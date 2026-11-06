// Focused browser-independent behavior checks using the actual application scripts.
// This small DOM harness exercises state/events; it does not verify rendering or replace browser QA.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const crypto=require('node:crypto');

class Element {
  constructor(tag='div'){this.tagName=tag.toUpperCase();this.children=[];this.dataset={};this.attributes={};this.style={};this.className='';this._value='';this._text='';this.hidden=false;this.checked=false;this.open=false;this.clientWidth=700;this.clientHeight=200;this.scrollHeight=300;this.scrollTop=0;this.namespaceURI='http://www.w3.org/2000/svg';this.classList={add:(...names)=>this.classes(...names.map(name=>[name,true])),remove:(...names)=>this.classes(...names.map(name=>[name,false])),contains:name=>this.className.split(/\s+/).includes(name),toggle:(name,force)=>{const enabled=force??!this.classList.contains(name);this.classes([name,enabled]);return enabled}}}
  classes(...changes){const names=new Set(this.className.split(/\s+/).filter(Boolean));for(const [name,enabled]of changes)enabled?names.add(name):names.delete(name);this.className=[...names].join(' ')}
  set textContent(value){this._text=String(value);this.children=[]}
  get textContent(){return this._text+this.children.map(child=>child.textContent).join('')}
  set value(value){this._value=String(value)}
  get value(){return this._value||(this.tagName==='SELECT'?this.children[0]?.value||'':'')}
  get options(){return this.children}
  get firstElementChild(){return this.children[0]}
  append(...children){for(let child of children){if(typeof child==='string'){const text=new Element('#text');text.textContent=child;child=text}child.parentElement=this;this.children.push(child)}}
  replaceChildren(...children){this.children=[];this._text='';this.append(...children)}
  before(child){const parent=this.parentElement;if(parent){child.parentElement=parent;parent.children.splice(parent.children.indexOf(this),0,child)}}
  remove(){if(this.parentElement)this.parentElement.children=this.parentElement.children.filter(item=>item!==this)}
  setAttribute(name,value){this.attributes[name]=String(value);if(name==='class')this.className=String(value)}
  removeAttribute(name){delete this.attributes[name]}
  getAttribute(name){return this.attributes[name]}
  addEventListener(name,fn){this['on'+name]=fn}
  setCustomValidity(value){this.validationMessage=value}
  focus(){this.focused=true}
  scrollIntoView(){this.scrolledIntoView=true}
  showModal(){this.open=true}
  close(){this.open=false}
  click(){this.clicked=(this.clicked||0)+1;this.onclick?.({target:this,preventDefault(){},stopPropagation(){}})}
  getBoundingClientRect(){return {left:0,top:0,right:246,bottom:136,width:246,height:136}}
  matches(selector){if(selector==='dialog[open]')return this.tagName==='DIALOG'&&this.open;if(selector.startsWith('.'))return this.classList.contains(selector.slice(1));if(selector.startsWith('#'))return this.id===selector.slice(1);const attribute=selector.match(/^\[([^=\]]+)(?:=["']?([^"'\]]+)["']?)?\]$/);if(attribute){let value=this.attributes[attribute[1]];if(attribute[1].startsWith('data-'))value=this.dataset[attribute[1].slice(5).replace(/-([a-z])/g,(_,letter)=>letter.toUpperCase())];return attribute[2]===undefined?value!==undefined:value===attribute[2]}return this.tagName===selector.toUpperCase()}
  closest(selector){let element=this;while(element){if(element.matches(selector))return element;element=element.parentElement}return null}
  querySelectorAll(selector){const result=[];const visit=element=>{for(const child of element.children){if(child.matches(selector))result.push(child);visit(child)}};visit(this);return result}
  querySelector(selector){return this.querySelectorAll(selector)[0]||null}
}
class Option extends Element {constructor(text,value){super('option');this.textContent=text;this.value=value}}
function load(file='studio.js',htmlFile='studio.html'){
  const html=fs.readFileSync(path.join(__dirname,htmlFile),'utf8'),root=new Element('body'),ids=new Map();
  for(const match of html.matchAll(/<([a-z][\w-]*)\b([^>]*\bid="([^"]+)"[^>]*)>/g)){const element=new Element(match[1]);element.id=match[3];const className=match[2].match(/class="([^"]+)"/);if(className)element.className=className[1];const value=match[2].match(/value="([^"]*)"/);if(value)element.value=value[1];element.checked=/\bchecked\b/.test(match[2]);ids.set(element.id,element);root.append(element)}
  const shell=new Element('main');shell.className='workbench-shell';root.append(shell);
  const document={documentElement:new Element('html'),visibilityState:'visible',createElement:tag=>new Element(tag),createElementNS:(_,tag)=>new Element(tag),createTextNode:text=>{const element=new Element('#text');element.textContent=text;return element},querySelector:selector=>selector.startsWith('#')?ids.get(selector.slice(1))||null:root.querySelector(selector),querySelectorAll:selector=>root.querySelectorAll(selector),addEventListener(){}};
  const requests=[],storage=new Map(),window={innerWidth:1600,addEventListener(){}};
  const context={document,window,console,Option,crypto,CSS:{escape:value=>value},sessionStorage:{getItem:()=>null,setItem(){},removeItem(){}},localStorage:{getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value)},location:{reload(){}},requestAnimationFrame:()=>0,setInterval:()=>0,setTimeout:()=>0,FormData:class{},Blob,URL,URLSearchParams,confirm:()=>true,navigator:{clipboard:{writeText:async()=>{}}},fetch:(url,options={})=>{requests.push({url,options});return new Promise(()=>{})}};
  vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(__dirname,file),'utf8'),context,{filename:file});return{context,ids,requests,storage,evaluate:source=>vm.runInContext(source,context)};
}
const normalized=value=>JSON.parse(JSON.stringify(value));

test('workbench starts with explicit-save state, system appearance and comfortable controls',()=>{
  const app=load();assert.equal(app.context.document.documentElement.dataset.theme,'system');assert.equal(app.context.document.documentElement.dataset.density,'comfortable');assert.equal(app.ids.get('draft-state').textContent,'New project');assert.equal(app.ids.get('run-pipeline-button').disabled,true);
});

test('a lane Run action prepares published inputs and never executes stale input automatically',()=>{
  const app=load(),selection=JSON.stringify({id:'project',version:3,type:'QUERY_PROCESSING'}),run=app.ids.get('run-pipeline');run.append(new Option('Published query',selection));run.value=selection;app.ids.get('run-query').value='A question left from an earlier run';app.evaluate("current={id:'project',publishedVersion:3};");const count=app.requests.length;
  assert.equal(app.context.prepareWorkflowRun('QUERY_PROCESSING'),true);assert.equal(app.requests.length,count);assert.equal(app.ids.get('run-panel').open,true);assert.equal(app.ids.get('run-version').textContent,'Published v3');assert.equal(app.ids.get('run-query').focused,true);assert.equal(app.ids.get('run-pipeline-button').clicked,undefined);
});

test('flat profile overrides can be reset without changing the saved profile',()=>{
  const app=load(),node={workflow:'QUERY_PROCESSING',id:'node',plugin:{id:'provider',configurationSchema:{properties:{endpoint:{type:'string'}}}},config:{connectionRef:'profile'}},base={endpoint:'http://inherited.invalid'};
  const form=app.context.createConfigForm(node,base),input=form.querySelector('input'),badge=form.querySelector('.field-source'),reset=form.querySelector('.field-reset');assert.equal(input.value,base.endpoint);assert.equal(badge.textContent,'Inherited');assert.equal(reset.hidden,true);
  input.value='http://override.invalid';input.oninput();assert.equal(node.config.endpoint,input.value);assert.equal(badge.textContent,'Node override');assert.equal(reset.hidden,false);reset.onclick();assert.equal(node.config.endpoint,undefined);assert.equal(node.config.connectionRef,'profile');assert.equal(base.endpoint,'http://inherited.invalid');
});

test('nested profile fields use whole-object overrides and reset the entire object',()=>{
  const app=load(),node={workflow:'QUERY_PROCESSING',id:'node',plugin:{id:'provider',configurationSchema:{properties:{provider:{type:'object',properties:{host:{type:'string'},port:{type:'integer',default:42}}}}}},config:{connectionRef:'profile'}},base={provider:{host:'inherited',port:123}};
  const form=app.context.createConfigForm(node,base),inputs=form.querySelectorAll('input');inputs[0].value='custom';inputs[0].oninput();assert.deepEqual(normalized(node.config.provider),{host:'custom',port:123});assert.deepEqual(base,{provider:{host:'inherited',port:123}});assert.equal(form.querySelector('.field-source').textContent,'Object override');form.querySelector('.field-reset').onclick();assert.equal(node.config.provider,undefined);
  node.config.provider={host:'only-local'};const overridden=app.context.createConfigForm(node,base);assert.equal(overridden.querySelectorAll('input')[1].value,'42','missing child falls back to schema default, not live profile');
});

test('invalid integer input is flagged instead of silently becoming a different number',()=>{
  const app=load(),node={workflow:'QUERY_PROCESSING',id:'node',plugin:{configurationSchema:{properties:{count:{type:'integer'}}}},config:{count:2}},form=app.context.createConfigForm(node),input=form.querySelector('input');input.value='2.7';input.oninput();assert.equal(input.getAttribute('aria-invalid'),'true');assert.equal(node.config.count,2);assert.ok(input.validationMessage);input.value='7';input.oninput();assert.equal(node.config.count,7);assert.equal(input.getAttribute('aria-invalid'),undefined);assert.equal(input.validationMessage,'');
});

test('project serialization and connection checks keep ingestion and query separate',()=>{
  const app=load();app.evaluate(`nodes=[{id:'i1',workflow:'DOCUMENT_INGESTION',plugin:{id:'source',version:'1',outputs:[{dataType:'Text'}]},config:{}},{id:'i2',workflow:'DOCUMENT_INGESTION',plugin:{id:'split',version:'1',inputs:[{dataType:'Text'}]},config:{}},{id:'q1',workflow:'QUERY_PROCESSING',plugin:{id:'query',version:'1',inputs:[{dataType:'Text'}]},config:{}}];edges=[{from:'i1',to:'i2'}];`);
  const definition=normalized(app.context.payload());assert.equal(definition.type,'RAG_PROJECT');assert.equal(definition.workflows.DOCUMENT_INGESTION.nodes.length,2);assert.equal(definition.workflows.QUERY_PROCESSING.nodes.length,1);assert.equal(definition.workflows.QUERY_PROCESSING.edges.length,0);assert.equal(app.evaluate('canConnect(nodes[0],nodes[2])'),false);
});

test('build progress preserves manual log position unless Follow is enabled',()=>{
  const app=load('project-build.js','project-build.html'),log=app.ids.get('build-log'),follow=app.ids.get('follow-build-log');follow.checked=false;log.scrollTop=17;log.scrollHeight=1000;app.context.showSnapshot({id:'job',status:'BUILDING',projectName:'Example',outputFormat:'jar',destination:'C:/Example',progress:35,logs:['Compiling']});assert.equal(log.scrollTop,17);follow.checked=true;app.context.showSnapshot({id:'job',status:'SUCCEEDED',projectName:'Example',outputFormat:'jar',destination:'C:/Example',progress:100,logs:['BUILD SUCCESS'],artifactName:'example.jar'});assert.equal(log.scrollTop,1000);assert.equal(app.ids.get('download-artifact').hidden,true);assert.equal(app.ids.get('download-bundle').hidden,false);
});

test('adding a node keeps its canvas card compact and opens its full settings in the inspector',()=>{
  const app=load(),plugin={id:'source',name:'Source fixture',version:'1.0.0',category:'DOCUMENT_PARSER',capabilities:[],workflows:['DOCUMENT_INGESTION'],inputs:[{dataType:'File'}],outputs:[{dataType:'Text'}],configurationSchema:{properties:{path:{type:'string',default:'sample.txt'}}}};
  app.context.addPlugin(plugin,'DOCUMENT_INGESTION');const canvas=app.ids.get('canvas-DOCUMENT_INGESTION'),card=canvas.querySelector('.graph-node');assert.ok(card);assert.equal(card.querySelectorAll('input').length,0);assert.equal(card.querySelectorAll('button').length,3);assert.ok(app.ids.get('inspector-content').querySelectorAll('input').length>=5);assert.equal(app.ids.get('count-DOCUMENT_INGESTION').textContent,'1');assert.equal(app.evaluate('dirty'),true);
});

test('canvas port buttons retain typed connection behavior after moving forms into the inspector',()=>{
  const app=load(),first={id:'source',name:'Source fixture',version:'1',category:'DOCUMENT_PARSER',capabilities:[],workflows:['DOCUMENT_INGESTION'],inputs:[{dataType:'File'}],outputs:[{dataType:'Text'}],configurationSchema:{properties:{}}},second={id:'split',name:'Splitter fixture',version:'1',category:'CHUNKER',capabilities:[],workflows:['DOCUMENT_INGESTION'],inputs:[{dataType:'Text'}],outputs:[{dataType:'Chunks'}],configurationSchema:{properties:{}}};
  app.context.addPlugin(first,'DOCUMENT_INGESTION');app.context.addPlugin(second,'DOCUMENT_INGESTION');const canvas=app.ids.get('canvas-DOCUMENT_INGESTION');canvas.querySelectorAll('.graph-node')[0].querySelector('.output-port').onclick();canvas.querySelectorAll('.graph-node')[1].querySelector('.input-port').onclick();assert.equal(app.context.payload().workflows.DOCUMENT_INGESTION.edges.length,1);assert.equal(app.evaluate('connectingNodeId'),null);
});

test('usage segments and search combine without hiding the other workflow canvas',()=>{
  const app=load();app.evaluate(`plugins=[{id:'source',name:'Source',description:'Read documents',category:'DOCUMENT_PARSER',version:'1',workflows:['DOCUMENT_INGESTION'],inputs:[{dataType:'File'}],outputs:[{dataType:'Text'}]},{id:'query',name:'Retriever',description:'Search indexed documents',category:'RETRIEVER',version:'1',workflows:['QUERY_PROCESSING'],inputs:[{dataType:'SearchRequest'}],outputs:[{dataType:'Result'}]}];`);
  const filter=app.ids.get('plugin-usage'),button=new Element('button');button.dataset.usage='QUERY_PROCESSING';filter.append(button);filter.onclick({target:button});assert.equal(app.ids.get('plugin-list').querySelectorAll('article').length,1);assert.ok(app.ids.get('plugin-list').textContent.includes('Retriever'));app.ids.get('plugin-filter').value='no match';app.ids.get('plugin-filter').oninput();assert.ok(app.ids.get('plugin-list').textContent.includes('No plugins match'));assert.equal(app.ids.get('canvas-DOCUMENT_INGESTION').hidden,false);assert.equal(app.ids.get('canvas-QUERY_PROCESSING').hidden,false);
});

test('view preferences persist without changing the project definition',()=>{
  const app=load(),before=JSON.stringify(app.context.payload());app.ids.get('theme-choice').value='dark';app.ids.get('theme-choice').onchange();app.ids.get('density-choice').value='compact';app.ids.get('density-choice').onchange();assert.equal(app.context.document.documentElement.dataset.theme,'dark');assert.equal(app.storage.get('plugintell.density'),'compact');assert.equal(JSON.stringify(app.context.payload()),before);
});
