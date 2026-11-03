// Use after capture-android.mjs has installed the current emulator APKs.
// This fixture clears only the dedicated disposable app, never a user phone.
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdir,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
const run=promisify(execFile),root=process.env.FIELD_NOTES_TOOLS||'/workspace/.cache/field-notes';
const adb=join(root,'android-sdk/platform-tools/adb'),serial=process.env.FIELD_NOTES_EMULATOR||'emulator-5554';
const call=async(args,timeout=180000)=>(await run(adb,['-s',serial,...args],{timeout,maxBuffer:6*1024*1024})).stdout;
if(!serial.startsWith('emulator-')||(await call(['shell','getprop','ro.boot.qemu.avd_name'])).trim()!=='field-notes-test')throw Error('Only the disposable field-notes-test AVD may be cleared.');
const screenTimeout=(await call(['shell','settings','get','system','screen_off_timeout'])).trim();
await mkdir('dist/android-test-results',{recursive:true});
try {
 await call(['shell','settings','put','system','screen_off_timeout','2147483647']);
 await call(['shell','wm','size','540x1200']);await call(['shell','wm','density','210']);
 await call(['shell','settings','put','secure','show_ime_with_hard_keyboard','1']);
 await call(['shell','input','keyevent','KEYCODE_WAKEUP']);await call(['shell','wm','dismiss-keyguard']);
 await call(['shell','pm','clear','org.fieldnotes.app']);
 const output=await call(['shell','am','instrument','-w','-e','class','org.fieldnotes.app.EnrollmentDeviceTest#phoneNameTrustAndRequestStayAboveTheRealKeyboard','-e','fieldNotesEnrollmentCapture','synthetic-only','org.fieldnotes.app.test/androidx.test.runner.AndroidJUnitRunner'],600000);
 await writeFile('dist/android-test-results/enrollment-keyboard.txt',output);
 if(!/OK \(1 test\)/.test(output)||output.includes('FAILURES!!!'))throw Error(output);
 const {stdout}=await run(adb,['-s',serial,'exec-out','run-as','org.fieldnotes.app','cat','files/enrollment-keyboard.png'],{encoding:'buffer',maxBuffer:8*1024*1024});
 if(!stdout.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])))throw Error('Missing Android keyboard PNG.');
 await mkdir('docs/screenshots/android',{recursive:true});await writeFile('docs/screenshots/android/29-enrollment-keyboard.png',stdout);
 console.log('Real keyboard leaves the phone name, trust and enrollment action visible; current capture saved.');
} finally {
 await call(['shell','pm','clear','org.fieldnotes.app']).catch(()=>{});
 await call(['shell','wm','size','reset']).catch(()=>{});await call(['shell','wm','density','reset']).catch(()=>{});
 await call(['shell','settings',screenTimeout==='null'?'delete':'put','system','screen_off_timeout',...(screenTimeout==='null'?[]:[screenTimeout])]).catch(()=>{});
}
