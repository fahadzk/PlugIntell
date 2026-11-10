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
