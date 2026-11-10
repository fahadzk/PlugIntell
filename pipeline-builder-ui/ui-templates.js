/* Studio-only template catalog and configuration. Renderers receive projected state, never credentials. */
let previewTemplateMetadata=null,previewTemplateSettings={},templatePreviewSequence=0;

function templateDefaults(metadata){return Object.fromEntries(Object.entries(metadata?.settingsSchema?.properties||{}).filter(([,rule])=>Object.hasOwn(rule,'default')).map(([key,rule])=>[key,rule.default]))}
function validateTemplateSettingsClient(settings,metadata){
 if(!settings||Array.isArray(settings)||typeof settings!=='object'||Object.keys(settings).length>32)throw Error('Use a settings object with at most 32 fields.');
 const schema=metadata?.settingsSchema,properties=schema?.properties||{};
 for(const [key,value]of Object.entries(settings)){
  if(!['string','number','boolean'].includes(typeof value)||typeof value==='number'&&!Number.isFinite(value)||typeof value==='string'&&value.length>2048)throw Error('Use bounded text, booleans or finite numbers for template settings.');
  const rule=properties[key];if(!rule){if(schema?.additionalProperties===false)throw Error('Unknown template setting: '+key);continue}
  if(rule.type==='integer'?!Number.isInteger(value):rule.type==='number'?typeof value!=='number':typeof value!==rule.type)throw Error('Invalid type for '+key+'.');
  if(typeof value==='string'&&value.length>(rule.maxLength??2048))throw Error(key+' is too long.');
  if(rule.format==='color'&&!/^#[0-9a-f]{6}$/i.test(value))throw Error('Use a six-digit hex color for '+key+'.');
  if(typeof value==='number'&&(value<(rule.minimum??-Infinity)||value>(rule.maximum??Infinity)))throw Error(key+' is outside the allowed range.');
  if(rule.enum&&!rule.enum.includes(value))throw Error('Choose a declared value for '+key+'.');
 }
 for(const key of schema?.required||[])if(!Object.hasOwn(settings,key))throw Error('Missing required template setting: '+key);
}
function renderTemplateSettingFields(root,metadata,settings,onChange){
 root.replaceChildren();
 for(const [name,rule]of Object.entries(metadata?.settingsSchema?.properties||{})){
  const label=document.createElement('label'),input=document.createElement(rule.enum?'select':'input');label.className='template-setting-field';
  const caption=document.createElement('span');caption.textContent=rule.title||name;input.dataset.templateSetting=name;
  if(rule.enum){for(const choice of rule.enum)input.append(new Option(String(choice),String(choice)))}
  else input.type=rule.type==='boolean'?'checkbox':rule.format==='color'?'color':['integer','number'].includes(rule.type)?'number':'text';
  const value=Object.hasOwn(settings,name)?settings[name]:rule.default;
  if(rule.type==='boolean'&&!rule.enum){input.checked=value===true;label.classList.add('checkbox-label');label.append(input,caption)}else{input.value=value??'';label.append(caption,input)}
  if(rule.maxLength!==undefined)input.maxLength=rule.maxLength;if(rule.minimum!==undefined)input.min=String(rule.minimum);if(rule.maximum!==undefined)input.max=String(rule.maximum);if(rule.type==='integer')input.step='1';else if(rule.type==='number')input.step='any';
  if(rule.description){const help=document.createElement('small');help.textContent=rule.description;help.className='muted';label.append(help)}
  const change=()=>{const next={...settings};if(input.type==='checkbox')next[name]=input.checked;else if(['number','integer'].includes(rule.type)){if(!input.value.trim())delete next[name];else next[name]=Number(input.value)}else if(rule.type==='boolean')next[name]=input.value==='true';else next[name]=input.value;
   try{validateTemplateSettingsClient(next,metadata);Object.keys(settings).forEach(key=>delete settings[key]);Object.assign(settings,next);input.removeAttribute('aria-invalid');input.setCustomValidity('');onChange({...next})}catch(error){input.setAttribute('aria-invalid','true');input.setCustomValidity(error.message);setCapabilityStatus(error.message,'error')}};
  input.onchange=change;if(!rule.enum&&rule.type!=='boolean')input.oninput=change;root.append(label);
 }
 if(!root.children.length){const note=document.createElement('p');note.className='muted small-copy';note.textContent='This template has no declared form fields. Use advanced JSON for its documented settings.';root.append(note)}
}
function uiTemplateMetadata(id){return templateCatalog.find(template=>template.id===id)||(['standard','water'].includes(id)?{id,name:id==='water'?'Water pipeline':'Live workflow',version:'1',settingsSchema:{type:'object',properties:{title:{type:'string'},accent:{type:'string'}}}}:null)}
function currentTemplateMetadata(){return uiTemplateMetadata(capabilities.template.id)}
function renderTemplateConfiguration(){
 const metadata=currentTemplateMetadata();$('#template-summary').textContent=metadata?metadata.description||'Configure the Local UI view.':'The pinned template is unavailable. Import its original ZIP to restore it.';
 renderTemplateSettingFields($('#template-fields'),metadata,capabilities.template.settings||{},settings=>{capabilities.template.settings=settings;$('#template-settings').value=JSON.stringify(settings,null,2);$('#template-settings').removeAttribute('aria-invalid');$('#template-settings').setCustomValidity('');markDirty()});
 $('#template-preview-current').disabled=!metadata?.rendererUrl;$('#download-template-current').hidden=!metadata?.bundleUrl;
}
function selectUiTemplate(id,ensureUi=false,settings){
 const metadata=uiTemplateMetadata(id);if(!metadata)throw Error('Template metadata is unavailable.');
 const next=settings===undefined?templateDefaults(metadata):{...settings};validateTemplateSettingsClient(next,metadata);
 if(ensureUi&&!capabilities.adapters.some(adapter=>adapter.type==='ui')){const adapter=addAdapterInstance('ui');if(!adapter)throw Error('Local UI adapter metadata is unavailable.');for(const workflow of workflowTypes)if(nodes.some(node=>node.workflow===workflow))capabilities.subscriptions.push({adapterId:adapter.id,workflow,nodeIds:[],eventTypes:[],severities:[]})}
 if(ensureUi)selectedAdapterId=capabilities.adapters.find(adapter=>adapter.type==='ui')?.id||selectedAdapterId;
 capabilities.template={id:metadata.id,version:metadata.version||'1',settings:next};markDirty();renderCapabilitiesDialog();render();if(catalogMode==='templates')renderTemplateCatalog();
}
function setStudioCatalog(mode){
 catalogMode=['processing','adapters','templates'].includes(mode)?mode:'processing';$('#catalog-mode').querySelectorAll('[data-catalog]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.catalog===catalogMode)));
 $('#plugin-usage').hidden=catalogMode!=='processing';$('#catalog-hint').textContent=catalogMode==='templates'?'Choose a view for Local UI. Templates observe state; processing edges stay separate.':catalogMode==='adapters'?'Drop an adapter onto a node or workflow to observe events.':'Drag a plugin into its workflow, or use Add.';
 $('#plugin-filter').value='';$('#plugin-filter').placeholder=catalogMode==='templates'?'Search UI templates':catalogMode==='adapters'?'Search adapters':'Search plugins';renderPlugins();
}
function renderTemplateCatalog(){
 const root=$('#plugin-list'),query=$('#plugin-filter').value.toLowerCase();root.replaceChildren();
 for(const metadata of templateCatalog.filter(template=>(template.name+' '+template.id+' '+template.description).toLowerCase().includes(query))){
  const card=document.createElement('article');card.className='plugin-option template-option';card.classList.toggle('is-selected',capabilities.template.id===metadata.id);
  const swatch=document.createElement('div');swatch.className='template-swatch '+(['aurora','midnight'].includes(metadata.theme)?metadata.theme:'neutral');swatch.setAttribute('aria-hidden','true');for(let i=0;i<3;i++){const shape=document.createElement('span');swatch.append(shape)}
  const heading=document.createElement('div');heading.className='catalog-option-heading';const title=document.createElement('strong');title.textContent=metadata.name;heading.append(title,informationButton('template',metadata));
  const description=document.createElement('small');description.className='muted';description.textContent=metadata.description||'Reusable execution monitor';const pin=document.createElement('small');pin.className='template-pin';pin.textContent='v'+metadata.version+' · '+(metadata.executable?'Trusted JavaScript renderer':'Declarative view');
  const actions=document.createElement('div');actions.className='template-card-actions';const use=makeButton(capabilities.template.id===metadata.id?'Configure selected':'Use with Local UI','check','quiet');use.onclick=()=>{try{if(capabilities.template.id!==metadata.id)selectUiTemplate(metadata.id,true);else if(!capabilities.adapters.some(adapter=>adapter.type==='ui'))selectUiTemplate(metadata.id,true,capabilities.template.settings);openCapabilities();setCapabilityStatus('Template configured for Local UI. Save and publish; select Micronaut when generating a web application.','ok')}catch(error){status(error.message,'error')}};actions.append(use);
  if(metadata.rendererUrl){const preview=makeButton('Preview','preview','quiet');preview.onclick=()=>openTemplatePreview(metadata);actions.append(preview)}
  card.append(swatch,heading,description,pin,actions);root.append(card);
 }
 if(!root.children.length){const empty=document.createElement('p');empty.className='empty-state';empty.textContent='No UI templates match this search.';root.append(empty)}
 const importButton=makeButton('Import external template ZIP','import','secondary');importButton.onclick=()=>{openCapabilities();$('#template-import-section').open=true;$('#template-file').focus()};root.append(importButton);
}
function safeTemplateUrl(value,kind){const url=new URL(value,location.origin),pattern=kind==='bundle'?/^\/api\/v1\/templates\/[A-Za-z0-9_-]+\/bundle$/:/^\/ui\/templates\/[A-Za-z0-9_-]+\/index\.html$/;if(url.origin!==location.origin||!pattern.test(url.pathname)||url.search||url.hash)throw Error('Template resources must be served by this application.');return url.pathname}
async function downloadUiTemplate(metadata){if(!metadata?.bundleUrl)return;try{const blob=await api(safeTemplateUrl(metadata.bundleUrl,'bundle'),{responseType:'blob'}),url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=metadata.id+'.zip';link.click();URL.revokeObjectURL(url)}catch(error){setCapabilityStatus('Template download failed: '+error.message,'error')}}
function templatePreviewSnapshot(){
 const definition=[['parse','Parse documents','extract-text'],['split','Split into chunks','static-splitter'],['embed','Create embeddings','ollama-embedding'],['store','Write confirmed records','neo4j-writer']].map(([id,label,pluginId])=>({id,label,pluginId}));
 return{runId:'preview-sample',sequence:++templatePreviewSequence,status:'RUNNING',template:{id:previewTemplateMetadata.id,settings:{...previewTemplateSettings}},descriptor:{name:'Document intelligence',workflow:'DOCUMENT_INGESTION',nodes:definition,edges:[{from:'parse',to:'split'},{from:'split',to:'embed'},{from:'embed',to:'store'}]},nodes:{parse:{status:'SUCCEEDED',attempt:1,durationMillis:120,inputSummary:{type:'File'},outputSummary:{type:'TextDocument',pages:6}},split:{status:'SUCCEEDED',attempt:1,durationMillis:18,progress:{completed:24,total:24,unit:'chunks'}},embed:{status:'RUNNING',attempt:1,progress:{completed:8,total:24,unit:'chunks'}},store:{status:'WAITING',attempt:0}},health:[{id:'sample-local-ui',status:'UP',queued:0,delivered:17,dropped:0,coalesced:0,failed:0}],events:[{type:'NODE_SUCCEEDED',nodeId:'parse',severity:'INFO',message:'Sample: document parsed'},{type:'NODE_SUCCEEDED',nodeId:'split',severity:'INFO',message:'Sample: 24 chunks produced'},{type:'PROGRESS',nodeId:'embed',severity:'INFO',message:'Sample: 8 chunks embedded'}]};
}
function updateTemplatePreview(){if(previewTemplateMetadata)$('#template-preview-frame').contentWindow?.postMessage({type:'plugintell.snapshot',schemaVersion:1,data:templatePreviewSnapshot()},'*')}
function openTemplatePreview(metadata){
 const url=safeTemplateUrl(metadata.rendererUrl,'renderer');previewTemplateMetadata=metadata;previewTemplateSettings={...templateDefaults(metadata),...(capabilities.template.id===metadata.id?capabilities.template.settings:{})};
 $('#template-preview-title').textContent=metadata.name;$('#template-preview-description').textContent='Sample data preview · No workflow is running. Settings apply only when you choose Use with Local UI.';
 const frame=$('#template-preview-frame');frame.setAttribute('sandbox','allow-scripts');frame.title=metadata.name+' sample preview';frame.onload=updateTemplatePreview;frame.src=url;
 renderTemplateSettingFields($('#template-preview-fields'),metadata,previewTemplateSettings,settings=>{previewTemplateSettings=settings;updateTemplatePreview()});$('#template-preview-dialog').showModal();
}
function initializeUiTemplates(){
 $('#template-preview-current').onclick=()=>{const metadata=currentTemplateMetadata();if(metadata?.rendererUrl)openTemplatePreview(metadata)};
 $('#download-template-current').onclick=()=>downloadUiTemplate(currentTemplateMetadata());
 $('#browse-ui-templates').onclick=()=>{$('#capabilities-dialog').close();setStudioCatalog('templates')};
 $('#use-preview-template').onclick=()=>{if(!previewTemplateMetadata)return;try{selectUiTemplate(previewTemplateMetadata.id,true,previewTemplateSettings);$('#template-preview-dialog').close();openCapabilities();setCapabilityStatus('Local UI template selected. Save and publish to pin this view and its settings.','ok')}catch(error){setCapabilityStatus(error.message,'error')}};
 $('#template-preview-dialog').addEventListener('close',()=>{$('#template-preview-frame').removeAttribute('src');$('#template-preview-frame').onload=null;previewTemplateMetadata=null});
}
