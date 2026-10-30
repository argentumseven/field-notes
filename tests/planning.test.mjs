import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {fixture,password} from './support.mjs';
import '../apps/admin/planning.js';

test('priority scoring matches independent golden cases, including undated tasks',async()=>{
 const vectors=JSON.parse(await readFile(new URL('./fixtures/planning-cases.json',import.meta.url)));
 for(const c of vectors.cases){
  const rank=globalThis.FieldPlanning.rankTask({id:c.name,kind:'task',data:c.data},vectors.today,vectors.now,vectors.user);
  assert.equal(rank.score,c.score,c.name);assert.equal(rank.eligible,c.eligible,c.name);
 }
});
test('server accepts undated priorities, preserves creation age, validates plans and real calendar intervals',async()=>{
 const f=await fixture();
 try{
  const token=await f.login(),meta=(await f.request('/api/snapshot',{token})).data;
  async function send(action,target,data={},revision=null){return f.request('/api/commands',{token,body:{operation_id:randomUUID(),epoch:meta.epoch,generation:meta.generation,action,target,expected_revision:revision,data}});}
  const project=randomUUID(),task=randomUUID();assert.equal((await send('project.create',project,{title:'Life'})).status,200);
  const created=await send('task.create',task,{project_id:project,title:'Important, no deadline',priority:1,estimate_minutes:45});assert.equal(created.status,200);assert.equal(created.data.record.data.due,'');
  const creationTime=created.data.record.data.created_at;assert(creationTime>0);
  const updated=await send('task.update',task,{priority:2,planned_for:'2026-11-01',due:''},1);assert.equal(updated.status,200);assert.equal(updated.data.record.data.created_at,creationTime);
  for(const data of [{priority:0},{priority:5},{priority:null},{estimate_minutes:-1},{estimate_minutes:481},{pinned:'yes'},{planned_for:'2026-02-30'}])assert.equal((await send('task.update',task,data,2)).status,400,JSON.stringify(data));
  assert.equal((await send('task.update',task,{blocked:true},2)).status,200);assert.equal((await send('task.start',task,{},3)).status,409);
  const allDay=randomUUID();assert.equal((await send('event.create',allDay,{title:'Family weekend',all_day:true,start_date:'2026-11-01',end_date:'2026-11-03'})).status,200);
  assert.equal((await send('event.create',randomUUID(),{title:'Invalid',all_day:true,start_date:'2026-11-01',end_date:'2026-11-01'})).status,400);
  const start=Date.parse('2026-11-01T04:00:00Z')/1000,end=Date.parse('2026-11-02T05:00:00Z')/1000;
  const timed=await send('event.create',randomUUID(),{title:'Across a clock change',all_day:false,starts_at:start,ends_at:end});assert.equal(timed.status,200);assert.equal(timed.data.record.data.ends_at-timed.data.record.data.starts_at,90000);
  assert.equal((await send('event.create',randomUUID(),{title:'Backwards',all_day:false,starts_at:end,ends_at:start})).status,400);
  assert.equal((await send('event.update',allDay,{title:'A clearer title'},1)).status,200);assert.equal((await send('event.delete',allDay,{},1)).status,409);assert.equal((await send('event.delete',allDay,{},2)).status,200);
  // A second independently authenticated client sees calendar changes and
  // deletion through normal sync; visibility does not grant event authorship.
  assert.equal((await f.request('/api/reauth',{token,body:{password}})).status,200);
  assert.equal((await f.request('/api/admin',{token,body:{action:'user.create',name:'Calendar reader',role:'member',password}})).status,200);
  const other=await f.login('Calendar reader');
  const otherRecords=()=>f.request('/api/snapshot',{token:other}).then(r=>r.data.records);
  assert.equal((await otherRecords()).find(r=>r.id===timed.data.record.id).data.starts_at,start);
  const editBody={operation_id:randomUUID(),epoch:meta.epoch,generation:meta.generation,action:'event.update',target:timed.data.record.id,expected_revision:1,data:{title:'Unauthorized edit'}};
  assert.equal((await f.request('/api/commands',{token:other,body:editBody})).status,403);
  assert.equal((await send('event.update',timed.data.record.id,{title:'Agreed family time'},1)).status,200);
  assert.equal((await otherRecords()).find(r=>r.id===timed.data.record.id).data.title,'Agreed family time');
  assert.equal((await send('event.update',timed.data.record.id,{title:'Stale calendar edit'},1)).status,409);
  const snapshot=(await f.request('/api/snapshot',{token})).data;assert(!snapshot.records.some(r=>r.id===allDay));assert(snapshot.records.some(r=>r.kind==='event'));
  assert.equal((await send('event.delete',timed.data.record.id,{},2)).status,200);
  assert(!(await otherRecords()).some(r=>r.id===timed.data.record.id));
 }finally{await f.close();}
});
