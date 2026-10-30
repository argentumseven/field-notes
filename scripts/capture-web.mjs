// Actual Chromium screenshots of a disposable Rust-backed demo workspace.
import {chromium} from 'playwright';
import {mkdir} from 'node:fs/promises';
import {join} from 'node:path';
import assert from 'node:assert/strict';
import {password} from '../tests/support.mjs';
import {demo,illustration} from './screenshots/demo.mjs';

const out='docs/screenshots/web';
await mkdir(out,{recursive:true});
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
let f;
try{
 f=await demo(await illustration(browser));
 const page=await browser.newPage({viewport:{width:1440,height:1050},colorScheme:'dark',locale:'en-GB',timezoneId:'UTC',deviceScaleFactor:1});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const shot=async name=>{await page.locator('#notice.visible').waitFor({state:'hidden',timeout:10000});await page.screenshot({path:join(out,name+'.png'),fullPage:true,animations:'disabled'});console.log('Captured web/'+name);};
 const button=name=>page.getByRole('button',{name,exact:true});
 const cancel=async()=>{await button('Cancel').click();await page.locator('#dialog').waitFor({state:'hidden'});};
 const verify=async()=>{await page.getByRole('heading',{name:'Confirm it’s you',exact:true}).waitFor();await page.getByLabel('Password',{exact:true}).fill(password);await button('Verify').click();};
 await page.goto(f.admin);await page.getByRole('heading',{name:'Welcome home.'}).waitFor();await shot('01-sign-in');
 await page.getByLabel('User name',{exact:true}).fill('owner');await page.getByLabel('Password',{exact:true}).fill(password);await button('Sign in').click();
 await page.getByRole('button',{name:'Open task: Plan the kitchen shelves',exact:true}).waitFor();await shot('02-matrix');
 await button('Board').click();await shot('03-board');
 await button('List').click();await button('Change theme').click();await shot('04-list-light');await button('Change theme').click();
 await button('Open task: Plan the kitchen shelves').click();
 await page.locator('.image-grid img').evaluateAll(async images=>{await Promise.all(images.map(i=>i.complete&&i.naturalWidth?Promise.resolve():new Promise(resolve=>{i.onload=resolve;i.onerror=resolve;})));});
 await shot('05-task-details');await page.locator('.detail').evaluate(n=>n.scrollTop=n.scrollHeight);await shot('06-images-comments');
 await button('Close').click();
 await button('+').click();await page.getByLabel('Project name',{exact:true}).fill('A place for new ideas');await shot('07-new-project');await cancel();
 await page.getByRole('button',{name:/Administration/}).click();await page.getByRole('heading',{name:'Administrative activity',exact:true}).waitFor();await shot('08-administration');
 await button('+ Add person').click();await shot('09-confirm-identity');await verify();
 await page.getByRole('heading',{name:'Add a household member',exact:true}).waitFor();await page.getByLabel('Name',{exact:true}).fill('Taylor');await page.getByLabel('Role',{exact:true}).selectOption('member');await shot('10-add-person');await cancel();
 const samRow=page.locator('.item-row').filter({has:page.getByText('Sam',{exact:true})});await samRow.getByRole('button',{name:'Change role',exact:true}).click();await verify();
 await page.getByRole('heading',{name:'Change role for Sam',exact:true}).waitFor();await page.getByLabel('New role',{exact:true}).selectOption('admin');await shot('11-change-role');await cancel();
 await button('Clear workspace…').click();await verify();await page.getByRole('heading',{name:'Clear the entire workspace',exact:true}).waitFor();await shot('12-clear-workspace');await cancel();
 await page.getByRole('button',{name:/All tasks/}).click();await button('Open task: Book the boiler service').click();await button('Override claim').click();await verify();
 await page.getByRole('heading',{name:'Release this claim',exact:true}).waitFor();await page.getByLabel('Reason',{exact:true}).fill('Sam asked me to hand this task back while away.');await shot('13-override-claim');await cancel();await button('Close').click();
 await button('Matrix').click();await page.setViewportSize({width:390,height:844});await shot('14-phone-browser');
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 assert.deepEqual(errors,[]);
 console.log('Web gallery complete; disposable data removed on exit.');
}finally{await browser.close();await f?.close();}
