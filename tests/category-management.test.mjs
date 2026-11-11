import {test} from "node:test";
import assert from "node:assert/strict";
import {randomUUID} from "node:crypto";
import {fixture,password} from "./support.mjs";

async function scene() {
  const f=await fixture();
  const token=await f.login();
  const auth=await f.request("/api/reauth",{token,body:{password}});
  assert.equal(auth.status,200);
  const initial=(await f.request("/api/snapshot",{token})).data;
  const send=(action,target,data={},revision=null)=>
    f.request("/api/commands",{token,body:{
      operation_id:randomUUID(),epoch:initial.epoch,generation:initial.generation,
      action,target,expected_revision:revision,data
    }});
  const admin=(action,data={})=>f.request("/api/admin",{token,body:{action,...data}});
  const snapshot=async()=>(await f.request("/api/snapshot",{token})).data;
  const ok=async(...args)=>{const r=await send(...args);assert.equal(r.status,200,JSON.stringify(r.data));return r.data.record};
  return {f,token,send,admin,snapshot,ok};
}

test("Custom category rename, archive, merge and delete preserve all assigned records",async()=>{
 const s=await scene();
 try {
  assert.deepEqual((await s.snapshot()).categories,[]);
  const id=randomUUID(),key="custom:"+id;
  assert.equal((await s.admin("category.create",{target:id,name:"Garden projects"})).status,200);
  let cat=(await s.snapshot()).categories.find(c=>c.id===id);
  assert.equal(cat.name,"Garden projects");
  const p=await s.ok("project.create",randomUUID(),{title:"Restore soil",category:key});
  const t=await s.ok("task.create",randomUUID(),{title:"Pick up mulch",notes:"Do not lose this",category:key,project_id:p.id});
  const note=await s.ok("note.create",randomUUID(),{title:"Measurements",body:"Six bags",category:key});
  assert.equal((await s.admin("category.rename",{target:id,name:"Landscaping",revision:cat.revision})).status,200);
  cat=(await s.snapshot()).categories.find(c=>c.id===id);
  assert.equal(cat.name,"Landscaping");
  assert.equal((await s.snapshot()).records.find(r=>r.id===t.id).data.category,key);
  assert.equal((await s.admin("category.archive",{target:id,revision:cat.revision})).status,200);
  cat=(await s.snapshot()).categories.find(c=>c.id===id);
  assert.equal(cat.archived,true);
  assert.equal((await s.send("task.create",randomUUID(),{title:"Cannot choose archived",category:key})).status,409);
  // Existing assignment remains editable and retains its category.
  const changed=await s.ok("task.update",t.id,{notes:"Important measurements"},t.revision);
  assert.equal(changed.data.category,key);
  assert.equal((await s.send("task.update",t.id,{category:"general"},changed.revision)).status,409,"old client cannot silently replace custom classification");
  const contents=[p,changed,note].map(r=>({id:r.id,revision:r.revision}));
  assert.equal((await s.admin("category.merge",{target:id,revision:cat.revision,into:"home",contents,confirmation:"MERGE CATEGORY"})).status,200);
  const rows=(await s.snapshot()).records;
  for(const r of [p,changed,note])assert.equal(rows.find(v=>v.id===r.id).data.category,"home");
  assert.equal(rows.find(v=>v.id===t.id).data.notes,"Important measurements");
  assert.equal(rows.find(v=>v.id===note.id).data.body,"Six bags");
  cat=(await s.snapshot()).categories.find(c=>c.id===id);
  assert.equal((await s.admin("category.delete",{target:id,revision:cat.revision,confirmation:"DELETE CATEGORY"})).status,200);
  assert.equal((await s.snapshot()).categories.find(c=>c.id===id),undefined);
 }finally{await s.f.close();}
});

test("Category merge is revision fenced and cannot delete a category with assigned work",async()=>{
 const s=await scene();
 try {
  const id=randomUUID(),key="custom:"+id;
  assert.equal((await s.admin("category.create",{target:id,name:"Assignments"})).status,200);
  const category=(await s.snapshot()).categories.find(c=>c.id===id);
  const task=await s.ok("task.create",randomUUID(),{title:"Keep",category:key});
  assert.equal((await s.admin("category.delete",{target:id,revision:category.revision,confirmation:"DELETE CATEGORY"})).status,409);
  assert.equal((await s.admin("category.merge",{target:id,revision:category.revision,into:"work",contents:[],confirmation:"MERGE CATEGORY"})).status,409);
  const updated=await s.ok("task.update",task.id,{notes:"Newer edit"},task.revision);
  assert.equal((await s.admin("category.merge",{target:id,revision:category.revision,into:"work",contents:[{id:task.id,revision:task.revision}],confirmation:"MERGE CATEGORY"})).status,409);
  assert.equal((await s.snapshot()).records.find(r=>r.id===task.id).data.notes,"Newer edit");
  assert.equal((await s.admin("category.merge",{target:id,revision:category.revision,into:"work",contents:[{id:updated.id,revision:updated.revision}],confirmation:"MERGE CATEGORY"})).status,200);
  assert.equal((await s.snapshot()).records.find(r=>r.id===task.id).data.category,"work");
 }finally{await s.f.close();}
});

test("List unbundle releases work tasks without deleting the list, comments or work history",async()=>{
 const s=await scene();
 try {
  const list=await s.ok("list.create",randomUUID(),{title:"Build checklist",items_are_tasks:true});
  const task=await s.ok("task.create",randomUUID(),{title:"Measure wall",notes:"Preserve measurements",project_id:list.id});
  const comment=await s.ok("comment.create",randomUUID(),{task_id:task.id,body:"Remember studs"});
  const contents=[task,comment].map(r=>({id:r.id,revision:r.revision}));
  assert.equal((await s.send("list.unbundle",list.id,{contents:[{id:task.id,revision:0},contents]},list.revision)).status,400);
  assert.equal((await s.send("list.unbundle",list.id,{contents:[{id:task.id,revision:0},{id:comment.id,revision:comment.revision}]},list.revision)).status,409);
  assert.equal((await s.send("list.unbundle",list.id,{contents},list.revision)).status,200);
  const rows=(await s.snapshot()).records;
  assert.equal(rows.find(r=>r.id===list.id).data.title,"Build checklist");
  assert.equal(rows.find(r=>r.id===task.id).data.project_id,"");
  assert.equal(rows.find(r=>r.id===task.id).data.notes,"Preserve measurements");
  assert.equal(rows.find(r=>r.id===comment.id).data.body,"Remember studs");
  assert.equal((await s.send("list.unbundle",list.id,{contents},list.revision+1)).status,409);
  const ref=await s.ok("list.create",randomUUID(),{title:"Shopping",items_are_tasks:false});
  assert.equal((await s.send("list.unbundle",ref.id,{contents:[]},ref.revision)).status,409,"reference lists must preserve their grouping");
 }finally{await s.f.close();}
});

test("Converting a work list to reference mode cannot strand an in-progress task",async()=>{
 const s=await scene();
 try{
  const list=await s.ok("list.create",randomUUID(),{title:"Queue",items_are_tasks:true});
  const task=await s.ok("task.create",randomUUID(),{title:"Task",project_id:list.id});
  assert.equal((await s.send("task.start",task.id,{},task.revision)).status,200);
  assert.equal((await s.send("list.update",list.id,{items_are_tasks:false},list.revision)).status,409);
  assert.equal((await s.send("task.release",task.id,{},task.revision+1)).status,200);
  assert.equal((await s.send("list.update",list.id,{items_are_tasks:false},list.revision)).status,200);
  const rows=(await s.snapshot()).records;
  assert.equal(rows.find(r=>r.id===task.id).data.project_id,list.id);
  assert.equal(rows.find(r=>r.id===list.id).data.items_are_tasks,false);
 }finally{await s.f.close();}
});
