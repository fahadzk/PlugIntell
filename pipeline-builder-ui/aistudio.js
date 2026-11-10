const $=selector=>document.querySelector(selector);
try{const theme=localStorage.getItem('plugintell.theme'),density=localStorage.getItem('plugintell.density');if(['system','light','dark'].includes(theme))document.documentElement.dataset.theme=theme;if(['comfortable','compact'].includes(density))document.documentElement.dataset.density=density}catch{}
let token=sessionStorage.getItem('plugintell.accessToken'),loginPending=null,records=[];
function signIn(){if(loginPending)return loginPending;loginPending=new Promise(resolve=>{window.resolveProjectLogin=resolve;$('#login-error').textContent='';$('#login-dialog').showModal()}).finally(()=>{loginPending=null;delete window.resolveProjectLogin});return loginPending}
$('#login-form').addEventListener('submit',async event=>{event.preventDefault();try{const response=await fetch('/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:$('#login-username').value,password:$('#login-password').value})}),data=await response.json();if(!response.ok)throw Error(data.error||'Sign in failed.');token=data.accessToken;sessionStorage.setItem('plugintell.accessToken',token);$('#login-password').value='';$('#login-dialog').close();window.resolveProjectLogin?.(token)}catch(error){$('#login-error').textContent=error.message}});
$('#login-dialog').addEventListener('cancel',()=>window.resolveProjectLogin?.(null));
async function request(path,options={}){const headers={...(options.headers||{})};if(options.body&&!headers['Content-Type'])headers['Content-Type']='application/json';if(token)headers.Authorization=`Bearer ${token}`;let response=await fetch(path,{...options,headers});if(response.status===401){token=null;sessionStorage.removeItem('plugintell.accessToken');token=await signIn();if(!token)throw Error('Authentication is required.');headers.Authorization=`Bearer ${token}`;response=await fetch(path,{...options,headers})}if(!response.ok){let message=await response.text();try{const body=JSON.parse(message);message=body.error||body.errors?.join('\n')||message}catch{}throw Error(message||`HTTP ${response.status}`)}return response}
async function api(path,options){const response=await request(path,options);return response.status===204?null:response.json()}

function renderRecommendation(result){
 for(const lane of ['ingestion','query']){
  const chain=result[lane];$('#'+lane+'-title').textContent=(lane==='ingestion'?'Ingestion':'Query')+(chain.complete?'':' - incomplete');
  const list=$('#'+lane);list.replaceChildren();
  for(const step of chain.steps){const item=document.createElement('li');item.textContent=step.missing?`${step.step}: ${step.missing}`:step.name;if(step.missing)item.className='error';list.append(item)}
 }
 $('#recommendation').hidden=false;
}
$('#suggest-form').addEventListener('submit',async event=>{
 event.preventDefault();$('#suggest').disabled=true;$('#recommendation').hidden=true;$('#error').textContent='';$('#status').textContent='Asking Laya to recommend plugins...';
 try{renderRecommendation(await api('/api/v1/aistudio/recommend',{method:'POST',body:JSON.stringify({prompt:$('#prompt').value})}));$('#status').textContent='Recommendation ready.'}
 catch(error){$('#status').textContent='';$('#error').textContent=error.message}
 finally{$('#suggest').disabled=false}
});
