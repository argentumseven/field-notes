// Synthetic documentation data only. Never connect this helper to household data.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {fixture,password} from '../../tests/support.mjs';

export async function illustration(browser){
 const page=await browser.newPage({viewport:{width:800,height:450},deviceScaleFactor:1});
 try{
  await page.setContent(`<body style="margin:0;background:#f4f4ed;color:#202927;font:24px system-ui;padding:50px"><h2 style="font-size:28px">Shelf measurements</h2><hr style="border:1px solid #d4dcd1"><p>Width: 120 cm</p><p>Depth: 25 cm</p><p>Gap above counter: 65 cm</p><small>Reference attachment · synthetic demonstration</small></body>`);
  return await page.screenshot({type:'png'});
 }finally{await page.close();}
}

export async function demo(image, enrollment = false){
 const f=await fixture({enrollment});
 try{
  const login=async(name,device)=>f.login(name,password);
  const owner=await login('owner','Home computer · Linux');
  const meta=(await f.request('/api/snapshot',{token:owner})).data;
  const command=async(action,target,data={},revision=null,token=owner)=>{
   const r=await f.request('/api/commands',{token,body:{operation_id:randomUUID(),epoch:meta.epoch,generation:meta.generation,action,target,expected_revision:revision,data}});
   assert.equal(r.status,200,`${action}: ${JSON.stringify(r.data)}`);return r.data.record;
  };
  assert.equal((await f.request('/api/reauth',{token:owner,body:{password}})).status,200);
  for(const [name,role]of [['Sam','member'],['Alex','admin'],['Guest','viewer']]){
   assert.equal((await f.request('/api/admin',{token:owner,body:{action:'user.create',name,role,password}})).status,200);
  }
  const sam=await login('Sam','Sam’s phone · Android');
  await login('Alex','Alex’s computer · Windows');
  const home=randomUUID(),workshop=randomUUID(),weekend=randomUUID();
  await command('project.create',home,{title:'Home & garden'});
  await command('project.create',workshop,{title:'The workbench'});
  await command('project.create',weekend,{title:'Weekend plans'});
  const tasks=[
   ['shelves',home,'Plan the kitchen shelves','decide','2026-11-08','Measure the alcove before buying timber.\n\nTwo oak shelves, 120 cm wide. Leave room for the coffee grinder. Check the wall fixings together.'],
   ['boiler',home,'Book the boiler service','do','2026-11-03','Ask for the first available morning appointment.'],
   ['herbs',home,'Plant the winter herbs','delegate','2026-11-07','Rosemary, thyme, and a sheltered spot by the kitchen.'],
   ['lamp',home,'Choose a reading lamp','defer','','Warm light and a small footprint.'],
   ['backup',workshop,'Test the household backup','do','2026-11-01','Restore a sample into a new directory, then check the contents.'],
   ['bench',workshop,'Organize the workbench','decide','','Give the small tools a permanent home.'],
   ['walk',weekend,'Plan a woodland walk','decide','2026-11-08','A short loop, a flask of coffee, and no rush.'],
   ['seeds',home,'Order herb seedlings','do','','The nursery order is confirmed.'],
  ];
  const ids={home,workshop,weekend};
  for(const [key,project_id,title,quadrant,due,notes]of tasks){ids[key]=randomUUID();await command('task.create',ids[key],{project_id,title,quadrant,due,notes,priority:({shelves:2,backup:1,lamp:2,bench:4})[key]||3,estimate_minutes:({shelves:30,backup:15,walk:20})[key]||0,planned_for:['shelves','walk'].includes(key)?'2026-10-30':'',blocked:key==='herbs',pinned:key==='backup'});}
  ids.groceries=randomUUID();ids.routines=randomUUID();
  await command('list.create',ids.groceries,{title:'Groceries',notes:'Shared shopping, without a project or deadline.'});
  await command('list.create',ids.routines,{title:'Household routines',notes:'The practical things we keep coming back to.'});
  await command('task.create',randomUUID(),{title:'Oats and coffee',project_id:ids.groceries,priority:4});
  await command('task.create',randomUUID(),{title:'Check parcel delivery',priority:2});
  ids.reference=randomUUID();
  await command('note.create',ids.reference,{title:'Household reference',body:'Useful details belong here, without becoming tasks.\n\nAir filter: 20 × 25 × 1\nPaint finish: warm white, matte\nKeep a copy of the appliance receipts with the household backup.',pinned:true});
  await command('note.create',randomUUID(),{title:'Weekend ideas',body:'A woodland walk, a new recipe, or a quiet afternoon at home.'});
  await command('event.create',randomUUID(),{title:'Household planning',notes:'Review the week together.',all_day:true,start_date:'2026-10-30',end_date:'2026-10-31',starts_at:0,ends_at:0});
  await command('task.start',ids.boiler,{},1,sam);
  await command('task.complete',ids.seeds,{},1);
  await command('comment.create',randomUUID(),{task_id:ids.shelves,body:'I can measure the alcove on Saturday morning.'},null,sam);
  await command('comment.create',randomUUID(),{task_id:ids.shelves,body:'Perfect. I attached the measurements for reference.'});
  await command('image.create',randomUUID(),{task_id:ids.shelves,name:'Shelf measurements.png',bytes:image.toString('base64')});
  return {...f,ids,owner,sam,command};
 }catch(e){await f.close();throw e;}
}
