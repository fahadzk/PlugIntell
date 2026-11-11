const test=require('node:test');
const assert=require('node:assert/strict');
const {load}=require('./ui-test-dom.cjs');
test('renders catalog names in ordered lanes and missing roles in place',()=>{
 const page=load('aistudio.js','aistudio.html');
 page.context.result={ingestion:{complete:true,steps:['Extract display','Chunk display','Embed display','Writer display'].map(name=>({name}))},query:{complete:false,steps:[{step:'Retrieval',missing:'No compatible ChromaDB plugin. Install a compatible plugin.'},{name:'Answer display'}]}};
 page.evaluate('renderRecommendation(result)');
 assert.deepEqual(page.ids.get('ingestion').children.map(c=>c.textContent),['Extract display','Chunk display','Embed display','Writer display']);
 assert.match(page.ids.get('query').children[0].textContent,/Retrieval:.*ChromaDB/);
 assert.equal(page.ids.get('query').children[1].textContent,'Answer display');
 assert.match(page.ids.get('query-title').textContent,/incomplete/);
 assert.equal(page.ids.get('recommendation').hidden,false);
});
test('shows remote errors and enables retry, preserving original prompt',async()=>{
 let sent;
 const page=load('aistudio.js','aistudio.html',{fetch:async(url,options)=>{sent=JSON.parse(options.body);return {ok:false,status:502,text:async()=>JSON.stringify({error:'Laya is unreachable. Check LAYA_BASE_URL and try again.'})}}});
 page.ids.get('prompt').value='  PDF RAG  ';
 await page.ids.get('suggest-form').onsubmit({preventDefault(){}});
 assert.equal(sent.prompt,'  PDF RAG  ');
 assert.match(page.ids.get('error').textContent,/LAYA_BASE_URL/);
 assert.equal(page.ids.get('suggest').disabled,false);
 assert.equal(page.ids.get('recommendation').hidden,true);
});
const recommendation={ingestion:{complete:true,steps:[['tika-document-parser','Tika Document Parser'],['langchain4j-splitter','LangChain4j Splitter'],['ollama-embedding','Ollama Embedding'],['neo4j-writer','Neo4j Writer']].map(([id,name])=>({id,name,version:'1.0.0'}))},query:{complete:true,steps:[['neo4j-retrieval','Neo4j Retrieval'],['ollama-answer','Ollama Answer']].map(([id,name])=>({id,name,version:'1.0.0'}))}};
const descriptors=[...recommendation.ingestion.steps,...recommendation.query.steps].map(p=>({...p,configurationSchema:p.id.startsWith('tika')||p.id.startsWith('langchain')?{properties:{}}:{required:['MODEL'],properties:{MODEL:{type:'string'},URL:{type:'string',default:'http://localhost:11434'},PASSWORD:{type:'string',format:'secret-reference'}}}}));
const defaultProfile={id:'default-profile',name:'Home',defaultProfile:true,pluginConfigs:Object.fromEntries(descriptors.filter(p=>p.configurationSchema.required).map(p=>[p.id,{pluginVersion:'1.0.0',config:{MODEL:'installed-model',URL:'http://network:11434',PASSWORD:'secret://env/PROVIDER_PASSWORD'}}]))};
async function projectPage(handler,profile=defaultProfile){
 const calls=[];const page=load('aistudio.js','aistudio.html',{fetch:async(url,options={})=>{
  calls.push({url,method:options.method||'GET',body:options.body?JSON.parse(options.body):null});
  const data=url==='/api/v1/plugins'?descriptors:url==='/api/v1/connections'?[profile]:await handler(url,options,calls);
  return {ok:true,status:200,json:async()=>data};
 }});
 page.context.rec=recommendation;page.ids.get('prompt').value='Create project "Middle East" using Tika and LangChain4j';page.evaluate('renderRecommendation(rec)');await page.evaluate('prepareProject()');return {page,calls};
}
test('default profile supplies settings and saved draft preserves ordered editable lanes',async()=>{
 const {page,calls}=await projectPage(async()=>({id:'project-a',revision:1}));
 assert.equal(page.ids.get('ai-profile').value,'default-profile');
 await page.ids.get('create-workflow').onclick();
 const saved=calls.find(c=>c.url==='/api/v1/pipelines').body;
 assert.equal(saved.name,'Middle East');assert.equal(saved.type,'RAG_PROJECT');assert.equal(saved.schemaVersion,2);
 const ingestion=saved.workflows.DOCUMENT_INGESTION;
 assert.deepEqual(ingestion.nodes.map(n=>n.pluginId),recommendation.ingestion.steps.map(s=>s.id));
 assert.deepEqual(ingestion.edges.map(e=>[e.from,e.to]),ingestion.nodes.slice(1).map((n,i)=>[ingestion.nodes[i].id,n.id]));
 assert.equal(saved.workflows.QUERY_PROCESSING.edges.length,1);
 assert.deepEqual(ingestion.nodes[2].config,{connectionRef:'default-profile'});
 assert.equal(page.context.location.href,'/ui/studio.html?pipelineId=project-a');
 assert.equal(calls.filter(c=>c.url.endsWith('/publish')).length,0);
});
test('create and build validates, publishes and starts the selected project, then opens progress',async()=>{
 const {page,calls}=await projectPage(async url=>url.endsWith('/validate')?{valid:true}:url.endsWith('/publish')?{version:1}:url==='/api/v1/projects/builds'?{id:'job-a'}:{id:'project-a',revision:2});
 page.ids.get('ai-destination').value='D:/Projects/MiddleEast';page.ids.get('ai-output-format').value='jar';
 await page.ids.get('create-build').onclick();
 const build=calls.find(c=>c.url==='/api/v1/projects/builds').body;
 assert.deepEqual(build,{pipelineId:'project-a',version:1,projectName:'MiddleEast',destination:'D:/Projects/MiddleEast',outputFormat:'jar'});
 assert.match(page.context.location.href,/project-build.html\?pipelineId=project-a&buildId=job-a/);
 assert.ok(calls.findIndex(c=>c.url.endsWith('/validate'))<calls.findIndex(c=>c.url==='/api/v1/pipelines'));
});
test('missing settings allow a draft but block build before persistence',async()=>{
 const {page,calls}=await projectPage(async()=>({id:'draft',revision:1}),{id:'empty',name:'Empty',defaultProfile:true,pluginConfigs:{}});
 page.ids.get('ai-destination').value='D:/Projects/Draft';await page.ids.get('create-build').onclick();
 assert.match(page.ids.get('project-status').textContent,/model/i);assert.equal(calls.filter(c=>c.method==='POST').length,0);
 await page.ids.get('create-workflow').onclick();assert.equal(page.context.location.href,'/ui/studio.html?pipelineId=draft');
});
test('failed build keeps a link to the saved draft and retry does not create another project',async()=>{
 const {page,calls}=await projectPage(async url=>{
  if(url.endsWith('/validate'))return {valid:true};if(url.endsWith('/publish'))return {version:1};if(url==='/api/v1/projects/builds')throw Error('Output folder is not empty');return {id:'project-a',revision:2};
 });
 page.ids.get('ai-destination').value='D:/Projects/Existing';await page.ids.get('create-build').onclick();
 assert.match(page.ids.get('project-status').textContent,/not empty/);assert.equal(page.ids.get('open-created').hidden,false);
 assert.equal(page.ids.get('create-workflow').disabled,false);await page.ids.get('create-workflow').onclick();
 assert.equal(calls.filter(c=>c.url==='/api/v1/pipelines'&&c.method==='POST').length,1);
});
test('editing prompt invalidates recommendation and raw secrets are rejected',async()=>{
 const {page,calls}=await projectPage(async()=>({id:'project-a',revision:1}));
 page.evaluate("configInputs.find(f=>f.key==='PASSWORD').input.value='raw-password'");await page.ids.get('create-workflow').onclick();
 assert.match(page.ids.get('project-status').textContent,/Raw passwords/);assert.equal(calls.filter(c=>c.method==='POST').length,0);
 page.ids.get('prompt').value='Different workflow';page.ids.get('prompt').oninput();assert.equal(page.ids.get('project-panel').hidden,true);assert.equal(page.ids.get('create-workflow').disabled,true);
});
test('build page resumes an existing job without starting another build',async()=>{
 const calls=[];const job={id:'job-a',projectName:'MiddleEast',destination:'D:/Projects/MiddleEast',outputFormat:'jar',status:'FAILED',error:'test failure',logs:['Maven log']};
 const page=load('project-build.js','project-build.html',{location:{search:'?pipelineId=project-a&buildId=job-a'},fetch:async(url,options={})=>{calls.push({url,method:options.method||'GET'});return {ok:true,status:200,json:async()=>url==='/api/v1/pipelines'?[{id:'project-a',publishedVersion:1,definition:{name:'Middle East'}}]:job}}});
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(page.ids.get('build-panel').hidden,false);assert.equal(page.ids.get('build-log').textContent,'Maven log');assert.ok(calls.every(c=>c.method==='GET'));
});
test('example prompts are editable, counted, and do not call Laya automatically',()=>{
 const page=load('aistudio.js','aistudio.html');
 page.ids.get('example-documents').onclick();
 assert.match(page.ids.get('prompt').value,/Tika Document Parser/);assert.match(page.ids.get('prompt-count').textContent,/12,000 characters/);assert.equal(page.requests.length,0);assert.equal(page.ids.get('prompt').focused,true);
 page.ids.get('example-scanned').onclick();assert.match(page.ids.get('prompt').value,/PDF OCR to Text/);assert.equal(page.ids.get('stage-describe').getAttribute('aria-current'),'step');
 let submitted=0;page.ids.get('suggest-form').requestSubmit=()=>submitted++;
 page.ids.get('prompt').onkeydown({ctrlKey:true,key:'Enter',preventDefault(){}});assert.equal(submitted,1);
});
test('missing settings open the configuration section and focus the missing field',async()=>{
 const {page}=await projectPage(async()=>({id:'draft',revision:1}),{id:'empty',name:'Empty',defaultProfile:true,pluginConfigs:{}});
 assert.equal(page.ids.get('plugin-settings').open,true);assert.match(page.ids.get('settings-state').textContent,/missing/);
 page.ids.get('ai-destination').value='D:/Projects/Draft';await page.ids.get('create-build').onclick();
 const field=page.evaluate("configInputs.find(f=>f.required&&f.input.getAttribute('aria-invalid')==='true').input");assert.equal(field.focused,true);
 assert.equal(page.ids.get('stage-create').getAttribute('aria-current'),'step');
});
test('suggestion loading state is announced and cleared after an error',async()=>{
 let release;const page=load('aistudio.js','aistudio.html',{fetch:()=>new Promise(resolve=>release=resolve)});
 page.ids.get('prompt').value='A RAG workflow';const request=page.ids.get('suggest-form').onsubmit({preventDefault(){}});
 assert.equal(page.ids.get('suggest').disabled,true);assert.equal(page.ids.get('suggest').getAttribute('aria-busy'),'true');assert.match(page.ids.get('status').textContent,/Laya/);
 release({ok:false,status:502,text:async()=>JSON.stringify({error:'Laya is unreachable. Try again.'})});await request;
 assert.equal(page.ids.get('suggest').disabled,false);assert.equal(page.ids.get('suggest').getAttribute('aria-busy'),undefined);assert.match(page.ids.get('error').textContent,/Try again/);
});
