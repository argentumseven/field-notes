// Actual launcher hosting and Android delivery, only on the disposable AVD.
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {join} from 'node:path';
const run=promisify(execFile),root=process.env.FIELD_NOTES_TOOLS||'/workspace/.cache/field-notes';
const adb=join(root,'android-sdk/platform-tools/adb'),serial=process.env.FIELD_NOTES_EMULATOR||'emulator-5554';
const deliveryOnly=process.argv.includes('--delivery-only');
const call=async(args,timeout=180000)=>(await run(adb,['-s',serial,...args],{timeout,maxBuffer:6*1024*1024})).stdout;
if(!serial.startsWith('emulator-')||(await call(['shell','getprop','ro.boot.qemu.avd_name'])).trim()!=='field-notes-test')throw Error('Only the disposable field-notes-test AVD may be cleared.');
const screenTimeout=(await call(['shell','settings','get','system','screen_off_timeout'])).trim();
await mkdir('dist/android-test-results',{recursive:true});await mkdir('docs/screenshots/android',{recursive:true});
async function instrument(name,extra=[]){
 const output=await call(['shell','am','instrument','-w','-e','class','org.fieldnotes.app.'+name,...extra,'org.fieldnotes.app.test/androidx.test.runner.AndroidJUnitRunner'],600000);
 await writeFile('dist/android-test-results/'+name+'.txt',output);
 if(!/OK \(1 test\)/.test(output)||output.includes('FAILURES!!!'))throw Error(output);
}
async function installCurrent(path,pkg){
 const wanted=createHash('sha256').update(await readFile(path)).digest('hex');
 const installed=(await call(['shell','pm','path',pkg])).trim().match(/^package:(\/data\/app\/[A-Za-z0-9_~+=./-]+\/base\.apk)$/);
 const actual=installed?(await call(['shell','sha256sum',installed[1]])).split(/\s+/)[0]:'';
 // Reinstall only changed APK bytes. Replacing an identical APK invalidates
 // expensive AOT compilation on the software-only documentation emulator.
 if(actual!==wanted)await call(['install','-r',path]);
}
try{
 await call(['shell','settings','put','system','screen_off_timeout','2147483647']);
 await call(['shell','wm','size','540x1200']);await call(['shell','wm','density','210']);
 await call(['shell','input','keyevent','KEYCODE_WAKEUP']);await call(['shell','wm','dismiss-keyguard']);
 await installCurrent('dist/android-emulator/FieldNotes-debug.apk','org.fieldnotes.app');
 await installCurrent('dist/android-emulator/FieldNotes-debug-androidTest.apk','org.fieldnotes.app.test');
 await call(['shell','pm','clear','org.fieldnotes.app']);
 // Clear only this disposable AVD's launcher so repeated runs don't accumulate
 // old synthetic widgets on different pages.
 if(!deliveryOnly)await call(['shell','pm','clear','com.android.launcher3']);
 for(const pkg of ['org.fieldnotes.app','org.fieldnotes.app.test'])await call(['shell','cmd','package','compile','-m','speed',pkg],360000);
 if(!deliveryOnly){
 await instrument('HomeWidgetDeviceTest',['-e','fieldNotesHomeGallery','synthetic-only']);
 await call(['shell','input','keyevent','KEYCODE_HOME']);
 let visible=false;
 for(let i=0;i<6;i++){
  await call(['shell','uiautomator','dump','/data/local/tmp/field-notes-home.xml'],60000);
  const xml=await call(['shell','cat','/data/local/tmp/field-notes-home.xml']);
  await writeFile('dist/android-test-results/home-launcher.xml',xml);
  if(xml.includes('package="com.android.launcher3"')&&xml.includes('content-desc="Field Notes · Upcoming"')&&xml.includes('Household check-in')){visible=true;break;}
  await call(['shell','input','swipe','490','500','45','500','250']);
 }
 if(!visible)throw Error('Actual launcher did not expose the bound synthetic Upcoming content.');
 const {stdout:home}=await run(adb,['-s',serial,'exec-out','screencap','-p'],{encoding:'buffer',maxBuffer:8*1024*1024});
 await writeFile('docs/screenshots/android/22-home-upcoming-widget.png',home);
 console.log('Actual launcher hosting and visible Upcoming content passed.');
 }
 await instrument('DeliveryDeviceTest');
 const {stdout:notifications}=await run(adb,['-s',serial,'exec-out','run-as','org.fieldnotes.app','cat','files/gallery/23-android-notifications.png'],{encoding:'buffer',maxBuffer:8*1024*1024});
 await writeFile('docs/screenshots/android/23-android-notifications.png',notifications);
 console.log('Foreground service, cached alarm, private audible channel and delayed profile test passed.');
}finally{
 await call(['shell','pm','clear','org.fieldnotes.app']).catch(()=>{});
 await call(['shell','wm','size','reset']).catch(()=>{});await call(['shell','wm','density','reset']).catch(()=>{});
 await call(['shell','settings',screenTimeout==='null'?'delete':'put','system','screen_off_timeout',...(screenTimeout==='null'?[]:[screenTimeout])]).catch(()=>{});
}
