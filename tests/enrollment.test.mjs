import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes,createHash} from 'node:crypto';
import {readFile,writeFile,stat} from 'node:fs/promises';
import {join} from 'node:path';
import {fixture,password} from './support.mjs';

test('LAN enrollment is scoped, single-claim, approved, acknowledged, source-bound and revocable',async()=>{
 const f=await fixture({enrollment:true});
 try{
  const owner=await f.login(),actor=(await f.request('/api/snapshot',{token:owner})).data.actor;
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
