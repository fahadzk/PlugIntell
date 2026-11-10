const {test}=require('node:test');
const assert=require('node:assert/strict');
const {load}=require('./ui-test-dom.cjs');
function fixture(){const app=load('template-bridge.js','monitor.html');for(const [id,attribute]of [['monitor-status','data-pipeline-status'],['standard-view','data-pipeline-nodes'],['monitor-timeline','data-pipeline-events']])app.ids.get(id).setAttribute(attribute,'');app.ids.get('monitor-node-details').setAttribute('data-pipeline-node','a');return app}
const snapshot=(sequence=3)=>({runId:'run',sequence,status:'RUNNING',descriptor:{nodes:[{id:'a',label:'<script>evil()</script>',pluginId:'fixture'}]},nodes:{a:{status:'RUNNING',attempt:2,progress:{completed:1,total:4}}},events:[{type:'PLUGIN_MESSAGE',nodeId:'a',severity:'INFO',message:'<img src=x>'}]});
test('declarative bridge renders live status, nodes, individual slots and timeline safely',()=>{
 const app=fixture();assert.equal(app.context.window.PlugIntellTemplateBridge.receiveSnapshot(snapshot()),true);assert.equal(app.ids.get('monitor-status').textContent,'RUNNING');assert.match(app.ids.get('standard-view').textContent,/<script>evil/);assert.equal(app.ids.get('standard-view').querySelector('script'),null);assert.equal(app.ids.get('standard-view').querySelector('progress').value,'25');assert.match(app.ids.get('monitor-node-details').textContent,/Attempt 2/);assert.match(app.ids.get('monitor-timeline').textContent,/<img src=x>/);assert.equal(app.ids.get('monitor-timeline').querySelector('img'),null);
});
test('declarative bridge accepts only versioned parent messages and rejects stale snapshots',()=>{
 const app=fixture(),parent={};app.context.window.parent=parent;const data={type:'plugintell.snapshot',schemaVersion:1,data:snapshot()};app.context.window.onmessage({source:{},data});assert.equal(app.ids.get('monitor-status').textContent,'');app.context.window.onmessage({source:parent,data:{...data,schemaVersion:9}});assert.equal(app.ids.get('monitor-status').textContent,'');app.context.window.onmessage({source:parent,data});assert.equal(app.ids.get('monitor-status').textContent,'RUNNING');assert.equal(app.context.window.PlugIntellTemplateBridge.receiveSnapshot({...snapshot(2),status:'FAILED'}),false);assert.equal(app.ids.get('monitor-status').textContent,'RUNNING');
});

test('declarative timeline respects visibility markers while stage state comes from projected snapshots',()=>{
 const app=fixture(),data=snapshot();data.events.push({type:'PROGRESS',visible:false,message:'Hidden progress'});data.events.push({type:'NODE_SUCCEEDED',visible:false,message:'Hidden lifecycle'});data.nodes.a.status='SUCCEEDED';app.context.window.PlugIntellTemplateBridge.receiveSnapshot(data);assert.equal(app.ids.get('monitor-timeline').children.length,1);assert.equal(app.ids.get('monitor-timeline').textContent.includes('Hidden'),false);assert.match(app.ids.get('standard-view').textContent,/SUCCEEDED/);
});

test('declarative success with unknown totals or counts shows completion without inventing a progress bar',()=>{
 for(const progress of [undefined,{completed:4},{completed:4,total:0},{completed:null,total:10}]){const app=fixture(),data=snapshot();data.nodes.a={status:'SUCCEEDED',progress};app.context.window.PlugIntellTemplateBridge.receiveSnapshot(data);const stages=app.ids.get('standard-view');assert.equal(stages.querySelector('progress'),null);assert.match(stages.textContent,/Completed/);assert.equal(stages.textContent.includes('100%'),false)}
});

test('declarative success preserves partial reported counts instead of forcing100percent',()=>{
 const app=fixture(),data=snapshot();data.nodes.a={status:'SUCCEEDED',progress:{completed:4,total:10,unit:'pages'}};app.context.window.PlugIntellTemplateBridge.receiveSnapshot(data);const stages=app.ids.get('standard-view');assert.equal(stages.querySelector('progress').value,'40');assert.match(stages.textContent,/Completed.*4 \/ 10 pages/);assert.equal(stages.textContent.includes('100%'),false);
});

test('rich declarative settings safely control appearance and visibility while counts and durations come from state',()=>{
 const app=fixture();for(const [id,attribute,value]of [['monitor-completed','data-pipeline-count','succeeded'],['monitor-version','data-template-setting','subtitle'],['water-view','data-pipeline-events-panel',''],['monitor-run','data-pipeline-run',''],['monitor-sequence','data-pipeline-sequence','']])app.ids.get(id).setAttribute(attribute,value);
 const data=snapshot();data.template={settings:{accent:'#123abc',compact:true,layout:'list',subtitle:'<img src=x>',showTimeline:false,timelineLimit:1}};data.nodes.a={status:'SUCCEEDED',durationMillis:42,progress:{completed:4,total:10}};data.events.push({type:'NODE_SUCCEEDED',message:'Last event'});app.context.window.PlugIntellTemplateBridge.receiveSnapshot(data);
 assert.equal(app.context.document.documentElement.style['--accent'],'#123abc');assert.equal(app.context.document.documentElement.dataset.templateDensity,'compact');assert.equal(app.context.document.documentElement.dataset.templateLayout,'list');assert.equal(app.ids.get('monitor-version').textContent,'<img src=x>');assert.equal(app.ids.get('monitor-version').querySelector('img'),null);assert.equal(app.ids.get('water-view').hidden,true);assert.equal(app.ids.get('monitor-completed').textContent,'1');assert.equal(app.ids.get('monitor-run').textContent,'run');assert.match(app.ids.get('standard-view').textContent,/42 ms/);assert.equal(app.ids.get('monitor-timeline').children.length,1);
});

test('rich declarative node inspector and delivery health use safe summaries and explicit topology',()=>{
 const app=fixture(),inspector=app.ids.get('monitor-node-details');inspector.removeAttribute('data-pipeline-node');inspector.setAttribute('data-pipeline-inspector','');app.ids.get('monitor-health').setAttribute('data-pipeline-health','');app.ids.get('water-svg').setAttribute('data-pipeline-connections','');
 const data=snapshot();data.descriptor.nodes.push({id:'b',label:'Write confirmed records',pluginId:'writer'});data.descriptor.edges=[{from:'a',to:'b'}];data.nodes.b={status:'WAITING'};data.nodes.a.outputSummary={type:'TextDocument',pages:6,private:{text:'not a summary'}};data.health=[{id:'<script>adapter',status:'UP',delivered:9,dropped:2,coalesced:1,failed:0}];app.context.window.PlugIntellTemplateBridge.receiveSnapshot(data);
 assert.match(inspector.textContent,/Output summary/);assert.match(inspector.textContent,/pages6/);assert.equal(inspector.textContent.includes('not a summary'),false);app.ids.get('standard-view').querySelectorAll('[role=button]')[1].click();assert.match(inspector.textContent,/Write confirmed records/);assert.match(inspector.textContent,/WAITING/);assert.match(app.ids.get('monitor-health').textContent,/<script>adapter/);assert.equal(app.ids.get('monitor-health').querySelector('script'),null);assert.match(app.ids.get('monitor-health').textContent,/dropped2/);assert.match(app.ids.get('water-svg').textContent,/→ Write confirmed records/);
});

test('keyboard stage selection survives a live snapshot update without losing its focus target',()=>{
 const app=fixture(),data=snapshot();app.ids.get('monitor-node-details').removeAttribute('data-pipeline-node');app.ids.get('monitor-node-details').setAttribute('data-pipeline-inspector','');app.context.window.PlugIntellTemplateBridge.receiveSnapshot(data);const before=app.ids.get('standard-view').querySelector('[role=button]');app.context.document.activeElement=before;let prevented=false;before.onkeydown({key:'Enter',preventDefault(){prevented=true}});assert.equal(prevented,true);app.context.window.PlugIntellTemplateBridge.receiveSnapshot({...data,sequence:4});assert.equal(app.ids.get('standard-view').querySelector('[role=button]').focused,true);
});
