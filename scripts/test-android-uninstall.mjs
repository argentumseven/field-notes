// Destructive only for the dedicated disposable AVD; never a user's phone.
import {execFile,spawn} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdir,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
import {liveJobs} from './android-job-state.mjs';
const run=promisify(execFile),root=process.env.FIELD_NOTES_TOOLS||'/workspace/.cache/field-notes';
const adb=join(root,'android-sdk/platform-tools/adb'),serial=process.env.FIELD_NOTES_EMULATOR||'emulator-5554';
const call=async(args,timeout=180000)=>(await run(adb,['-s',serial,...args],{timeout,maxBuffer:4*1024*1024})).stdout;
if(!serial.startsWith('emulator-')||(await call(['shell','getprop','ro.boot.qemu.avd_name'])).trim()!=='field-notes-test')throw Error('Only the disposable field-notes-test AVD may be uninstalled.');
await mkdir('dist/android-test-results',{recursive:true});
const apk='dist/android-emulator/FieldNotes-debug.apk',tests='dist/android-emulator/FieldNotes-debug-androidTest.apk';
await call(['install','-r',apk]);await call(['install','-r',tests]);
await call(['shell','pm','grant','org.fieldnotes.app','android.permission.POST_NOTIFICATIONS']);
await call(['shell','appops','set','org.fieldnotes.app','SCHEDULE_EXACT_ALARM','allow']);
async function phase(name){
 const out=await call(['shell','am','instrument','-w','-e','class','org.fieldnotes.app.UninstallDeviceTest','-e','uninstallPhase',name,'org.fieldnotes.app.test/androidx.test.runner.AndroidJUnitRunner'],360000);
 await writeFile('dist/android-test-results/uninstall-'+name+'.txt',out);
 if(!/OK \(1 test\)/.test(out)||out.includes('FAILURES!!!'))throw Error(out);
}
await phase('before');
const {stdout:ciphertext}=await run(adb,['-s',serial,'exec-out','run-as','org.fieldnotes.app','cat','no_backup/vault-uninstall-probe/workspace.encrypted'],{encoding:'buffer'});
// Instrumentation ends by stopping its target. Reopen through the normal
// launcher activity so the cached event alarm and worker are genuinely live
// immediately before removal, rather than proving absence after a force-stop.
await call(['shell','am','start','-W','-n','org.fieldnotes.app/.MainActivity']);
let live=false;
for(let i=0;i<20;i++){
 const alarms=await call(['shell','dumpsys','alarm']),jobs=await call(['shell','dumpsys','jobscheduler']);
 if(alarms.includes('org.fieldnotes.ALERT')&&liveJobs(jobs)){
  await writeFile('dist/android-test-results/uninstall-before-alarm.txt',alarms);
  await writeFile('dist/android-test-results/uninstall-before-jobscheduler.txt',jobs);
  live=true;break;
 }
 await delay(1000);
}
if(!live)throw Error('Expected a live cached alarm and worker before uninstall.');
// No -k: exercise ordinary Android removal, including private data and keys.
if(!(await call(['uninstall','org.fieldnotes.app'])).includes('Success'))throw Error('Normal uninstall failed.');
const packages=await call(['shell','pm','list','packages','org.fieldnotes.app']);
if(packages.split('\n').some(p=>p.trim()==='package:org.fieldnotes.app'))throw Error('Application survived uninstall.');
let removed=false;
const deadline=Date.now()+90000;
while(Date.now()<deadline){
 let remaining=false;
 for(const service of ['alarm','jobscheduler','appwidget']){
  const dump=await call(['shell','dumpsys',service]);
  await writeFile('dist/android-test-results/uninstall-'+service+'.txt',dump);
  if(service==='jobscheduler'?liveJobs(dump):dump.includes('org.fieldnotes.app/'))remaining=true;
 }
 if(!remaining){removed=true;break;}
 await delay(1000);
}
if(!removed)throw Error('App-owned scheduled work or widget provider survived uninstall.');
const processes=await call(['shell','ps','-A','-o','ARGS']);
if(processes.split('\n').some(line=>line.trim()==='org.fieldnotes.app'))throw Error('Application process survived uninstall.');
await call(['install',apk]);
await call(['shell','run-as','org.fieldnotes.app','mkdir','-p','files']);
await new Promise((resolve,reject)=>{
 const child=spawn(adb,['-s',serial,'shell','-T',"run-as org.fieldnotes.app sh -c 'cat > files/old-uninstall-fixture.encrypted'"],{stdio:['pipe','ignore','pipe']});
 child.on('error',reject);child.on('close',code=>code===0?resolve():reject(Error('Fixture restore failed')));child.stdin.end(ciphertext);
});
await phase('after');
console.log('Normal uninstall removed the old profile key/state and scheduled work; reinstall could not decrypt the deliberately reintroduced old ciphertext.');
