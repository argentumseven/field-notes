import {test,before,after} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {deflateSync} from 'node:zlib';
import {DatabaseSync} from 'node:sqlite';
import {fixture,password} from './support.mjs';
let f,owner,member,viewer,project,task,meta;
before(async()=>{f=await fixture();owner=await f.login();meta=(await f.request('/api/snapshot',{token:owner})).data;});
after(async()=>{await f?.close();});
const cmd=(action,target,data={},revision=null,extra={})=>({operation_id:randomUUID(),epoch:meta.epoch,generation:meta.generation,action,target,expected_revision:revision,data,...extra});
const send=(c,token=owner)=>f.request('/api/commands',{token,body:c});
const admin=body=>f.request('/api/admin',{token:owner,body});
async function reauth(){assert.equal((await f.request('/api/reauth',{token:owner,body:{password}})).status,200);}
function png(){
 const crc=b=>{let c=0xffffffff;for(const byte of b){c^=byte;for(let i=0;i<8;i++)c=(c>>>1)^(0xedb88320&-(c&1));}return(c^0xffffffff)>>>0;};
 const chunk=(type,data)=>{const b=Buffer.concat([Buffer.from(type),data]),len=Buffer.alloc(4),hash=Buffer.alloc(4);len.writeUInt32BE(data.length);hash.writeUInt32BE(crc(b));return Buffer.concat([len,b,hash]);};
 const h=Buffer.alloc(13);h.writeUInt32BE(1,0);h.writeUInt32BE(1,4);h[8]=8;h[9]=2;
 return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',h),chunk('tEXt',Buffer.from('GPS\0private-location')),chunk('IDAT',deflateSync(Buffer.from([0,250,120,100]))),chunk('IEND',Buffer.alloc(0))]);
}
test('unauthenticated and device-side administrator routes are unavailable',async()=>{
 assert.equal((await f.request('/api/snapshot')).status,401);
 assert.equal((await f.request('/api/admin',{base:f.device,token:owner})).status,404);
 assert.equal((await f.request('/',{base:f.device,token:owner})).status,404);
});
test('origin and CSRF checks reject browser mutations; content policy has no third-party assets',async()=>{
 assert.equal((await f.request('/api/login',{origin:'https://attacker.invalid',body:{name:'owner',password,device:'x'}})).status,403);
 assert.equal((await f.request('/api/commands',{body:cmd('project.create',randomUUID(),{title:'bad'})})).status,401);
 const home=await f.request('/');assert.equal(home.status,200);assert.match(home.headers.get('content-security-policy'),/default-src 'none'/);
});
test('admin changes require reauthentication; member and viewer roles are enforced',async()=>{
 assert.equal((await admin({action:'user.create',name:'early',password,role:'member'})).status,403);
 await reauth();for(const [name,role]of [['partner','member'],['guest','viewer']])assert.equal((await admin({action:'user.create',name,password,role})).status,200);
 member=await f.login('partner');viewer=await f.login('guest');
 assert.equal((await send(cmd('project.create',randomUUID(),{title:'Denied'}),viewer)).status,403);
 assert.equal((await f.request('/api/admin',{token:member})).status,403);
 const users=(await f.request('/api/admin',{token:owner})).data.users;
 assert.equal((await admin({action:'user.disable',target:users.find(u=>u.role==='owner').id})).status,409);
});
test('projects and tasks persist encrypted; initial revisions are authoritative',async()=>{
 project=randomUUID();task=randomUUID();
 assert.equal((await send(cmd('project.create',project,{title:'Home projects'}))).status,200);
 const r=await send(cmd('task.create',task,{project_id:project,title:'Private test task',notes:'Private note contents'}));assert.equal(r.status,200);assert.equal(r.data.record.revision,1);
 assert.equal((await f.request('/api/snapshot',{token:viewer})).data.records.length,2);
 for(const suffix of ['', '-wal']){try{const bytes=await readFile(join(f.data,'field-notes.sqlite3'+suffix));assert(!bytes.includes(Buffer.from('Private note contents')));assert(!bytes.includes(Buffer.from('Private test task')));}catch(e){if(e.code!=='ENOENT')throw e;}}
});
test('two concurrent claims result in exactly one winner',async()=>{
 const results=await Promise.all([send(cmd('task.start',task,{},1),owner),send(cmd('task.start',task,{},1),member)]);
 assert.deepEqual(results.map(r=>r.status).sort(),[200,409]);
 const current=(await f.request('/api/snapshot',{token:owner})).data.records.find(r=>r.id===task);assert.equal(current.revision,2);assert.equal(current.data.status,'in_progress');assert(current.data.claimant);
});
test('idempotent retry returns original result after restart; changed payload cannot reuse ID',async()=>{
 const c=cmd('comment.create',randomUUID(),{task_id:task,body:'A durable comment'});
 const first=await send(c);assert.equal(first.status,200);await f.stop();await f.start();
 const repeat=await send(c);assert.equal(repeat.status,200);assert.deepEqual(repeat.data,first.data);
 assert.equal((await send({...c,data:{task_id:task,body:'Different content'}})).status,409);
});
test('stale edits and client timestamps cannot overwrite accepted state',async()=>{
 assert.equal((await f.request('/api/head',{token:owner,headers:{'X-Field-Protocol':'99'}})).status,426);
 assert.equal((await send(cmd('task.update',task,{title:'Stale edit'},1))).status,409);
 assert.equal((await send({...cmd('task.update',task,{title:'Future timestamp'},2),timestamp:999999999999})).status,422);
 assert.equal((await send(cmd('task.update',task,{claimant:'forged'},2))).status,400);
});
test('images are authenticated, normalized, metadata-stripped and removed with their task',async()=>{
 const imageId=randomUUID(),raw=png();const r=await send(cmd('image.create',imageId,{task_id:task,name:'test.png',bytes:raw.toString('base64')}));assert.equal(r.status,200,JSON.stringify(r.data));
 assert.equal((await f.request('/api/images/'+imageId)).status,401);
 const image=await f.request('/api/images/'+imageId,{token:member});assert.equal(image.status,200);assert(!image.data.includes(Buffer.from('private-location')));assert.equal(image.headers.get('content-type'),'image/png');
 assert.equal((await send(cmd('image.create',randomUUID(),{task_id:task,name:'evil.svg',bytes:Buffer.from('<svg><script/></svg>').toString('base64')}))).status,400);
 const a=await f.request('/api/admin',{token:owner});await reauth();
 const before=(await f.request('/api/snapshot',{token:owner})).data.records.find(r=>r.id===task);
 assert.equal((await send(cmd('task.delete',task,{},before.revision))).status,200);
 assert.equal((await f.request('/api/images/'+imageId,{token:owner})).status,404);
 assert.equal((await send(cmd('comment.create',randomUUID(),{task_id:task,body:'Zombie'}))).status,404);
});
test('role changes and revocation invalidate previously authenticated clients',async()=>{
 const users=(await f.request('/api/admin',{token:owner})).data.users;await reauth();
 const partner=users.find(u=>u.name==='partner');assert.equal((await admin({action:'user.role',target:partner.id,role:'viewer'})).status,200);
 assert.equal((await f.request('/api/snapshot',{token:member})).status,401);
 member=await f.login('partner');assert.equal((await send(cmd('project.create',randomUUID(),{title:'Not permitted'}),member)).status,403);
});
test('a storage failure rolls back the whole mutation and the same operation can retry',async()=>{
 const db=new DatabaseSync(join(f.data,'field-notes.sqlite3'));
 db.exec("CREATE TRIGGER fail_event BEFORE INSERT ON events WHEN NEW.action='project.create' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
 const c=cmd('project.create',randomUUID(),{title:'Rolled back project'});
 try{assert.equal((await send(c)).status,500);assert(!(await f.request('/api/snapshot',{token:owner})).data.records.some(r=>r.id===c.target));}
 finally{db.exec('DROP TRIGGER fail_event');db.close();}
 assert.equal((await send(c)).status,200);
});
test('calendar validation rejects impossible dates without changing the task',async()=>{
 const target=randomUUID();assert.equal((await send(cmd('task.create',target,{title:'Calendar check',project_id:project,due:'2026-02-30'}))).status,400);
 assert.equal((await send(cmd('task.create',target,{title:'Calendar check',project_id:project,due:'2028-02-29'}))).status,200);
});
test('workspace clear fences stale commands and requires typed confirmation',async()=>{
 await reauth();assert.equal((await admin({action:'workspace.clear',generation:meta.generation,confirmation:'wrong'})).status,409);
 assert.equal((await admin({action:'workspace.clear',generation:meta.generation,confirmation:'CLEAR WORKSPACE'})).status,200);
 assert.equal((await send(cmd('project.create',randomUUID(),{title:'Stale queue'}))).status,409);
 const snapshot=(await f.request('/api/snapshot',{token:owner})).data;assert.equal(snapshot.records.length,0);assert.equal(snapshot.generation,meta.generation+1);meta=snapshot;
});
test('encrypted backup restores a consistent database with a fresh epoch and revoked sessions',async()=>{
 const p=randomUUID();assert.equal((await send(cmd('project.create',p,{title:'Restored private project'}))).status,200);await reauth();
 const backup=await f.request('/api/admin/backup',{token:owner});assert.equal(backup.status,200);assert(!backup.data.includes(Buffer.from('SQLite format 3')));assert(!backup.data.includes(Buffer.from('Restored private project')));
 const file=join(f.root,'backup.enc');await writeFile(file,backup.data,{mode:0o600});await f.stop();const restored=join(f.root,'restored');f.restore(file,restored);await f.start(restored);
 assert.equal((await f.request('/api/snapshot',{token:owner})).status,401);owner=await f.login();const snapshot=(await f.request('/api/snapshot',{token:owner})).data;assert.notEqual(snapshot.epoch,meta.epoch);assert(snapshot.records.some(r=>r.id===p));
});
