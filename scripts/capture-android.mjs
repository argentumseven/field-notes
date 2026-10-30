// Capture the actual Android UI using only the dedicated disposable test AVD.
import {execFile,spawn} from 'node:child_process';
import {promisify} from 'node:util';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
import {chromium} from 'playwright';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {demo,illustration} from './screenshots/demo.mjs';
const run=promisify(execFile);
const tools=process.env.FIELD_NOTES_TOOLS||'/workspace/.cache/field-notes';
const adb=join(tools,'android-sdk/platform-tools/adb');
const serial=process.env.FIELD_NOTES_EMULATOR||'emulator-5554';
const imageOnly=process.argv.includes('--image-only');
if(!serial.startsWith('emulator-'))throw Error('Only a disposable emulator is supported.');
async function call(args,timeout=120000){return(await run(adb,['-s',serial,...args],{timeout,maxBuffer:6*1024*1024})).stdout;}
const avd=(await call(['shell','getprop','ro.boot.qemu.avd_name'])).trim()||(await call(['shell','getprop','ro.kernel.qemu.avd_name'])).trim();
if(avd!=='field-notes-test')throw Error('Use the dedicated field-notes-test AVD. User devices are not modified.');
const output='docs/screenshots/android';await mkdir(output,{recursive:true});
await call(['shell','wm','size','540x1200']);await call(['shell','wm','density','210']);
await call(['shell','cmd','uimode','night','yes']);
await call(['shell','input','keyevent','KEYCODE_WAKEUP']);
await call(['shell','wm','dismiss-keyguard']);
await call(['install','-r','apps/mobile/build/outputs/apk/debug/FieldNotes-debug.apk']);
await call(['install','-r','apps/mobile/build/outputs/apk/androidTest/debug/FieldNotes-debug-androidTest.apk']);
if(!imageOnly)await call(['shell','pm','clear','org.fieldnotes.app']);
for(const pkg of ['org.fieldnotes.app','org.fieldnotes.app.test']){
 const result=await call(['shell','cmd','package','compile','-m','speed',pkg],360000);if(!result.includes('Success'))throw Error('Emulator AOT preparation failed.');
}
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
let f,hub;
try{
 f=await demo(await illustration(browser));await browser.close();
 const configFile=join(f.root,'gallery-connection.json'),peerFile=join(f.root,'gallery-peer');
 hub=spawn(resolve('dist/testhub'),['--backend',f.device,'--peer-file',peerFile,'--config-file',configFile],{stdio:['ignore','ignore','pipe']});
 let config;for(let i=0;i<100;i++){try{config=await readFile(configFile);break;}catch{}await delay(50);}if(!config)throw Error('Test hub did not start.');
 const snapshot=(await f.request('/api/snapshot',{token:f.owner})).data;
 const stale=await f.request('/api/commands',{token:f.owner,body:{operation_id:randomUUID(),epoch:snapshot.epoch,generation:snapshot.generation,action:'task.update',target:f.ids.shelves,expected_revision:0,data:{title:'A stale edit'}}});
 assert.equal(stale.status,409);
 const image=snapshot.records.find(r=>r.kind==='image');
 const imageBytes=await f.request('/api/images/'+image.id,{token:f.owner});assert.equal(imageBytes.status,200);
 // Real authorized server snapshot/image and a real stale response. The test
 // connection renders them deterministically; this is not a tunnel test.
 const uiFixture=Buffer.from(JSON.stringify({snapshot,image:imageBytes.data.toString('base64'),stale_error:stale.data})).toString('base64');
 for(const mode of imageOnly?['image']:['dark','light']){
  if(mode==='light')await call(['shell','cmd','uimode','night','no']);
  await call(['shell','input','keyevent','KEYCODE_WAKEUP']);
  console.log('Capturing Android '+mode+' UI.');
  const result=await call(['shell','am','instrument','-w','-e','class','org.fieldnotes.app.GalleryDeviceTest','-e','fieldNotesGallery','synthetic-only','-e','galleryMode',mode,'-e','fieldNotesConfig',config.toString('base64'),'-e','fieldNotesFixture',uiFixture,'org.fieldnotes.app.test/androidx.test.runner.AndroidJUnitRunner'],1200000);
  await mkdir('dist/android-test-results',{recursive:true});await writeFile(`dist/android-test-results/gallery-${mode}.txt`,result);
  if(!/OK \(1 test\)/.test(result)||result.includes('FAILURES!!!'))throw Error(result);
 }
 const names=(await call(['shell','run-as','org.fieldnotes.app','ls','files/gallery'])).trim().split(/\s+/);
 if(names.length!==17||names.some(n=>!/^\d\d-[a-z-]+\.png$/.test(n)))throw Error('Incomplete gallery: '+names.join(','));
 for(const name of names){const {stdout}=await run(adb,['-s',serial,'exec-out','run-as','org.fieldnotes.app','cat','files/gallery/'+name],{encoding:'buffer',maxBuffer:8*1024*1024});await writeFile(join(output,name),stdout);}
 console.log('Captured '+names.length+' Android screenshots.');
}finally{
 hub?.kill('SIGTERM');await browser.close();await f?.close();
 await call(['shell','wm','size','reset']).catch(()=>{});await call(['shell','wm','density','reset']).catch(()=>{});
}
