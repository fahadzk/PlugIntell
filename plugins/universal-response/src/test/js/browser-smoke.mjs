// Run against an already started demo and a dedicated headless Chromium/Edge CDP endpoint.
import assert from 'node:assert/strict';
import {writeFile,mkdir} from 'node:fs/promises';
const [debug='http://127.0.0.1:19225',viewer='http://127.0.0.1:18765']=process.argv.slice(2);
const targets=await fetch(debug+'/json/list').then(r=>r.json());const target=targets.find(t=>t.type==='page');assert.ok(target,'A browser page target is required');
const socket=new WebSocket(target.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
let sequence=0;const pending=new Map(),errors=[],requests=[];
socket.addEventListener('message',event=>{const message=JSON.parse(event.data);if(message.id){const waiter=pending.get(message.id);if(waiter){clearTimeout(waiter.timer);pending.delete(message.id);message.error?waiter.reject(Error(JSON.stringify(message.error))):waiter.resolve(message.result);}}else if(message.method==='Runtime.exceptionThrown')errors.push(message.params.exceptionDetails.text);else if(message.method==='Network.requestWillBeSent')requests.push(message.params.request.url);});
function send(method,params={}){return new Promise((resolve,reject)=>{const id=++sequence,timer=setTimeout(()=>reject(Error('CDP timeout: '+method)),15000);pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params}));});}
async function evaluate(expression){const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(result.exceptionDetails)throw Error(result.exceptionDetails.text);return result.result.value;}
async function waitFor(expression){for(let i=0;i<80;i++){if(await evaluate(expression))return;await new Promise(r=>setTimeout(r,150));}throw Error('Page condition timed out: '+expression);}
try{
 await send('Page.enable');await send('Runtime.enable');await send('Network.enable');await send('Emulation.setDeviceMetricsOverride',{width:1440,height:1050,deviceScaleFactor:1,mobile:false});await send('Page.navigate',{url:viewer+'/'});
 await waitFor('document.querySelectorAll(".block").length >= 13');
 assert.match(await evaluate('document.querySelector("h1").textContent'),/Rawalpindi/);
 assert.ok(await evaluate('document.querySelectorAll(".tok-keyword").length>0'));
 assert.ok(await evaluate('document.querySelectorAll(".graph svg marker").length>=2'));
 assert.ok(await evaluate('document.querySelectorAll(".sequence svg").length===1'));
 assert.ok(await evaluate('document.querySelectorAll(".chart svg rect").length>0'));
 await evaluate(`(()=>{const chart=document.querySelector('.chart');chart.querySelector('select').value='line';chart.querySelector('select').dispatchEvent(new Event('change'));})()`);
 const lines=await evaluate('document.querySelectorAll(".chart svg polyline").length');assert.ok(lines>0);await evaluate(`document.querySelector('.chart .legend button').click()`);assert.equal(await evaluate('document.querySelectorAll(".chart svg polyline").length'),lines-1);await evaluate(`document.querySelector('.chart .legend button').click()`);
 const beforeDrag=await evaluate(`document.querySelector('.graph-node').getAttribute('transform')`);const point=await evaluate(`(()=>{const n=document.querySelector('.graph-node');n.scrollIntoView({block:'center'});const r=n.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2};})()`);
 await send('Input.dispatchMouseEvent',{type:'mousePressed',x:point.x,y:point.y,button:'left',buttons:1,clickCount:1});await send('Input.dispatchMouseEvent',{type:'mouseMoved',x:point.x+40,y:point.y+20,button:'left',buttons:1});await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:point.x+40,y:point.y+20,button:'left',buttons:0});assert.notEqual(await evaluate(`document.querySelector('.graph-node').getAttribute('transform')`),beforeDrag);
 assert.equal(await evaluate('typeof window.__pwned'),'undefined');
 assert.ok(await evaluate('Array.from(document.querySelectorAll("iframe")).every(f=>f.getAttribute("sandbox")===""&&!f.srcdoc.includes("<script")&&!f.srcdoc.includes("onerror"))'));
 await evaluate(`(()=>{const card=Array.from(document.querySelectorAll('.block')).find(b=>b.querySelector('.block-header>span')?.textContent==='table');const select=card.querySelector('select');select.value='json';select.dispatchEvent(new Event('change'));return true;})()`);
 assert.ok(await evaluate(`Array.from(document.querySelectorAll('.block')).find(b=>b.querySelector('.block-header>span')?.textContent==='table').querySelector('pre')!==null`));
 await evaluate(`(()=>{const card=Array.from(document.querySelectorAll('.block')).find(b=>b.querySelector('.block-header>span')?.textContent==='table');const select=card.querySelector('select');select.value='table';select.dispatchEvent(new Event('change'));const input=card.querySelector('input[type=search]');input.value='Feb';input.dispatchEvent(new Event('input'));})()`);
 assert.equal(await evaluate(`Array.from(document.querySelectorAll('.block')).find(b=>b.querySelector('.block-header>span')?.textContent==='table').querySelectorAll('tbody tr').length`),1);
 await evaluate(`(()=>{const fields=document.querySelectorAll('.widget-fields input[type=number]');fields[0].value='2';fields[1].value='3';Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Calculate total').click();})()`);
 assert.equal(await evaluate('document.querySelector(".widget-output").textContent'),'5');
 await evaluate(`Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Count confirmations').click()`);assert.equal(await evaluate('document.querySelectorAll(".widget-output")[1].textContent'),'1');
 await evaluate(`(()=>{const checkbox=document.querySelector('.widget-fields input[type=checkbox]');checkbox.checked=false;Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Count confirmations').click();})()`);assert.equal(await evaluate('document.querySelectorAll(".widget-output")[1].textContent'),'0');
 const csv=await evaluate(`(async()=>{const link=Array.from(document.querySelectorAll('#artifact-list a')).find(a=>a.download.endsWith('.csv'));const response=await fetch(link.href);return {status:response.status,text:await response.text(),disposition:response.headers.get('Content-Disposition')};})()`);assert.equal(csv.status,200);assert.match(csv.text,/revenue/);assert.match(csv.disposition,/attachment/);
 const beforeTheme=await evaluate('document.body.classList.contains("dark")');await evaluate(`document.querySelector('#theme').click()`);assert.notEqual(await evaluate('document.body.classList.contains("dark")'),beforeTheme);if(beforeTheme)await evaluate(`document.querySelector('#theme').click()`);
 await evaluate('window.scrollTo(0,0)');await mkdir('plugins/universal-response/target',{recursive:true});let shot=await send('Page.captureScreenshot',{format:'png'});await writeFile('plugins/universal-response/target/response-ui-desktop.png',Buffer.from(shot.data,'base64'));
 await send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await new Promise(r=>setTimeout(r,250));shot=await send('Page.captureScreenshot',{format:'png'});await writeFile('plugins/universal-response/target/response-ui-mobile.png',Buffer.from(shot.data,'base64'));
 assert.ok(await evaluate('document.documentElement.scrollWidth <= window.innerWidth+1'),'Mobile layout must not overflow the viewport');assert.deepEqual(errors,[]);assert.ok(requests.every(url=>url.startsWith(viewer)||url==='about:srcdoc'),'No external media or CDN requests are permitted by default');
 console.log('Browser smoke passed: blocks, Markdown/code, table/JSON switching, filtering, charts, graphs, diagrams, widgets, CSV download, themes, responsive layout and script-disabled previews.');
}finally{socket.close();}
