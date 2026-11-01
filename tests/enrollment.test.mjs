import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes,createHash} from 'node:crypto';
import {readFile,writeFile,stat} from 'node:fs/promises';
import {DatabaseSync} from 'node:sqlite';
import {join} from 'node:path';
import {fixture,password} from './support.mjs';

async function acknowledge(f){
 const raw=await readFile(join(f.data,'peers.json')),manifest=JSON.parse(raw);
 await writeFile(f.peerStatus,JSON.stringify({version:1,digest:createHash('sha256').update(raw).digest('hex'),applied_at:Math.floor(Date.now()/1000),peers:manifest.peers.map(({preshared_key,...p})=>p),error:null}),{mode:0o600});
}
async function pendingPhone(f,owner,publicKey=randomBytes(32).toString('base64')){
 const actor=(await f.request('/api/snapshot',{token:owner})).data.actor;
 const invite=await f.request('/api/enrollment/invitations',{token:owner,body:{user_id:actor.user_id}});assert.equal(invite.status,200);
 const payload=JSON.parse(Buffer.from(invite.data.code.split('#')[1],'base64url'));
 const body={token:payload.token,public_key:publicKey,claim_secret:randomBytes(32).toString('hex'),name:'Retryable phone'};
 const claim=await f.request('/api/enrollment/claim',{body});assert.equal(claim.status,200);
 return {...claim.data,body};
}

test('canceling an uncertain claim uses saved proof without allocating or rebinding a peer',async()=>{
 const f=await fixture({enrollment:true});
 try{
  const owner=await f.login();await f.request('/api/reauth',{token:owner,body:{password}});
  const user=(await f.request('/api/snapshot',{token:owner})).data.actor.user_id;
  const invitation=await f.request('/api/enrollment/invitations',{token:owner,body:{user_id:user}});
  const data=JSON.parse(Buffer.from(invitation.data.code.split('#')[1],'base64url'));
  const proof={token:data.token,claim_secret:randomBytes(32).toString('hex'),public_key:randomBytes(32).toString('base64')};
  assert.equal((await f.request('/api/enrollment/cancel',{body:proof})).status,200);
  assert.equal((await f.request('/api/enrollment/cancel',{body:proof})).status,410);
  assert.equal((await f.request('/api/enrollment/claim',{body:{...proof,name:'Canceled before claim'}})).status,410);
  assert.equal((await f.request('/api/enrollment/devices',{token:owner})).data.devices.length,0);
  const phone=await pendingPhone(f,owner),cancel={token:phone.body.token,claim_secret:phone.body.claim_secret,public_key:phone.body.public_key};
  await f.stop();await f.start(); // Phone has no saved ID after a lost response.
  assert.equal((await f.request('/api/enrollment/cancel',{body:{...cancel,claim_secret:'0'.repeat(64)}})).status,403);
  assert.equal((await f.request('/api/enrollment/cancel',{body:{...cancel,public_key:randomBytes(32).toString('base64')}})).status,403);
  assert.equal((await f.request('/api/enrollment/cancel',{body:{...cancel,id:phone.id}})).status,400);
  assert.equal((await f.request(`/api/enrollment/devices/${phone.id}/approve`,{token:owner,body:{verification:phone.verification}})).status,200);
  await acknowledge(f);await f.request('/api/enrollment/status',{body:{id:phone.id,claim_secret:phone.body.claim_secret}});
  const db=new DatabaseSync(join(f.data,'field-notes.sqlite3'));
  try{
   db.prepare("UPDATE devices SET state='active' WHERE id=?").run(phone.id);
   assert.equal((await f.request('/api/enrollment/cancel',{body:cancel})).status,409,'old invitation proof must not revoke an active phone');
   db.prepare("UPDATE devices SET state='ready' WHERE id=?").run(phone.id);
   assert.equal((await f.request('/api/enrollment/cancel',{body:cancel})).status,200);
   assert.equal((await f.request('/api/enrollment/cancel',{body:cancel})).status,200);
   assert.equal((await f.request('/api/enrollment/status',{body:{id:phone.id,claim_secret:phone.body.claim_secret}})).status,410);
   assert.equal((await f.request('/api/enrollment/devices',{token:owner})).data.devices[0].state,'revoking');
   await acknowledge(f);assert.equal((await f.request('/api/enrollment/devices',{token:owner})).data.devices[0].state,'revoked');
   assert.equal(db.prepare('SELECT COUNT(*) AS n FROM devices').get().n,1);
  }finally{db.close();}
 }finally{await f.close();}
});

test('inactive cleanup waits for root peer-removal acknowledgement and allows safe key reuse without reviving old secrets',async()=>{
 const f=await fixture({enrollment:true});
 try{
  const owner=await f.login();await f.request('/api/reauth',{token:owner,body:{password}});
  const phone=await pendingPhone(f,owner);
  const purge=(body={confirmation:'REMOVE PAIRING'})=>f.request(`/api/enrollment/devices/${phone.id}/purge`,{token:owner,body});
  assert.equal((await purge()).status,409,'pending phone cannot be silently purged');
  assert.equal((await purge({confirmation:'wrong'})).status,400);
  assert.equal((await f.request(`/api/enrollment/devices/${phone.id}/approve`,{token:owner,body:{verification:phone.verification}})).status,200);
  await acknowledge(f);
  const ready=await f.request('/api/enrollment/status',{body:{id:phone.id,claim_secret:phone.body.claim_secret}});assert.equal(ready.data.state,'ready');
  const db=new DatabaseSync(join(f.data,'field-notes.sqlite3'));
  try{
   db.prepare("UPDATE devices SET state='active' WHERE id=?").run(phone.id);
   assert.equal((await purge()).status,409,'an offline active binding remains in use');
   assert.equal((await f.request(`/api/enrollment/devices/${phone.id}/revoke`,{token:owner,body:{}})).data.state,'revoking');
   assert.equal((await purge()).status,409,'stale acknowledgement cannot permit cleanup');
   await acknowledge(f);assert.equal((await f.request('/api/enrollment/devices',{token:owner})).data.devices[0].state,'revoked');
   db.exec("CREATE TRIGGER fail_purge BEFORE INSERT ON audit WHEN NEW.action='device.purge' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
   assert.equal((await purge()).status,500);assert.equal(db.prepare('SELECT COUNT(*) AS n FROM devices WHERE id=?').get(phone.id).n,1);
   db.exec('DROP TRIGGER fail_purge');
   assert.equal((await purge()).status,200);assert.equal((await purge()).status,200);
   assert.equal((await f.request('/api/enrollment/claim',{body:phone.body})).status,410);
   assert.equal((await f.request('/api/enrollment/status',{body:{id:phone.id,claim_secret:phone.body.claim_secret}})).status,404);
   const next=await pendingPhone(f,owner,phone.body.public_key);assert.notEqual(next.id,phone.id);
   assert.equal((await f.request(`/api/enrollment/devices/${next.id}/approve`,{token:owner,body:{verification:next.verification}})).status,200);
   await acknowledge(f);
   const newReady=(await f.request('/api/enrollment/status',{body:{id:next.id,claim_secret:next.body.claim_secret}})).data;
   assert.equal(newReady.state,'ready');assert.notEqual(newReady.configuration.preshared_key,ready.data.configuration.preshared_key);
  }finally{db.close()}
 }finally{await f.close()}
});

test('expired never-approved bindings can be removed but an administrator cannot purge owner bindings',async()=>{
 const f=await fixture({enrollment:true});
 try{
  const owner=await f.login();await f.request('/api/reauth',{token:owner,body:{password}});
  await f.request('/api/admin',{token:owner,body:{action:'user.create',name:'Alex',role:'admin',password}});
  const administrator=await f.login('Alex');await f.request('/api/reauth',{token:administrator,body:{password}});
  const phone=await pendingPhone(f,owner);const db=new DatabaseSync(join(f.data,'field-notes.sqlite3'));
  try{db.prepare('UPDATE devices SET expires=0 WHERE id=?').run(phone.id)}finally{db.close()}
  const body={confirmation:'REMOVE PAIRING'},path=`/api/enrollment/devices/${phone.id}/purge`;
  assert.equal((await f.request(path,{token:administrator,body})).status,403);
  const list=(await f.request('/api/enrollment/devices',{token:owner})).data.devices;
  assert.equal(list[0].state,'expired');assert.equal(list[0].can_purge,true);
  assert.equal((await f.request(path,{token:owner,body})).status,200);
  assert.equal((await f.request('/api/enrollment/claim',{body:phone.body})).status,410);
 }finally{await f.close()}
});

test('LAN enrollment is scoped, single-claim, approved, acknowledged, source-bound and revocable',async()=>{
 const f=await fixture({enrollment:true});
 try{
  const owner=await f.login(),actor=(await f.request('/api/snapshot',{token:owner})).data.actor;
  // Cleanup is private/source-bound, not an administrator website mutation.
  assert.equal((await f.request('/api/device/revoke-self',{token:owner,body:{}})).status,404);
  assert.equal((await f.request('/api/device/revoke-self',{base:f.device,token:owner,body:{},headers:{'X-Forwarded-For':'10.82.0.2'}})).status,403);
  const invite=()=>f.request('/api/enrollment/invitations',{token:owner,body:{user_id:actor.user_id}});
  assert.equal((await invite()).status,403);
  assert.equal((await f.request('/api/reauth',{token:owner,body:{password}})).status,200);
  const issued=await invite();assert.equal(issued.status,200);assert.match(issued.data.qr_svg,/<svg/);
  const payload=JSON.parse(Buffer.from(issued.data.code.split('#')[1],'base64url'));
  assert.equal(payload.role,'owner');assert.equal(payload.url,'https://192.168.1.10:9443/api/enrollment');
  const claimSecret=randomBytes(32).toString('hex'),key=randomBytes(32).toString('base64');
  const body={token:payload.token,public_key:key,claim_secret:claimSecret,name:'This phone'};
  const claim=await f.request('/api/enrollment/claim',{body});assert.equal(claim.status,200);assert.equal(claim.data.state,'pending_approval');
  const id=claim.data.id,poll={id,claim_secret:claimSecret};
  assert.deepEqual((await f.request('/api/enrollment/claim',{body})).data,claim.data);
  assert.equal((await f.request('/api/enrollment/claim',{body:{...body,public_key:randomBytes(32).toString('base64')}})).status,409);
  assert.equal((await f.request('/api/enrollment/claim',{body,origin:f.admin})).status,403);
  assert.equal((await f.request('/api/enrollment/status',{body:{id,claim_secret:randomBytes(32).toString('hex')}})).status,403);
  let status=await f.request('/api/enrollment/status',{body:poll});assert.equal(status.data.state,'pending_approval');assert(!status.data.configuration);
  assert.equal((await f.request(`/api/enrollment/devices/${id}/approve`,{token:owner,body:{verification:claim.data.verification}})).status,200);
  status=await f.request('/api/enrollment/status',{body:poll});assert.equal(status.data.state,'provisioning');assert(!status.data.activation_secret);
  const ack=async()=>{
   const raw=await readFile(join(f.data,'peers.json')),manifest=JSON.parse(raw);
   assert.equal((await stat(join(f.data,'peers.json'))).mode&0o777,0o600);
   await writeFile(f.peerStatus,JSON.stringify({version:1,digest:createHash('sha256').update(raw).digest('hex'),applied_at:Math.floor(Date.now()/1000),peers:manifest.peers.map(({preshared_key,...publicFields})=>publicFields),error:null}),{mode:0o600});
  };
  await ack();status=await f.request('/api/enrollment/status',{body:poll});assert.equal(status.data.state,'ready');assert.equal(status.data.configuration.address,'10.82.0.2/32');assert.equal(status.data.configuration.api,'https://10.82.0.1:8443');assert(!status.data.configuration.private_key);
  const activation={id,activation_secret:status.data.activation_secret};
  // Real TCP source here is loopback. Headers cannot impersonate the WG peer.
  assert.equal((await f.request('/api/device/activate',{base:f.device,body:activation,headers:{'X-Forwarded-For':'10.82.0.2','X-Real-IP':'10.82.0.2'}})).status,403);
  assert.equal((await f.request('/api/snapshot',{base:f.device,token:owner,headers:{'X-Forwarded-For':'10.82.0.2'}})).status,403);
  assert.equal((await f.request('/api/enrollment/devices',{base:f.device,token:owner})).status,403);
  const revoked=await f.request(`/api/enrollment/devices/${id}/revoke`,{token:owner,body:{}});assert.equal(revoked.status,200);assert.equal(revoked.data.state,'revoking');assert(revoked.data.authorization_revoked);
  assert.equal((await f.request('/api/enrollment/status',{body:poll})).status,410);
  await ack();const list=await f.request('/api/enrollment/devices',{token:owner});assert.equal(list.data.devices[0].state,'revoked');
  const db=await readFile(join(f.data,'field-notes.sqlite3'));assert(!db.includes(Buffer.from(payload.token)));assert(!db.includes(Buffer.from(activation.activation_secret)));
 }finally{await f.close();}
});
