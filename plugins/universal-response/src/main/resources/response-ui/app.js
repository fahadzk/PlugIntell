import {el,renderers} from '/renderers.js';
const $=id=>document.getElementById(id);let currentId=null,loading=false;let followLatest=location.pathname==='/',routeId=location.pathname.startsWith('/responses/')?location.pathname.split('/').pop():null;
let preference;try{preference=localStorage.getItem('plugintell-response-theme');}catch{}
function theme(value){document.body.classList.toggle('dark',value==='dark'||value==='system'&&matchMedia('(prefers-color-scheme: dark)').matches);}
theme(preference||'system');$('theme').onclick=()=>{preference=document.body.classList.contains('dark')?'light':'dark';theme(preference);try{localStorage.setItem('plugintell-response-theme',preference);}catch{}};
async function request(path){const response=await fetch(path,{headers:{Accept:'application/json'},cache:'no-store'});if(!response.ok){const body=await response.json().catch(()=>({}));throw Error(body.error||'Response is unavailable.');}return response.json();}
function showError(error){$('error').hidden=false;$('error').textContent=error.message;$('connection').textContent='· unavailable';}
function card(block,response){
 const root=el('article','block'),head=el('div','block-header'),title=el('span','',block.type),body=el('div','block-body');head.append(title,el('span','spacer'));
 const views=block.options?.views?.length?block.options.views:[block.renderer];let selected=block.renderer;
 function draw(){body.replaceChildren();try{const renderer=renderers[selected]||renderers.text;renderer(body,block.data,{allowRemoteMedia:response.metadata?.allowRemoteMedia===true});}catch(error){body.append(el('p','warning','This view is unavailable: '+error.message));renderers.text(body,block.data);}}
 if(views.length>1){const picker=el('select');picker.setAttribute('aria-label','Presentation view');views.forEach(view=>picker.append(new Option(view,view)));picker.value=selected;picker.onchange=()=>{selected=picker.value;draw();};head.append(picker);}
 root.append(head,body);draw();return root;
}
function show(response){
 if(response.id===currentId)return;currentId=response.id;if(!preference)theme(response.metadata?.theme||'system');
 $('query').textContent=response.query||'Response';$('meta').textContent=`${new Date(response.createdAt).toLocaleString()} · ${response.blocks.length} blocks · ${response.status}`;$('blocks').replaceChildren();
 for(const block of response.blocks)$('blocks').append(card(block,response));
 $('warnings').replaceChildren();for(const warning of response.warnings||[])$('warnings').append(el('p','warning',warning));
 $('sources').hidden=!response.sources?.length;$('source-list').replaceChildren();for(const source of response.sources||[])$('source-list').append(el('p','',`${source.name} · page ${source.page} · ${source.id}`));
 $('downloads').hidden=!response.artifacts?.length;$('artifact-list').replaceChildren();for(const artifact of response.artifacts||[]){const link=el('a','download',`↓ ${artifact.name} · ${(artifact.bytes/1024).toFixed(1)} KB`);link.href='/api/artifacts/'+artifact.id;link.download=artifact.name;$('artifact-list').append(link);}
 document.title=(response.query||'Response').slice(0,70)+' · PlugIntell';$('error').hidden=true;for(const entry of $('history').children)entry.classList.toggle('active',entry.dataset.responseId===response.id);
}
async function load(){if(loading)return;loading=true;try{
 const responses=await request('/api/responses');$('connection').textContent='· connected';$('history').replaceChildren();
 for(const item of [...responses].reverse()){const b=el('button',item.id===currentId?'active':'',item.query||'Response');b.dataset.responseId=item.id;b.title=item.query;b.onclick=()=>{followLatest=false;routeId=item.id;history.pushState(null,'','/responses/'+item.id);refreshSelected();};$('history').append(b);}
 if(responses.length){await refreshSelected();}else if(routeId){throw Error('This response expired or belongs to a previous application session.');}
 }catch(error){showError(error);}finally{loading=false;}}
async function refreshSelected(){try{const response=await request(followLatest?'/api/responses/latest':'/api/responses/'+routeId);show(response);$('error').hidden=true;$('connection').textContent='· connected';}catch(error){showError(error);}}
$('latest').onclick=()=>{followLatest=true;routeId=null;history.pushState(null,'','/');load();};
window.addEventListener('popstate',()=>{followLatest=location.pathname==='/';routeId=followLatest?null:location.pathname.split('/').pop();load();});
load();setInterval(load,4000);
