import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {fixture,password} from './support.mjs';

test('personal alerts retain shared calendar content, replay safely and fence legacy editors',async()=>{
 const f=await fixture();try{
  const owner=await f.login(),snapshot=(await f.request('/api/snapshot',{token:owner})).data;
  await f.request('/api/reauth',{token:owner,body:{password}});
  assert.equal((await f.request('/api/admin',{token:owner,body:{action:'user.create',name:'Sam',role:'member',password}})).status,200);
  const sam=await f.login('Sam'),people=(await f.request('/api/people',{token:owner})).data.people;
  const ownerId=snapshot.actor.user_id,samId=people.find(p=>p.name==='Sam').id;
  const send=(token,action,target,data={},revision=null,operation_id=randomUUID())=>f.request('/api/commands',{token,body:{operation_id,epoch:snapshot.epoch,generation:snapshot.generation,action,target,expected_revision:revision,data}});
  const rule=randomUUID(),event=randomUUID(),rules={alerts:[{id:rule,minutes_before:0,importance:'urgent',sound:'chime',volume:100,vibrate:true}],repeat:null};
  const options={audience:{mode:'people',people:[ownerId]},playback:{[rule]:{tone:'beacon',duration_seconds:30,alarm:true,unmute:true,alarm_volume:100}}};
  const created=await send(sam,'event.create',event,{title:'Personal appointment',all_day:false,starts_at:1793718000,ends_at:null,notes:'Bring the forms',reminders:rules,reminder_options:options});
  assert.equal(created.status,200,JSON.stringify(created.data));assert.deepEqual(created.data.record.data.reminder_rules,rules);
  const other=(await f.request('/api/snapshot',{token:sam})).data.records.find(r=>r.id===event);
  assert.equal(other.data.title,'Personal appointment');assert.deepEqual(other.data.reminders,{alerts:[],repeat:null});assert.deepEqual(other.data.reminder_rules,rules);
  assert.equal((await send(sam,'event.update',event,{...created.data.record.data,title:'Edited by partner',reminder_options:options},1)).status,400,'server rejects readonly history fields');
  const op=randomUUID();const edit=await send(sam,'event.update',event,{title:'Edited by partner'},1,op);
  assert.equal(edit.status,200);assert.deepEqual(edit.data.record.data.reminders,{alerts:[],repeat:null});
  assert.deepEqual((await send(sam,'event.update',event,{title:'Edited by partner'},1,op)).data,edit.data,'replay is projected for the requesting person');
  const blind=await send(sam,'event.update',event,{reminders:{alerts:[],repeat:null}},2);
  assert.equal(blind.status,409);assert.match(blind.data.error,/reminder|update/i);
  const changed=await send(sam,'event.update',event,{reminders:rules,reminder_options:{...options,audience:{mode:'everyone',people:[]}}},2);
  assert.equal(changed.status,200,JSON.stringify(changed.data));assert.deepEqual(changed.data.record.data.reminders,rules);
  assert.deepEqual((await f.request('/api/snapshot',{token:owner})).data.records.find(r=>r.id===event).data.reminders,rules);
  const task=randomUUID();const taskReply=await send(owner,'task.create',task,{title:'Assigned task',assignee:samId,due:'2026-11-05'});
  assert.equal(taskReply.status,200);assert.equal(taskReply.data.record.data.reminder_options.audience.mode,'everyone');assert.equal(taskReply.data.record.data.reminders,undefined,'Everyone retains the implicit due-date reminder when no explicit rule was set');
  assert.equal((await f.request('/api/snapshot',{token:sam})).data.records.find(r=>r.id===task).data.reminders,undefined,'Everyone includes the assigned person for the implicit due reminder');
  const head=(await f.request('/api/head',{token:sam})).data;assert.equal(head.compatibility.protocol_min,1);assert(head.compatibility.features.includes('reminder-options-v1'));
  const metadata=(await f.request('/api/device/update',{token:sam,base:f.device})).data;assert.deepEqual(metadata.compatibility,head.compatibility);assert.equal(metadata.available,false);assert.match(metadata.error,/APK/i);
  const onlySam={...options,audience:{mode:'people',people:[samId]}};
  assert.equal((await send(owner,'event.update',event,{reminder_options:onlySam},3)).status,200);
  await f.request('/api/reauth',{token:owner,body:{password}});
  assert.equal((await f.request('/api/admin',{token:owner,body:{action:'user.disable',target:samId}})).status,200);
  assert.equal((await send(owner,'event.update',event,{title:'Retained appointment history',reminder_options:onlySam},4)).status,200,'unchanged inactive recipients must not prevent ordinary edits');
  assert.equal((await send(owner,'task.create',randomUUID(),{title:'New inactive recipient',reminder_options:onlySam})).status,400,'new selections cannot use disabled accounts');
  assert.equal((await send(owner,'event.create',randomUUID(),{title:'New inactive appointment',all_day:false,starts_at:1793718000,reminder_options:onlySam})).status,400);
  await f.stop();await f.start();
  assert.equal((await f.request('/api/snapshot',{token:owner})).data.records.find(r=>r.id===event).data.reminder_options.playback[rule].tone,'beacon');
 }finally{await f.close()}
});

test('recipient and playback validation reject unknown people and unsafe sound instructions',async()=>{
 const f=await fixture();try{
  const token=await f.login(),meta=(await f.request('/api/snapshot',{token})).data,rule=randomUUID();
  const base={audience:{mode:'everyone',people:[]},playback:{}};
  const invalid=[{...base,audience:{mode:'people',people:[]}},{...base,audience:{mode:'people',people:[randomUUID()]}},{...base,audience:{mode:'people',people:[meta.actor.user_id,meta.actor.user_id]}},...[
   {tone:'https://outside.test/audio',duration_seconds:10,alarm:true,unmute:true,alarm_volume:100},
   {tone:'siren',duration_seconds:3600,alarm:true,unmute:true,alarm_volume:100},
   {tone:'siren',duration_seconds:30,alarm:false,unmute:true,alarm_volume:100},
   {tone:'siren',duration_seconds:30,alarm:true,unmute:true,alarm_volume:200}
  ].map(p=>({...base,playback:{[rule]:p}}))];
  for(const reminder_options of invalid){
   const reply=await f.request('/api/commands',{token,body:{operation_id:randomUUID(),epoch:meta.epoch,generation:meta.generation,action:'task.create',target:randomUUID(),expected_revision:null,data:{title:'Invalid reminder',reminder_options}}});
   assert.equal(reply.status,400,JSON.stringify(reply.data));
  }
  assert.equal((await f.request('/api/snapshot',{token})).data.records.length,0);
 }finally{await f.close()}
});
