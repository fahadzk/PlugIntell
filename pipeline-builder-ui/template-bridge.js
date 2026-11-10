/* Trusted declarative-template bridge. No HTML evaluation or direct API access. */
(function(global){
  'use strict';
  let runId='',sequence=-1,latest=null,selectedNodeId=null;
  const settingDefaults=new Map();
  const all=selector=>document.querySelectorAll(selector);
  const text=(tag,value,className)=>{const element=document.createElement(tag);element.textContent=String(value??'');if(className)element.className=className;return element};
  function stage(descriptor,state,interactive=false){
    const section=document.createElement('section');section.className='template-stage';section.setAttribute('data-status',state.status||'WAITING');section.append(text('strong',descriptor.label||descriptor.pluginId||descriptor.id),text('p',(state.status||'WAITING').replaceAll('_',' ')));
    if(interactive){section.setAttribute('role','button');section.setAttribute('tabindex','0');section.setAttribute('data-template-stage-id',descriptor.id);section.setAttribute('aria-pressed',String(selectedNodeId===descriptor.id));section.onclick=()=>{selectedNodeId=descriptor.id;renderStages(latest)};section.onkeydown=event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();section.onclick()}}}
    const progress=state.progress||{},completed=typeof progress.completed==='number'?progress.completed:NaN,total=typeof progress.total==='number'?progress.total:NaN,known=Number.isFinite(completed)&&Number.isFinite(total)&&total>0;
    if(known){const bar=document.createElement('progress');bar.max=100;bar.value=Math.max(0,Math.min(100,100*completed/total));bar.setAttribute('aria-label',(descriptor.label||descriptor.id)+' progress');section.append(bar)}
    const details=known?`${completed} / ${total} ${progress.unit||'units'}`:Number.isFinite(completed)?`${completed} ${progress.unit||'units'} · total unknown`:'Progress not reported';section.append(text('small',(state.status==='SUCCEEDED'?'Completed · ':'')+details));if(state.attempt)section.append(text('small','Attempt '+state.attempt));if(Number.isFinite(state.durationMillis))section.append(text('small','Measured duration: '+state.durationMillis+' ms'));return section;
  }
  function renderStages(snapshot){
    if(!snapshot)return;const nodes=snapshot.descriptor?.nodes||[],current=snapshot.nodes||{},focusedId=document.activeElement?.getAttribute('data-template-stage-id');
    all('[data-pipeline-nodes]').forEach(element=>element.replaceChildren(...nodes.map(node=>stage(node,current[node.id]||{},true))));
    all('[data-pipeline-inspector]').forEach(element=>{element.replaceChildren();const node=nodes.find(item=>item.id===selectedNodeId);if(!node){element.append(text('p','No stages in this descriptor.'));return}const state=current[node.id]||{};element.append(stage(node,state),text('small',node.pluginId||node.id));for(const [key,label]of [['inputSummary','Input summary'],['outputSummary','Output summary']]){if(!state[key]||typeof state[key]!=='object')continue;const list=document.createElement('dl');for(const [name,value]of Object.entries(state[key]).slice(0,12))if(['string','number','boolean'].includes(typeof value))list.append(text('dt',name),text('dd',value));if(list.children.length)element.append(text('h3',label),list)}});
    if(focusedId)[...all('[data-template-stage-id]')].find(element=>element.getAttribute('data-template-stage-id')===focusedId)?.focus({preventScroll:true});
  }
  function receiveSnapshot(snapshot){
    if(!snapshot||typeof snapshot!=='object'||!snapshot.runId||!Number.isSafeInteger(Number(snapshot.sequence)))return false;
    if(snapshot.runId===runId&&Number(snapshot.sequence)<sequence)return false;runId=snapshot.runId;sequence=Number(snapshot.sequence);
    const changedRun=snapshot.runId!==latest?.runId;latest=snapshot;
    const descriptor=snapshot.descriptor||{},nodes=descriptor.nodes||[],current=snapshot.nodes||{},settings=descriptor.capabilities?.template?.settings||descriptor.template?.settings||snapshot.template?.settings||{};
    if(changedRun||!nodes.some(node=>node.id===selectedNodeId))selectedNodeId=nodes[0]?.id||null;
    document.documentElement.dataset.templateDensity=settings.compact===true?'compact':'comfortable';document.documentElement.dataset.templateLayout=['cards','list'].includes(settings.layout)?settings.layout:'';
    if(typeof settings.accent==='string'&&/^#[0-9a-f]{6}$/i.test(settings.accent))document.documentElement.style.setProperty('--accent',settings.accent);else document.documentElement.style.removeProperty('--accent');
    all('[data-template-setting]').forEach(element=>{if(!settingDefaults.has(element))settingDefaults.set(element,element.textContent);const value=settings[element.getAttribute('data-template-setting')];element.textContent=['string','number','boolean'].includes(typeof value)?String(value):settingDefaults.get(element)});
    all('[data-pipeline-events-panel]').forEach(element=>element.hidden=settings.showTimeline===false);all('[data-pipeline-health-panel]').forEach(element=>element.hidden=settings.showHealth===false);
    all('[data-pipeline-run]').forEach(element=>element.textContent=snapshot.runId);all('[data-pipeline-sequence]').forEach(element=>element.textContent=String(snapshot.sequence));all('[data-pipeline-workflow]').forEach(element=>element.textContent=String(descriptor.workflow||'Workflow').replaceAll('_',' '));
    const states=nodes.map(node=>current[node.id]?.status||'WAITING'),counts={total:nodes.length,succeeded:states.filter(status=>status==='SUCCEEDED').length,failed:states.filter(status=>status==='FAILED').length,active:states.filter(status=>['RUNNING','RETRYING','CANCELLATION_REQUESTED'].includes(status)).length};
    all('[data-pipeline-count]').forEach(element=>element.textContent=String(counts[element.getAttribute('data-pipeline-count')]??0));
    all('[data-pipeline-status]').forEach(element=>{element.textContent=String(snapshot.status||'WAITING').replaceAll('_',' ');element.setAttribute('data-status',snapshot.status||'WAITING')});
    all('[data-pipeline-title]').forEach(element=>element.textContent=descriptor.capabilities?.template?.settings?.title||descriptor.template?.settings?.title||snapshot.template?.settings?.title||descriptor.name||'Workflow');
    renderStages(snapshot);
    all('[data-pipeline-connections]').forEach(element=>element.replaceChildren(...(descriptor.edges||[]).map(edge=>text('li',(nodes.find(node=>node.id===edge.from)?.label||edge.from)+' → '+(nodes.find(node=>node.id===edge.to)?.label||edge.to)))));
    all('[data-pipeline-health]').forEach(element=>{const health=Array.isArray(snapshot.health)?snapshot.health:[];element.replaceChildren(...health.map(adapter=>{const card=text('article','','template-health');card.append(text('strong',adapter.id||'Adapter'),text('p',adapter.status||'Unknown'));const list=document.createElement('dl');for(const name of ['queued','delivered','dropped','coalesced','failed'])if(Number.isFinite(adapter[name]))list.append(text('dt',name),text('dd',adapter[name]));card.append(list);return card}));if(!health.length)element.append(text('p','No delivery health has been reported.'))});
    all('[data-pipeline-node]').forEach(element=>{const id=element.getAttribute('data-pipeline-node'),node=nodes.find(item=>item.id===id);if(node)element.replaceChildren(stage(node,current[id]||{}))});
    const limit=Number.isInteger(settings.timelineLimit)?Math.max(1,Math.min(100,settings.timelineLimit)):100;
    all('[data-pipeline-events]').forEach(element=>{const items=(snapshot.events||[]).filter(event=>event.visible!==false).slice(-limit).map(event=>{const item=text('li',`${event.type||'EVENT'}${event.nodeId?' · '+event.nodeId:''}${event.message?' · '+event.message:''}`,'template-event');item.setAttribute('data-severity',event.severity||'INFO');return item});element.replaceChildren(...items)});
    return true;
  }
  global.addEventListener('message',event=>{if(event.source!==global.parent)return;const message=event.data;if(message?.schemaVersion!==1||message.type!=='plugintell.snapshot')return;receiveSnapshot(message.data)});
  global.PlugIntellTemplateBridge={receiveSnapshot};
})(window);
