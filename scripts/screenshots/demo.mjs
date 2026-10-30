// Synthetic documentation data only. Never connect this helper to household data.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {fixture,password} from '../../tests/support.mjs';

export async function illustration(browser){
 const page=await browser.newPage({viewport:{width:800,height:450},deviceScaleFactor:1});
 try{
  await page.setContent(`<body style="margin:0"><svg xmlns="http://www.w3.org/2000/svg" width="800" height="450" viewBox="0 0 800 450"><rect width="800" height="450" fill="#edf2fa"/><text x="50" y="58" font-family="sans-serif" font-size="25" fill="#243654">Kitchen shelf measurements</text><text x="50" y="89" font-family="sans-serif" font-size="15" fill="#526078">Household demo · sketch before buying timber</text><rect x="175" y="130" width="450" height="245" rx="12" fill="#fff" stroke="#62738d" stroke-width="3"/><path d="M195 210H605M195 290H605" stroke="#a28b74" stroke-width="14"/><path d="M175 400H625M175 390V410M625 390V410" stroke="#2756af" stroke-width="2"/><text x="360" y="430" font-family="sans-serif" font-size="18" fill="#2756af">120 cm</text><path d="M665 130V375M655 130H675M655 375H675" stroke="#6942b7" stroke-width="2"/><text x="688" y="263" font-family="sans-serif" font-size="18" fill="#6942b7">65 cm</text><rect x="225" y="172" width="45" height="31" rx="4" fill="#9cb7d8"/><rect x="280" y="155" width="26" height="48" rx="3" fill="#beabdf"/><rect x="450" y="252" width="62" height="31" rx="8" fill="#84ac9b"/></svg></body>`);
  return await page.screenshot({type:'png'});
 }finally{await page.close();}
}

export async function demo(image){
 const f=await fixture();
 try{
  const login=async(name,device)=>{
   const r=await f.request('/api/login',{base:f.device,body:{name,password,device}});
   assert.equal(r.status,200);return r.data.token;
  };
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
  for(const [key,project_id,title,quadrant,due,notes]of tasks){ids[key]=randomUUID();await command('task.create',ids[key],{project_id,title,quadrant,due,notes});}
  await command('task.start',ids.boiler,{},1,sam);
  await command('task.complete',ids.seeds,{},1);
  await command('comment.create',randomUUID(),{task_id:ids.shelves,body:'I can measure the alcove on Saturday morning.'},null,sam);
  await command('comment.create',randomUUID(),{task_id:ids.shelves,body:'Perfect. I added a sketch so we can compare the shelf sizes.'});
  await command('image.create',randomUUID(),{task_id:ids.shelves,name:'Kitchen shelf sketch.png',bytes:image.toString('base64')});
  return {...f,ids,owner,sam,command};
 }catch(e){await f.close();throw e;}
}
