import {spawn,execFileSync} from 'node:child_process';
import {mkdtemp,rm} from 'node:fs/promises';
import {readFileSync,writeFileSync} from 'node:fs';
import {randomBytes} from 'node:crypto';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import net from 'node:net';
import {setTimeout as delay} from 'node:timers/promises';
export const password='household-test-password-only';
const binary=resolve(process.env.FIELD_NOTES_BINARY||'target/debug/field-notes-server');
async function port(){const s=net.createServer();await new Promise(r=>s.listen(0,'127.0.0.1',r));const p=s.address().port;await new Promise(r=>s.close(r));return p;}
export async function fixture({enrollment=false,downloads=process.env.FIELD_NOTES_DOWNLOADS}={}){
 const root=await mkdtemp(join(tmpdir(),'field-notes-'));const data=join(root,'data'),key=join(root,'key');
 const adminPort=await port(),devicePort=await port();const admin=`http://127.0.0.1:${adminPort}`,device=`http://127.0.0.1:${devicePort}`;
 execFileSync(binary,['init','--data',data,'--key-file',key,'--name','owner'],{input:password,stdio:['pipe','pipe','pipe']});
 const peerStatus=join(root,'peer-status.json'),networkFile=join(root,'network.json');
 let extra=[];
 if(enrollment){
  const ca=join(root,'tls');execFileSync('python3',['scripts/tls.py','init','--directory',ca,'--name','192.168.1.10','--name','10.82.0.1']);
  writeFileSync(networkFile,JSON.stringify({bootstrap_url:'https://192.168.1.10:9443/api/enrollment',endpoint:'home.example:49272',server_key:randomBytes(32).toString('base64'),api:'https://10.82.0.1:8443',ca_pem:readFileSync(join(ca,'ca.crt'),'utf8'),overlay:'10.82.0.0/24'}),{mode:0o600});
  extra=['--network-config',networkFile,'--peer-status',peerStatus];
 }
 let child,output='';
 if(downloads)extra.push('--downloads',downloads);
 async function start(dir=data){child=spawn(binary,['serve','--data',dir,'--key-file',key,'--admin-bind',`127.0.0.1:${adminPort}`,'--device-bind',`127.0.0.1:${devicePort}`,'--origin',admin,'--dev',...extra],{stdio:['ignore','pipe','pipe']});child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b);for(let n=0;n<150;n++){try{if((await fetch(admin+'/health')).ok)return;}catch{}if(child.exitCode!==null)throw Error(output);await delay(30);}throw Error('Server did not become ready: '+output);}
 async function stop(){if(child&&child.exitCode===null){child.kill('SIGTERM');await new Promise(r=>child.once('exit',r));}}
 async function request(path,{body,token,origin,base=admin,headers={}}={}){const response=await fetch(base+path,{method:body!==undefined?'POST':'GET',headers:{...headers,...(body!==undefined?{'Content-Type':'application/json'}:{}),...(token?{Authorization:'Bearer '+token}:{}),...(origin?{Origin:origin}:{})},body:body!==undefined?JSON.stringify(body):undefined});const data=response.headers.get('content-type')?.includes('json')?await response.json():Buffer.from(await response.arrayBuffer());return {status:response.status,data,headers:response.headers};}
 async function login(name='owner',pass=password){const r=await request('/api/login',{base:enrollment?admin:device,body:{name,password:pass,device:'Integration test'}});if(r.status!==200)throw Error(`Login ${r.status}: ${JSON.stringify(r.data)}`);return enrollment?r.headers.get('set-cookie').match(/^field_session=([a-f0-9]{64});/)[1]:r.data.token;}
 await start();return {root,data,key,admin,device,request,login,start,stop,enrollment,peerStatus,networkFile,restore(file,dest){execFileSync(binary,['restore','--data',dest,'--key-file',key,'--file',file]);},async close(){await stop();await rm(root,{recursive:true,force:true});}};
}
