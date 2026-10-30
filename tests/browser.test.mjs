import {test} from 'node:test';
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {mkdir} from 'node:fs/promises';
import {fixture,password} from './support.mjs';
test('web workflow: sign in, project, task details, comment, board, admin and mobile layout',async()=>{
 const f=await fixture();let browser;
 try{
  browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
  const page=await browser.newPage({viewport:{width:1440,height:1050},colorScheme:'dark'});const errors=[];const external=[];
  page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(!r.url().startsWith(f.admin)&&!r.url().startsWith('blob:'))external.push(r.url());});
  await page.goto(f.admin);await page.getByLabel('User name',{exact:true}).fill('owner');await page.getByLabel('Password',{exact:true}).fill(password);await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await page.getByRole('button',{name:'+ Create a project',exact:true}).click();await page.getByLabel('Project name',{exact:true}).fill('Home & garden');await page.getByRole('button',{name:'Create project',exact:true}).click();
  await page.getByLabel('New task title',{exact:true}).fill('Plan the kitchen shelves');await page.getByRole('button',{name:'+ Add task',exact:true}).click();
  await page.getByRole('button',{name:'Open task: Plan the kitchen shelves',exact:true}).click();await page.getByLabel('Notes',{exact:true}).fill('Measure the alcove before buying timber.');await page.getByRole('button',{name:'Save details',exact:true}).click();await page.getByText('Details saved.',{exact:true}).waitFor();
  await page.getByLabel('Add a comment',{exact:true}).fill('I can measure this on Saturday.');await page.getByRole('button',{name:'Post comment',exact:true}).click();await page.getByText('I can measure this on Saturday.',{exact:true}).waitFor();
  await page.getByRole('button',{name:'Start task',exact:true}).click();await page.getByText('Currently claimed by owner',{exact:true}).waitFor();await page.getByRole('button',{name:'Close',exact:true}).click();
  for(const title of ['Order herb seedlings','Book a boiler service','Choose a reading lamp']){await page.getByLabel('New task title',{exact:true}).fill(title);await page.getByRole('button',{name:'+ Add task',exact:true}).click();await page.getByRole('button',{name:'Open task: '+title,exact:true}).waitFor();}
  await mkdir('dist/screenshots',{recursive:true});await page.screenshot({path:'dist/screenshots/workspace.png',fullPage:true});
  await page.getByRole('button',{name:'Board',exact:true}).click();await page.getByRole('heading',{name:'In progress',exact:true}).waitFor();
  await page.getByRole('button',{name:/Administration/}).click();await page.getByRole('heading',{name:'People',exact:true}).waitFor();await page.getByRole('heading',{name:'Devices & sessions',exact:true}).waitFor();await page.screenshot({path:'dist/screenshots/admin.png',fullPage:true});
  await page.getByRole('button',{name:/All tasks/}).click();await page.setViewportSize({width:390,height:844});await page.screenshot({path:'dist/screenshots/mobile.png',fullPage:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'mobile layout should not overflow');assert.deepEqual(errors,[]);assert.deepEqual(external,[]);
 }finally{await browser?.close();await f.close();}
});
