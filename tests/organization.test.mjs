import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {fixture} from './support.mjs';

test('Inbox, typed lists/projects and shared notes preserve revisions, moves and deletion fences',async()=>{
 const f=await fixture();try{
  const token=await f.login(),meta=(await f.request('/api/snapshot',{token})).data;
  const send=(action,target,data={},revision=null)=>f.request('/api/commands',{token,body:{operation_id:randomUUID(),epoch:meta.epoch,generation:meta.generation,action,target,expected_revision:revision,data}});
  const list=randomUUID(),project=randomUUID(),task=randomUUID(),note=randomUUID();
  assert.equal((await send('task.create',task,{title:'Capture before organizing',priority:2})).status,200);
  assert.equal((await f.request('/api/snapshot',{token})).data.records.find(r=>r.id===task).data.project_id,'');
  assert.equal((await send('list.create',list,{title:'Groceries',notes:'Shared shopping'})).data.record.kind,'list');
  assert.equal((await send('project.create',project,{title:'Kitchen refresh'})).status,200);
  assert.equal((await send('task.update',task,{project_id:list},1)).status,200);
  assert.equal((await send('list.delete',list,{},1)).status,409);
  assert.equal((await send('task.update',task,{project_id:project},1)).status,409,'stale move cannot win');
  assert.equal((await send('task.update',task,{project_id:''},2)).status,200);
  assert.equal((await send('list.delete',list,{},1)).status,200);
  assert.equal((await send('task.create',randomUUID(),{title:'Stale list edit',project_id:list})).status,404);
  assert.equal((await send('note.create',note,{title:'Household reference',body:'Private reference text',pinned:true})).status,200);
  assert.equal((await send('task.update',task,{project_id:note},3)).status,400);
  assert.equal((await send('note.update',note,{body:'Revised together'},1)).status,200);
  assert.equal((await send('note.update',note,{body:'Stale overwrite'},1)).status,409);
  assert.equal((await send('task.update',task,{project_id:project},3)).status,200);
  assert.equal((await send('project.delete',project,{},1)).status,409);
  assert.equal((await send('task.update',task,{project_id:''},4)).status,200);
  assert.equal((await send('project.delete',project,{},1)).status,200);
  const snap=(await f.request('/api/snapshot',{token})).data;
  assert.equal(snap.records.find(r=>r.id===task).data.title,'Capture before organizing');
  assert.equal(snap.records.find(r=>r.id===note).data.body,'Revised together');
  assert.equal((await send('note.delete',note,{},2)).status,200);
 }finally{await f.close();}
});
