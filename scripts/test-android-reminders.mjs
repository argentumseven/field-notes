// Destructive synthetic fixtures are restricted to the dedicated disposable AVD.
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
const run=promisify(execFile),tools=process.env.FIELD_NOTES_TOOLS||'/workspace/.cache/field-notes';
const adb=join(tools,'android-sdk/platform-tools/adb'),serial=process.env.FIELD_NOTES_EMULATOR||'emulator-5554';
const call=async(args,timeout=180000)=>(await run(adb,['-s',serial,...args],{timeout,maxBuffer:8*1024*1024})).stdout;
const dedupeOnly=process.argv.includes('--dedupe-only');
const editorOnly=process.argv.includes('--editor-only');
const coreOnly=process.argv.includes('--core-only');
const notificationOnly=process.argv.includes('--notification-only');
const calendarOnly=dedupeOnly||notificationOnly||editorOnly||process.argv.includes('--calendar-only');
if(!serial.startsWith('emulator-')||(await call(['shell','getprop','ro.boot.qemu.avd_name'])).trim()!=='field-notes-test')throw Error('Only the disposable field-notes-test AVD may run these fixtures.');
if(calendarOnly&&!dedupeOnly&&!/OK \(9 tests\)/.test(await readFile('dist/android-test-results/alarm-updates-details.txt','utf8')))throw Error('Run the alarm/update checks successfully before continuing just the calendar captures.');
if(editorOnly&&!/OK \(1 test\)/.test(await readFile('dist/android-test-results/week-launcher.txt','utf8')))throw Error('Run the launcher check before continuing just the editor capture.');
await mkdir('dist/android-test-results',{recursive:true});await mkdir('docs/screenshots/android',{recursive:true});
const screenTimeout=(await call(['shell','settings','get','system','screen_off_timeout'])).trim();
async function instrument(classes,name){
 const result=await call(['shell','am','instrument','-w','-e','class',classes.map(x=>'org.fieldnotes.app.'+x).join(','),'-e','fieldNotesCalendarGallery','synthetic-only','org.fieldnotes.app.test/androidx.test.runner.AndroidJUnitRunner'],900000);
 await writeFile('dist/android-test-results/'+name+'.txt',result);
 if(!/OK \(\d+ tests?\)/.test(result)||/FAILURES!!!|INSTRUMENTATION_FAILED/.test(result))throw Error(result);
 console.log(name+': '+result.match(/OK \(\d+ tests?\)/)[0]);
}
async function installCurrent(path,pkg){
 const wanted=createHash('sha256').update(await readFile(path)).digest('hex');
 const installed=(await call(['shell','pm','path',pkg]).catch(()=>'')).trim().match(/^package:(\/data\/app\/[A-Za-z0-9_~+=./-]+\/base\.apk)$/);
 const actual=installed?(await call(['shell','sha256sum',installed[1]])).split(/\s+/)[0]:'';
 if(actual!==wanted)await call(['install','-r',path]);
}
try{
 await call(['shell','settings','put','system','screen_off_timeout','2147483647']);
 await call(['shell','wm','size','540x1200']);await call(['shell','wm','density','210']);
 await call(['shell','input','keyevent','KEYCODE_WAKEUP']);await call(['shell','wm','dismiss-keyguard']);
 await call(['shell','input','keyevent','KEYCODE_HOME']);
 await installCurrent('dist/android-emulator/FieldNotes-debug.apk','org.fieldnotes.app');
 await installCurrent('dist/android-emulator/FieldNotes-debug-androidTest.apk','org.fieldnotes.app.test');
 if(dedupeOnly||!calendarOnly||notificationOnly)await call(['shell','pm','clear','org.fieldnotes.app']);
 await call(['shell','pm','grant','org.fieldnotes.app','android.permission.POST_NOTIFICATIONS']);
 await call(['shell','appops','set','org.fieldnotes.app','SCHEDULE_EXACT_ALARM','allow']);
 console.log('Preparing the dedicated AVD packages for device checks.');
 for(const pkg of ['org.fieldnotes.app','org.fieldnotes.app.test'])await call(['shell','cmd','package','compile','-m','speed',pkg],360000);
 if(dedupeOnly)await instrument(['NotificationDeduplicationDeviceTest'],'notification-deduplication');
 else if(notificationOnly)await instrument(['ReminderDeliveryDeviceTest'],'detailed-notification-shade');
 else if(!calendarOnly)await instrument(['AlarmExperienceDeviceTest','UpdateDeviceTest','ReminderDeliveryDeviceTest','WidgetDeviceTest'],'alarm-updates-details');
 if(!dedupeOnly&&!coreOnly&&!notificationOnly){
  if(!editorOnly){await call(['shell','pm','clear','com.android.launcher3']);await instrument(['CalendarWidgetHostDeviceTest#weekIsHostedRepaintsOfflineAndOpensTheChosenDate'],'week-launcher');}
  await instrument(['CalendarExperienceDeviceTest'],'calendar-visibility');
 }
 for(const name of dedupeOnly?[]:coreOnly||notificationOnly?['43-detailed-notifications']:['42-home-week','43-detailed-notifications','44-long-alarm-controls','45-independent-visibility']){
  const {stdout}=await run(adb,['-s',serial,'exec-out','run-as','org.fieldnotes.app','cat','files/calendar-gallery/'+name+'.png'],{encoding:'buffer',maxBuffer:8*1024*1024});
  await writeFile('docs/screenshots/android/'+name+'.png',stdout);
 }
}finally{
 await call(['shell','am','force-stop','org.fieldnotes.app']).catch(()=>{});
 await call(['shell','wm','size','reset']).catch(()=>{});await call(['shell','wm','density','reset']).catch(()=>{});
 await call(['shell','settings',screenTimeout==='null'?'delete':'put','system','screen_off_timeout',...(screenTimeout==='null'?[]:[screenTimeout])]).catch(()=>{});
}
