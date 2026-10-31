import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {fixture,password} from './support.mjs';

test('assignments have a private directory, revision arbitration and safe handoffs',async()=>{
 const f=await fixture();try{
  const owner=await f.login(),meta=(await f.request('/api/snapshot',{token:owner})).data;
  await f.request('/api/reauth',{token:owner,body:{password}});
  for(const [name,role] of [['Sam','member'],['Guest','viewer']])assert.equal((await f.request('/api/admin',{token:owner,body:{action:'user.create',name,role,password}})).status,200);
  const sam=await f.login('Sam'),guest=await f.login('Guest');
  assert.equal((await f.request('/api/people')).status,401);
  const directory=(await f.request('/api/people',{token:guest})).data.people;
  for(const p of directory)assert.deepEqual(Object.keys(p).sort(),['can_assign','id','name']);
  const samId=directory.find(p=>p.name==='Sam').id,guestId=directory.find(p=>p.name==='Guest').id,ownerId=meta.actor.user_id;
  assert.equal(directory.find(p=>p.id===guestId).can_assign,false);
  const send=(token,action,target,data={},revision=null,op=randomUUID())=>f.request('/api/commands',{token,body:{operation_id:op,epoch:meta.epoch,generation:meta.generation,action,target,expected_revision:revision,data}});
  const task=randomUUID();assert.equal((await send(owner,'task.create',task,{title:'Replace the air filter'})).data.record.data.assignee,null);
  const race=await Promise.all([send(owner,'task.update',task,{assignee:ownerId},1),send(sam,'task.update',task,{assignee:samId},1)]);
  assert.deepEqual(race.map(r=>r.status).sort(),[200,409]);
  const winner=race.find(r=>r.status===200).data.record;assert.equal(winner.revision,2);
  assert.equal((await send(owner,'task.update',task,{assignee:samId},2)).status,200);
  assert.equal((await send(owner,'task.start',task,{},3)).status,409,'assignment requires an explicit takeover');
  assert.equal((await send(sam,'task.start',task,{},3)).status,200);
  assert.equal((await send(owner,'task.update',task,{assignee:ownerId},4)).status,409,'assignment cannot steal an active claim');
  assert.equal((await send(sam,'task.release',task,{},4)).status,200);
  assert.equal((await send(owner,'task.update',task,{assignee:guestId},5)).status,400);
  assert.equal((await send(owner,'task.update',task,{assignee:randomUUID()},5)).status,400);
  assert.equal((await send(owner,'task.update',task,{assignee:42},5)).status,400);
  assert.equal((await send(guest,'task.update',task,{assignee:ownerId},5)).status,403);
  const op=randomUUID();const assigned=await send(owner,'task.update',task,{assignee:ownerId},5,op);assert.equal(assigned.status,200);
  assert.deepEqual((await send(owner,'task.update',task,{assignee:ownerId},5,op)).data,assigned.data);
  assert.equal((await send(owner,'task.complete',task,{},6)).data.record.data.completed_by,ownerId);
  assert.equal((await f.request('/api/snapshot',{token:sam})).data.records.find(r=>r.id===task).data.assignee,ownerId);
  assert.equal((await send(owner,'task.reopen',task,{},7)).data.record.data.completed_at,null);
  assert.equal((await send(owner,'task.update',task,{assignee:null},8)).status,200);
  assert.equal((await send(owner,'task.update',task,{assignee:samId},9)).status,200);
  await f.request('/api/reauth',{token:owner,body:{password}});
  assert.equal((await f.request('/api/admin',{token:owner,body:{action:'user.disable',target:samId}})).status,200);
  const other=randomUUID();assert.equal((await send(owner,'task.create',other,{title:'Offline assignment',assignee:samId})).status,400);
  assert.equal((await send(owner,'task.update',task,{notes:'History is preserved',assignee:samId},10)).status,200,'unchanged historic assignment permits ordinary edits');
  const people=(await f.request('/api/people',{token:owner})).data.people;assert.equal(people.find(p=>p.id===samId).can_assign,false);
  await f.stop();await f.start();
  const persisted=(await f.request('/api/snapshot',{token:owner})).data.records.find(r=>r.id===task);assert.equal(persisted.data.assignee,samId);assert.equal(persisted.data.notes,'History is preserved');
 }finally{await f.close();}
});
