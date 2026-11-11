const $=selector=>document.querySelector(selector);
try{const theme=localStorage.getItem('plugintell.theme'),density=localStorage.getItem('plugintell.density');if(['system','light','dark'].includes(theme))document.documentElement.dataset.theme=theme;if(['comfortable','compact'].includes(density))document.documentElement.dataset.density=density}catch{}
let token=sessionStorage.getItem('plugintell.accessToken'),loginPending=null;
function signIn(){if(loginPending)return loginPending;loginPending=new Promise(resolve=>{window.resolveProjectLogin=resolve;$('#login-error').textContent='';$('#login-dialog').showModal()}).finally(()=>{loginPending=null;delete window.resolveProjectLogin});return loginPending}
$('#login-form').addEventListener('submit',async event=>{event.preventDefault();try{const response=await fetch('/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:$('#login-username').value,password:$('#login-password').value})}),data=await response.json();if(!response.ok)throw Error(data.error||'Sign in failed.');token=data.accessToken;sessionStorage.setItem('plugintell.accessToken',token);$('#login-password').value='';$('#login-dialog').close();window.resolveProjectLogin?.(token)}catch(error){$('#login-error').textContent=error.message}});
$('#login-dialog').addEventListener('cancel',()=>window.resolveProjectLogin?.(null));
async function request(path,options={}){const headers={...(options.headers||{})};if(options.body&&!headers['Content-Type'])headers['Content-Type']='application/json';if(token)headers.Authorization=`Bearer ${token}`;let response=await fetch(path,{...options,headers});if(response.status===401){token=null;sessionStorage.removeItem('plugintell.accessToken');token=await signIn();if(!token)throw Error('Authentication is required.');headers.Authorization=`Bearer ${token}`;response=await fetch(path,{...options,headers})}if(!response.ok){let message=await response.text();try{const body=JSON.parse(message);message=body.error||body.errors?.join('\n')||message}catch{}throw Error(message||`HTTP ${response.status}`)}return response}
async function api(path,options){const response=await request(path,options);return response.status===204?null:response.json()}

let recommendation=null,recommendedPrompt='',catalog=[],profiles=[],configInputs=[],createdRecord=null,savedDefinition='',busy=false;
function setProjectButtons(enabled){$('#create-workflow').disabled=!enabled;$('#create-build').disabled=!enabled}
function setStage(stage){for(const name of ['describe','review','create']){const step=$('#stage-'+name);if(name===stage)step.setAttribute('aria-current','step');else step.removeAttribute('aria-current')}}
function updatePromptCount(){$('#prompt-count').textContent=`${$('#prompt').value.length.toLocaleString()} / 12,000 characters`}
function badge(id,text,ready){const element=$('#'+id);element.textContent=text;element.className='state-badge '+(ready?'ai-ready':'ai-needs-attention')}
function updateSettingReadiness(){
 const missing=configInputs.filter(field=>field.required&&field.property.type!=='boolean'&&!field.input.value.trim()).length;
 badge('settings-state',missing?`${missing} required setting${missing===1?'':'s'} missing`:'Plugin settings ready',!missing);
 $('#settings-count').textContent=missing?`${missing} to complete before building`:'Review or customize';
 return missing;
}
function failField(field,message){$('#plugin-settings').open=true;field.input.setAttribute('aria-invalid','true');field.input.focus();throw Error(message)}
function invalidateRecommendation(){recommendation=null;createdRecord=null;savedDefinition='';$('#project-panel').hidden=true;$('#recommendation').hidden=true;setProjectButtons(false);setStage('describe')}
function renderRecommendation(result){
 setStage('review');recommendation=result;recommendedPrompt=$('#prompt').value;createdRecord=null;savedDefinition='';$('#project-panel').hidden=true;$('#open-created').hidden=true;setProjectButtons(false);
 for(const lane of ['ingestion','query']){
  const chain=result[lane];$('#'+lane+'-title').textContent=(lane==='ingestion'?'Ingestion':'Query')+(chain.complete?'':' - incomplete');
  badge(lane+'-state',chain.complete?`${chain.steps.length} plugins`:'Incomplete',chain.complete);
  const list=$('#'+lane);list.replaceChildren();
  for(const step of chain.steps){const item=document.createElement('li');item.textContent=step.missing?`${step.step}: ${step.missing}`:step.name;item.dataset.step=step.step||'';if(step.missing)item.className='error';list.append(item)}
 }
 const complete=result.ingestion.complete&&result.query.complete;badge('recommendation-state',complete?'Ready to configure':'Needs your attention',complete);$('#recommendation-help').textContent=complete?'Both lanes are in workflow order. Review your profile and choose how to continue below.':'A required plugin is unavailable. Update your prompt or install a compatible plugin, then suggest again.';
 $('#recommendation').hidden=false;
}

function selectedProfile(){return profiles.find(profile=>profile.id===$('#ai-profile').value)}
function profileSettings(profile,plugin){
 const settings=profile?.pluginConfigs?.[plugin.id]||(profile?.pluginId===plugin.id?{pluginVersion:profile.pluginVersion,config:profile.config||{}}:null);
 return settings?.pluginVersion===plugin.version?settings:null;
}
function selectedPlugins(){
 if(!recommendation||$('#prompt').value!==recommendedPrompt||!recommendation.ingestion.complete||!recommendation.query.complete)throw Error('Get a complete recommendation for the current prompt first.');
 return ['ingestion','query'].flatMap(lane=>recommendation[lane].steps.map(step=>{
  const plugin=catalog.find(p=>p.id===step.id&&p.version===step.version);if(!plugin)throw Error(`Plugin ${step.id} changed or is unavailable. Suggest plugins again.`);return plugin;
 }));
}
async function prepareProject(){
 const prompt=recommendedPrompt;
 const loaded=await Promise.all([api('/api/v1/plugins'),api('/api/v1/connections')]);
 if(!recommendation||$('#prompt').value!==prompt)return;
 [catalog,profiles]=loaded;selectedPlugins();
 const select=$('#ai-profile');select.replaceChildren(new Option('No profile - use plugin settings',''));
 profiles.forEach(profile=>select.append(new Option(profile.name+(profile.defaultProfile?' (Default)':''),profile.id)));
 select.value=profiles.find(profile=>profile.defaultProfile)?.id||'';
 const match=prompt.match(/(?:project|called|named)\s*["']([^"']{1,120})["']/i);
 $('#ai-project-name').value=match?match[1]:'My RAG Project';
 $('#project-status').textContent='';$('#project-panel').hidden=false;renderProjectSettings();setProjectButtons(true);setStage('create');
}
function configLabel(key,property){
 if(property.title&&property.title!==key)return property.title;
 const labels={OLLAMA_BASE_URL:'Ollama server URL',OLLAMA_EMBEDDING_MODEL:'Embedding model',OLLAMA_CHAT_MODEL:'Answer model',OLLAMA_TIMEOUT_SECONDS:'Request timeout (seconds)',NEO4J_URI:'Neo4j connection URI',NEO4J_USERNAME:'Neo4j username',NEO4J_PASSWORD:'Neo4j password reference',NEO4J_DATABASE:'Neo4j database',NEO4J_VECTOR_INDEX:'Vector index name',NEO4J_VECTOR_DIMENSIONS:'Embedding dimensions',RAG_SEARCH_TOP_K:'Retrieved results (top K)',RAG_SEARCH_INCLUDE_CITATIONS:'Include source citations',PLUGIN_LANGCHAIN4J_CHUNK_SIZE:'Chunk size (characters)',PLUGIN_LANGCHAIN4J_CHUNK_OVERLAP:'Chunk overlap (characters)'};
 return labels[key]||key.replace(/^PLUGIN_/,'').toLowerCase().replace(/_/g,' ').replace(/^./,letter=>letter.toUpperCase());
}
function renderProjectSettings(){
 const root=$('#ai-config');root.replaceChildren();configInputs=[];const profile=selectedProfile();let covered=0;
 for(const plugin of selectedPlugins()){
  const inherited=profileSettings(profile,plugin);if(inherited)covered++;
  const properties=plugin.configurationSchema?.properties||{},required=new Set(plugin.configurationSchema?.required||[]);
  if(!Object.keys(properties).length)continue;
  const group=document.createElement('fieldset'),legend=document.createElement('legend');legend.textContent=plugin.name+(inherited?' - from profile':'');group.append(legend);
  for(const [key,property] of Object.entries(properties)){
   const label=document.createElement('label');const title=configLabel(key,property);label.textContent=title+(required.has(key)?' *':'');
   const input=document.createElement('input');input.type=property.type==='boolean'?'checkbox':['number','integer'].includes(property.type)?'number':'text';
   let value=inherited?.config?.[key]??property.default??'';
   const topK=recommendedPrompt.match(/\btop\s+(\d+)\b/i);if(key==='RAG_SEARCH_TOP_K'&&topK)value=Number(topK[1]);
   if(property.type==='boolean')input.checked=Boolean(value);else input.value=String(value);
   if(property.minimum!==undefined)input.min=property.minimum;if(property.maximum!==undefined)input.max=property.maximum;
   if(property.type==='integer')input.step='1';
   if(property.format==='secret-reference')input.placeholder='secret://env/VARIABLE_NAME';
   input.id=`setting-${plugin.id}-${key}`;input.autocomplete='off';input.addEventListener('input',()=>{input.removeAttribute('aria-invalid');updateSettingReadiness()});const help=document.createElement('small');help.textContent=property.description||'';label.append(input,help);group.append(label);
   configInputs.push({plugin,key,title,property,input,required:required.has(key),inherited});
  }
  root.append(group);
 }
 $('#profile-help').textContent=profile?`${profile.defaultProfile?'Default profile: ':''}${profile.name} supplies settings for ${covered} plugin${covered===1?'':'s'}. Other steps use plugin defaults. You can override any field below.`:'No default profile is marked. Choose a profile here or mark one under Connections in Studio. You can still create an editable draft.';
 const missing=updateSettingReadiness();$('#plugin-settings').open=missing>0;
}
function makeProjectDefinition(complete=false){
 selectedPlugins();const name=$('#ai-project-name').value.trim();if(!name){$('#ai-project-name').focus();throw Error('Enter a project name.');}
 const workflows={};
 for(const [lane,type] of [['ingestion','DOCUMENT_INGESTION'],['query','QUERY_PROCESSING']]){
  const nodes=recommendation[lane].steps.map((step,index)=>{
   const plugin=catalog.find(p=>p.id===step.id),config={},inherited=profileSettings(selectedProfile(),plugin);
   if(inherited)config.connectionRef=selectedProfile().id;
   for(const field of configInputs.filter(f=>f.plugin.id===plugin.id)){
    const {input,property,key}=field;let value=property.type==='boolean'?input.checked:input.value.trim();
    if(value===''){if(complete&&field.required)failField(field,`${plugin.name}: enter ${field.title} in Plugin settings, or use a profile that supplies it.`);continue}
    if(['number','integer'].includes(property.type)){value=Number(value);if(!Number.isFinite(value)||(property.type==='integer'&&!Number.isInteger(value)))failField(field,`${key} must be a valid ${property.type}.`);if(property.minimum!==undefined&&value<property.minimum||property.maximum!==undefined&&value>property.maximum)failField(field,`${key} is outside its allowed range.`)}
    if(property.format==='secret-reference'&&!/^secret:\/\/env\/[A-Za-z_][A-Za-z0-9_]*$/.test(value))failField(field,`${key} must use secret://env/VARIABLE_NAME. Raw passwords cannot be saved.`);
    if(!inherited||value!==inherited.config?.[key])config[key]=value;
   }
   return {id:`${type}/step-${index+1}`,pluginId:step.id,pluginVersion:step.version,config};
  });
  workflows[type]={nodes,edges:nodes.slice(1).map((node,index)=>({from:nodes[index].id,to:node.id}))};
 }
 return {name,type:'RAG_PROJECT',schemaVersion:2,nodes:[],edges:[],workflows};
}
function projectMessage(message,error=false){$('#project-status').textContent=message;$('#project-status').className=error?'error':'muted'}
async function createProject(build=false){
 if(busy)return;busy=true;setProjectButtons(false);$('#prompt').readOnly=true;$('#suggest').disabled=true;$('#create-workflow').textContent=build?'Create workflow in Studio':'Saving workflow...';$('#create-build').textContent=build?'Starting build...':'Create project and build';$('#error').textContent='';
 try{
  const definition=makeProjectDefinition(build),destination=$('#ai-destination').value.trim();
  if(build&&!destination){$('#ai-destination').setAttribute('aria-invalid','true');$('#ai-destination').focus();throw Error('Enter an absolute output folder for the build.');}
  if(build){const validation=await api('/api/v1/pipelines/validate',{method:'POST',body:JSON.stringify(definition)});if(!validation.valid)throw Error(validation.errors.join('\n'))}
  const serialized=JSON.stringify(definition);
  if(!createdRecord){projectMessage('Saving your editable workflow...');createdRecord=await api('/api/v1/pipelines',{method:'POST',body:serialized});savedDefinition=serialized}
  else if(serialized!==savedDefinition){createdRecord=await api(`/api/v1/pipelines/${encodeURIComponent(createdRecord.id)}`,{method:'PUT',body:JSON.stringify({expectedRevision:createdRecord.revision,definition})});savedDefinition=serialized}
  const studioUrl='/ui/studio.html?pipelineId='+encodeURIComponent(createdRecord.id);$('#open-created').href=studioUrl;$('#open-created').hidden=false;
  if(!build){location.href=studioUrl;return}
  projectMessage('Publishing this version and starting the build...');
  const published=await api(`/api/v1/pipelines/${encodeURIComponent(createdRecord.id)}/publish`,{method:'POST',body:JSON.stringify({expectedRevision:createdRecord.revision})});
  createdRecord=await api(`/api/v1/pipelines/${encodeURIComponent(createdRecord.id)}`);
  const artifactName=definition.name.replace(/[^A-Za-z0-9_-]/g,'').replace(/^[^A-Za-z]+/,'').slice(0,64)||'RagProject';
  const job=await api('/api/v1/projects/builds',{method:'POST',body:JSON.stringify({pipelineId:createdRecord.id,version:published.version,projectName:artifactName,destination,outputFormat:$('#ai-output-format').value||'jar'})});
  location.href=`/ui/project-build.html?pipelineId=${encodeURIComponent(createdRecord.id)}&buildId=${encodeURIComponent(job.id)}`;
 }catch(error){projectMessage(error.message,true)}
 finally{busy=false;$('#create-workflow').textContent='Create workflow in Studio';$('#create-build').textContent='Create project and build';$('#prompt').readOnly=false;setProjectButtons(Boolean(recommendation&&catalog.length));$('#suggest').disabled=false}
}
$('#ai-profile').onchange=()=>{try{renderProjectSettings()}catch(error){projectMessage(error.message,true)}};
$('#create-workflow').onclick=()=>createProject(false);$('#create-build').onclick=()=>createProject(true);
$('#prompt').addEventListener('input',()=>{updatePromptCount();if(!busy){const previous=Boolean(recommendation);invalidateRecommendation();if(previous)$('#status').textContent='Prompt changed. Suggest plugins again to refresh your workflow.'}});
$('#ai-destination').addEventListener('input',()=>$('#ai-destination').removeAttribute('aria-invalid'));
const examples={documents:'Create project "Document Library". Use Tika Document Parser and LangChain4j Splitter, Ollama embeddings, Neo4j storage, and answer questions with my network Ollama. Retrieve the top 5 results.',scanned:'Create project "Research Archive". Use PDF OCR to Text and Citation-Preserving Text Splitter for scanned PDFs, Ollama embeddings, Neo4j storage and Ollama answers with citations.'};
for(const [id,key] of [['example-documents','documents'],['example-scanned','scanned']])$('#'+id).onclick=()=>{if(busy||$('#suggest').disabled)return;$('#prompt').value=examples[key];invalidateRecommendation();updatePromptCount();$('#status').textContent='Example added. Adjust it to your needs, then suggest plugins.';$('#prompt').focus()};
$('#prompt').addEventListener('keydown',event=>{if((event.ctrlKey||event.metaKey)&&event.key==='Enter'&&!$('#suggest').disabled){event.preventDefault();$('#suggest-form').requestSubmit()}});
updatePromptCount();
$('#suggest-form').addEventListener('submit',async event=>{
 event.preventDefault();if(busy)return;const prompt=$('#prompt').value;invalidateRecommendation();$('#suggest').disabled=true;$('#suggest').setAttribute('aria-busy','true');$('#suggest').textContent='Finding plugins...';$('#error').textContent='';$('#status').textContent='Checking installed plugins and asking Laya for the best fit...';
 try{const result=await api('/api/v1/aistudio/recommend',{method:'POST',body:JSON.stringify({prompt})});if($('#prompt').value!==prompt)return;renderRecommendation(result);$('#status').textContent='Recommendation ready.';if(result.ingestion.complete&&result.query.complete)await prepareProject()}
 catch(error){$('#status').textContent='';$('#error').textContent=error.message}
 finally{$('#suggest').disabled=false;$('#suggest').removeAttribute('aria-busy');$('#suggest').textContent='Suggest plugins'}
});
