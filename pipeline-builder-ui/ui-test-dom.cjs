// Minimal DOM for behavioral checks; not a rendering or accessibility engine.
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const crypto=require('node:crypto');
class Element {
  constructor(tag='div'){this.tagName=tag.toUpperCase();this.children=[];this.dataset={};this.attributes={};this.style={setProperty(name,value){this[name]=String(value)},removeProperty(name){delete this[name]}};this.className='';this._value='';this._text='';this.hidden=false;this.checked=false;this.open=false;this.clientWidth=700;this.clientHeight=200;this.scrollHeight=300;this.scrollTop=0;this.namespaceURI='http://www.w3.org/2000/svg';this.classList={add:(...names)=>this.classes(...names.map(name=>[name,true])),remove:(...names)=>this.classes(...names.map(name=>[name,false])),contains:name=>this.className.split(/\s+/).includes(name),toggle:(name,force)=>{const enabled=force??!this.classList.contains(name);this.classes([name,enabled]);return enabled}}}
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
  close(){this.open=false;this.onclose?.({target:this})}
  click(){this.clicked=(this.clicked||0)+1;this.onclick?.({target:this,preventDefault(){},stopPropagation(){}})}
  getBoundingClientRect(){return {left:0,top:0,right:246,bottom:136,width:246,height:136}}
  matches(selector){if(selector==='dialog[open]')return this.tagName==='DIALOG'&&this.open;if(selector.startsWith('.'))return this.classList.contains(selector.slice(1));if(selector.startsWith('#'))return this.id===selector.slice(1);const attribute=selector.match(/^\[([^=\]]+)(?:=["']?([^"'\]]+)["']?)?\]$/);if(attribute){let value=this.attributes[attribute[1]];if(attribute[1].startsWith('data-'))value=this.dataset[attribute[1].slice(5).replace(/-([a-z])/g,(_,letter)=>letter.toUpperCase())]??value;return attribute[2]===undefined?value!==undefined:value===attribute[2]}return this.tagName===selector.toUpperCase()}
  closest(selector){let element=this;while(element){if(element.matches(selector))return element;element=element.parentElement}return null}
  querySelectorAll(selector){const result=[];const visit=element=>{for(const child of element.children){if(child.matches(selector))result.push(child);visit(child)}};visit(this);return result}
  querySelector(selector){return this.querySelectorAll(selector)[0]||null}
}
class Option extends Element {constructor(text,value){super('option');this.textContent=text;this.value=value}}
function load(file='studio.js',htmlFile='studio.html',overrides={}){
  const html=fs.readFileSync(path.join(__dirname,htmlFile),'utf8'),root=new Element('body'),ids=new Map();
  for(const match of html.matchAll(/<([a-z][\w-]*)\b([^>]*\bid="([^"]+)"[^>]*)>/g)){const element=new Element(match[1]);element.id=match[3];const className=match[2].match(/class="([^"]+)"/);if(className)element.className=className[1];const value=match[2].match(/value="([^"]*)"/);if(value)element.value=value[1];element.checked=/\bchecked\b/.test(match[2]);ids.set(element.id,element);root.append(element)}
  const shell=new Element('main');shell.className='workbench-shell';root.append(shell);
  const document={documentElement:new Element('html'),visibilityState:'visible',createElement:tag=>new Element(tag),createElementNS:(_,tag)=>new Element(tag),createTextNode:text=>{const element=new Element('#text');element.textContent=text;return element},querySelector:selector=>selector.startsWith('#')?ids.get(selector.slice(1))||null:root.querySelector(selector),querySelectorAll:selector=>root.querySelectorAll(selector),addEventListener(){}};
  const requests=[],storage=new Map(),frames=[],window={innerWidth:1600,addEventListener(name,callback){this['on'+name]=callback}};
  const context={document,window,console,Option,crypto,CSS:{escape:value=>value},sessionStorage:{getItem:()=>null,setItem(){},removeItem(){}},localStorage:{getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value)},location:{reload(){},search:'',origin:'http://localhost:8189'},AbortController,TextDecoder,TextEncoder,ReadableStream,requestAnimationFrame:callback=>{frames.push(callback);return frames.length},setInterval:()=>0,setTimeout:()=>0,clearTimeout(){},FormData:class{},Blob,URL,URLSearchParams,confirm:()=>true,navigator:{clipboard:{writeText:async()=>{}}},fetch:(url,options={})=>{requests.push({url,options});return new Promise(()=>{})}};
  Object.assign(context,overrides);vm.createContext(context);if(file==='studio.js')vm.runInContext(fs.readFileSync(path.join(__dirname,'ui-templates.js'),'utf8'),context,{filename:'ui-templates.js'});vm.runInContext(fs.readFileSync(path.join(__dirname,file),'utf8'),context,{filename:file});return{context,ids,requests,storage,flushFrames(){while(frames.length)frames.shift()()},evaluate:source=>vm.runInContext(source,context)};
}
const normalized=value=>JSON.parse(JSON.stringify(value));

module.exports={load,Element,Option,normalized};

