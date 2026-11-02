import {test} from 'node:test';
import assert from 'node:assert/strict';
import {connect} from 'node:http2';
import {execFileSync,spawn} from 'node:child_process';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {tmpdir} from 'node:os';
import net from 'node:net';
import {setTimeout as delay} from 'node:timers/promises';

async function port(){const server=net.createServer();await new Promise(r=>server.listen(0,'127.0.0.1',r));const value=server.address().port;await new Promise(r=>server.close(r));return value;}
test('production TLS/HTTP2 preserves browser authentication when cookies are split across headers',async()=>{
 const root=await mkdtemp(join(tmpdir(),'field-notes-h2-auth-')),data=join(root,'data'),key=join(root,'master.key'),tls=join(root,'tls');
 const binary=resolve(process.env.FIELD_NOTES_BINARY||'target/debug/field-notes-server'),password='synthetic-http2-password!';let child,client;
 try{
  execFileSync(binary,['init','--data',data,'--key-file',key,'--name','admin'],{input:password});
  execFileSync('python3',['scripts/tls.py','init','--directory',tls,'--name','127.0.0.1']);
  const admin=await port(),device=await port(),origin=`https://127.0.0.1:${admin}`,ca=await readFile(join(tls,'ca.crt'));
  child=spawn(binary,['serve','--data',data,'--key-file',key,'--admin-bind',`127.0.0.1:${admin}`,'--admin-allow','127.0.0.1/32','--device-bind',`127.0.0.1:${device}`,'--origin',origin,'--tls-cert',join(tls,'server.crt'),'--tls-key',join(tls,'server.key')],{stdio:['ignore','ignore','pipe']});
  let errors='';child.stderr.on('data',part=>errors+=part);
  for(let i=0;i<100;i++){
   if(child.exitCode!==null)throw Error(errors);
   try{
    const attempt=connect(origin,{ca,minVersion:'TLSv1.3'});
    await new Promise((resolve,reject)=>{attempt.once('connect',resolve);attempt.once('error',reject)});client=attempt;break;
   }catch{await delay(25)}
  }
  assert(client,'TLS server became ready');assert.equal(client.socket.alpnProtocol,'h2');assert.equal(client.socket.getProtocol(),'TLSv1.3');
  async function request(path,{body,cookie,csrf}={}){
   return await new Promise((resolve,reject)=>{
    const headers={':method':body===undefined?'GET':'POST',':path':path,...(cookie?{cookie}:{}),...(body!==undefined?{origin,'content-type':'application/json'}:{}),...(csrf?{'x-field-csrf':csrf}:{})};
    const stream=client.request(headers);let received,bytes='';
    stream.on('response',headers=>received=headers);stream.setEncoding('utf8');stream.on('data',part=>bytes+=part);stream.on('error',reject);stream.on('end',()=>resolve({status:received[':status'],headers:received,data:JSON.parse(bytes)}));stream.end(body===undefined?undefined:JSON.stringify(body));
   });
  }
  const login=await request('/api/login',{body:{name:'admin',password,device:'TLS HTTP2 browser fixture'}});
  assert.equal(login.status,200);assert.match(login.headers['set-cookie'][0],/; Secure/);
  const cookie=login.headers['set-cookie'][0].split(';')[0];
  assert.equal((await request('/api/session',{cookie:'other_app=unrelated; '+cookie})).status,200,'joined cookie control');
  const split=['other_app=unrelated',cookie];
  const session=await request('/api/session',{cookie:split});
  assert.equal(session.status,200,'valid session in a later HTTP2 Cookie header');assert.equal(session.data.actor.user_id,login.data.actor.user_id);
  assert.equal((await request('/api/snapshot',{cookie:split})).status,200);
  assert.equal((await request('/api/people',{cookie:split})).status,200);
  assert.equal((await request('/api/reauth',{cookie:split,csrf:login.data.csrf,body:{password}})).status,200);
  assert.equal((await request('/api/session',{cookie:[cookie,'field_session='+'0'.repeat(64)]})).status,401,'conflicting session cookies are rejected');
  assert.equal((await request('/api/session',{cookie:[cookie,cookie]})).status,200,'identical repeated cookies are unambiguous');
  const plaintext=await new Promise((resolve,reject)=>{
   const socket=net.connect(admin,'127.0.0.1');let bytes='';socket.setEncoding('utf8');socket.on('connect',()=>socket.write('GET / HTTP/1.1\r\nHost: untrusted.invalid\r\n\r\n'));socket.on('data',part=>bytes+=part);socket.on('end',()=>resolve(bytes));socket.on('error',reject);
  });
  assert.match(plaintext,/^HTTP\/1\.1 400 Bad Request/);assert(plaintext.includes('HTTPS is required on this port. Open '+origin+'/'));assert(!plaintext.includes('untrusted.invalid'));assert(!plaintext.includes('Set-Cookie'));
 }finally{
  client?.destroy();if(child&&child.exitCode===null){child.kill('SIGTERM');await new Promise(r=>child.once('exit',r));}await rm(root,{recursive:true,force:true});
 }
});
