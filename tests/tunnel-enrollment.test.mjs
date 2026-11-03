import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {DatabaseSync} from 'node:sqlite';
import {join} from 'node:path';
import {fixture,password} from './support.mjs';

test('tunnel invitations bind a phone key, publish only a restricted peer and reject every LAN/spoofed claim',async()=>{
 const f=await fixture({enrollment:true});
 try{
  const owner=await f.login(),user=(await f.request('/api/snapshot',{token:owner})).data.actor.user_id;
  const publicKey=Buffer.alloc(32,91).toString('base64');
  const issue=()=>f.request('/api/enrollment/tunnel-invitations',{token:owner,body:{user_id:user,public_key:publicKey}});
  assert.equal((await issue()).status,403);
  await f.request('/api/reauth',{token:owner,body:{password}});
  const first=await issue();assert.equal(first.status,200);
  const payload=JSON.parse(Buffer.from(first.data.code.split('#')[1],'base64url'));
  assert.equal(payload.version,2);assert.equal(payload.url,'https://10.82.0.1:8443/api/enrollment');
  assert.equal(payload.tunnel.public_key,publicKey);assert(!('private_key' in payload.tunnel));
  const claim={token:payload.token,public_key:publicKey,claim_secret:'a'.repeat(64),name:'Never touches management'};
  assert.equal((await f.request('/api/enrollment/claim',{body:claim})).status,403);
  assert.equal((await f.request('/api/enrollment/claim',{base:f.device,body:claim,headers:{'X-Forwarded-For':'10.82.0.2'}})).status,403);
  assert.equal((await f.request('/api/enrollment/claim',{base:f.device,body:claim,token:owner})).status,403);
  assert.equal((await issue()).data.code,first.data.code);
  const before=JSON.parse(await readFile(join(f.data,'peers.json')));assert.equal(before.peers.length,1);
  assert.equal(before.peers[0].public_key,publicKey);assert.equal(before.peers[0].address,'10.82.0.2/32');
  const db=new DatabaseSync(join(f.data,'field-notes.sqlite3'));
  try{db.prepare('UPDATE devices SET expires=? WHERE id=?').run(Math.floor(Date.now()/1000)-1,first.data.device_id)}finally{db.close()}
  assert.equal((await issue()).status,409,'An expired peer address remains reserved before root removal acknowledgement');
  const raw=await readFile(join(f.data,'peers.json')),manifest=JSON.parse(raw);assert.deepEqual(manifest.peers,[]);
  await writeFile(f.peerStatus,JSON.stringify({version:1,digest:createHash('sha256').update(raw).digest('hex'),applied_at:Math.floor(Date.now()/1000),peers:[],error:null}),{mode:0o600});
  const next=await issue();assert.equal(next.status,200);assert.notEqual(next.data.device_id,first.data.device_id);
  assert.notEqual(next.data.code,first.data.code);
 }finally{await f.close()}
});
