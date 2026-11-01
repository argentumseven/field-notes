// Real Chromium views of a disposable Rust-backed workspace. Secrets shown in
// invitation captures belong only to this deleted synthetic fixture.
import {chromium} from 'playwright';
import {mkdir,readdir,rm,readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {join} from 'node:path';
import assert from 'node:assert/strict';
import {password} from '../tests/support.mjs';
import {demo,illustration} from './screenshots/demo.mjs';
const out='docs/screenshots/web';await mkdir(out,{recursive:true});const old=await readdir(out),made=[];
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});let f;
try{
 f=await demo(await illustration(browser),true);
 const page=await browser.newPage({viewport:{width:1440,height:1050},colorScheme:'dark',locale:'en-US',timezoneId:'America/New_York'});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));const button=name=>page.getByRole('button',{name,exact:true});
 const shot=async name=>{await page.locator('#notice.visible').waitFor({state:'hidden',timeout:10000});await page.screenshot({path:join(out,name+'.png'),fullPage:true,animations:'disabled'});made.push(name+'.png');console.log('Captured web/'+name);};
 const verify=async()=>{await page.locator('#dialog').waitFor({state:'visible'});if(await page.getByRole('heading',{name:'Confirm it’s you',exact:true}).isVisible()){await page.getByLabel('Password',{exact:true}).fill(password);await button('Verify').click();}};
 const cancel=async()=>{await button('Cancel').click();await page.locator('#dialog').waitFor({state:'hidden'});};
 await page.goto(f.admin);await page.getByRole('heading',{name:'Sign in'}).waitFor();await shot('01-sign-in');
 await page.getByLabel('User name',{exact:true}).fill('owner');await page.getByLabel('Password',{exact:true}).fill(password);await button('Sign in').click();
 await button('Open task: Test the household backup').waitFor();await shot('02-focus');await button('Change theme').click();await shot('03-focus-paper');await button('Change theme').click();
 await button('Tasks').click();await shot('04-task-list');await button('Board').click();await shot('05-board');await button('List').click();
 await button('Open task: Plan the kitchen shelves').click();await page.locator('.image-grid img').evaluateAll(async images=>Promise.all(images.map(i=>i.complete?Promise.resolve():new Promise(r=>{i.onload=r;i.onerror=r;}))));await shot('06-task-planning');await page.locator('.detail').evaluate(n=>n.scrollTop=n.scrollHeight);await shot('07-notes-images-comments');await button('Close').click();
 await button('Calendar').click();await page.getByRole('heading',{name:await page.evaluate(()=>new Date().toLocaleDateString('en-US',{month:'long',year:'numeric'}))}).waitFor();await shot('08-calendar');await button('Add event').click();await page.getByLabel('Event title',{exact:true}).fill('Plan next week');await shot('09-calendar-event');await cancel();
 await button('Inbox').click();await shot('20-inbox');
 await button('Collections').click();await shot('21-collections');
 await button('Notes').click();await shot('22-notes');await page.locator('.note-row').filter({hasText:'Household reference'}).click();await shot('23-shared-note');await button('Close').click();
 await page.locator('.collection-section .nav button').filter({hasText:'Groceries'}).click();await shot('24-grocery-list');
 await button('Assignments').click();await shot('25-assignments');await page.locator('main').getByRole('button',{name:'New task',exact:true}).click();await page.getByLabel('Task title',{exact:true}).fill('Pick up the replacement filter');await page.getByLabel('Assigned to',{exact:true}).selectOption({label:'Sam'});await shot('28-new-assigned-task');await cancel();
 await button('My tasks').click();await shot('26-my-tasks');await button('Unassigned').click();await shot('27-unassigned');
 await button('Management').click();await page.getByRole('heading',{name:'Devices',exact:true}).waitFor();await shot('10-management');
 await page.locator('.sidebar-bottom').getByRole('button',{name:'Change password',exact:true}).click();await page.getByRole('heading',{name:'Change your password',exact:true}).waitFor();await shot('29-change-password');await cancel();
 await button('+ Add person').click();await verify();await page.getByLabel('Name',{exact:true}).fill('Taylor');await page.getByLabel('Role',{exact:true}).selectOption('member');await shot('17-add-person');await cancel();
 const samRow=page.locator('.item-row').filter({has:page.getByText('Sam',{exact:true})});await samRow.getByRole('button',{name:'Change role',exact:true}).click();await verify();await page.getByLabel('New role',{exact:true}).selectOption('admin');await shot('18-change-role');await cancel();
 await samRow.getByRole('button',{name:'Reset password',exact:true}).click();await verify();await page.getByRole('heading',{name:'Reset password for Sam',exact:true}).waitFor();await shot('30-reset-password');await cancel();

 await button('Create invitation').click();await verify();await page.getByLabel('Account',{exact:true}).selectOption({label:'Sam · member'});await page.locator('#dialog-submit').click();await page.locator('.invite-qr').waitFor();await shot('11-enrollment-invitation');await button('Done').click();
 const actor=(await f.request('/api/snapshot',{token:f.owner})).data.actor;
 const invite=await f.request('/api/enrollment/invitations',{token:f.owner,body:{user_id:actor.user_id}});assert.equal(invite.status,200);
 const payload=JSON.parse(Buffer.from(invite.data.code.split('#')[1],'base64url'));const claim=await f.request('/api/enrollment/claim',{body:{token:payload.token,public_key:Buffer.alloc(32,7).toString('base64'),claim_secret:'a'.repeat(64),name:'Owner’s GrapheneOS phone'}});assert.equal(claim.status,200);
 await page.reload();await page.getByRole('heading',{name:'Focus',exact:true}).waitFor();await button('Management').click();await button('Approve').waitFor();await shot('12-device-pending');await button('Approve').click();await verify();await page.getByLabel('Code shown on phone').fill(claim.data.verification.slice(0,4)+' '+claim.data.verification.slice(4));await shot('13-device-confirmation');await button('Approve phone').click();await page.locator('.admin-grid .item-row .item-sub').filter({hasText:/provisioning/}).waitFor();await shot('14-provisioning');
 await button('Clear workspace…').click();await verify();await page.getByRole('heading',{name:'Clear the entire workspace'}).waitFor();await shot('15-clear-workspace');await cancel();
 assert.equal((await f.request(`/api/enrollment/devices/${claim.data.id}/revoke`,{token:f.owner,body:{}})).status,200);
 const manifest=await readFile(join(f.data,'peers.json')),peers=JSON.parse(manifest).peers.map(({preshared_key,...p})=>p);
 await writeFile(f.peerStatus,JSON.stringify({version:1,digest:createHash('sha256').update(manifest).digest('hex'),applied_at:Math.floor(Date.now()/1000),peers,error:null}),{mode:0o600});
 await page.reload();await button('Management').click();await button('Remove inactive pairing').waitFor();await shot('31-inactive-pairing');
 await button('Remove inactive pairing').click();await verify();await page.getByRole('heading',{name:'Remove inactive pairing for Owner’s GrapheneOS phone',exact:true}).waitFor();await shot('32-remove-inactive-pairing');await cancel();
 await button('Tasks').click();await button('Open task: Book the boiler service').click();await button('Override claim').click();await verify();await page.getByLabel('Reason',{exact:true}).fill('Sam asked me to release this while away.');await shot('19-override-claim');await cancel();await button('Close').click();
 await button('Focus').click();await page.setViewportSize({width:390,height:844});await shot('16-phone-browser');assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(errors,[]);
 for(const name of old)if(name.endsWith('.png')&&!made.includes(name))await rm(join(out,name));
 console.log('Web gallery complete. All workspace data and invitations are discarded.');
}finally{await browser.close();await f?.close();}
