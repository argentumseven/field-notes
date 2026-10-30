// Integration tests only on our disposable AVD; never clear a physical phone.
import {execFile,spawn} from 'node:child_process';
import {promisify} from 'node:util';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
import {fixture} from '../tests/support.mjs';
const run=promisify(execFile);
const tools=process.env.FIELD_NOTES_TOOLS||'/workspace/.cache/field-notes';
const adb=join(tools,'android-sdk/platform-tools/adb');
const serial=process.env.FIELD_NOTES_EMULATOR||'emulator-5554';
if(!serial.startsWith('emulator-'))throw Error('Only a disposable emulator is supported by this destructive test fixture.');
async function call(args,timeout=120000){return (await run(adb,['-s',serial,...args],{timeout,maxBuffer:4*1024*1024})).stdout;}
const avd=(await call(['shell','getprop','ro.boot.qemu.avd_name'])).trim()||(await call(['shell','getprop','ro.kernel.qemu.avd_name'])).trim();
if(avd!=='field-notes-test')throw Error('Use the dedicated field-notes-test AVD. Existing user devices are not cleared.');
await mkdir('dist/android-test-results',{recursive:true});
await call(['install','-r','apps/mobile/build/outputs/apk/debug/FieldNotes-debug.apk']);
await call(['install','-r','apps/mobile/build/outputs/apk/androidTest/debug/FieldNotes-debug-androidTest.apk']);
await call(['shell','pm','clear','org.fieldnotes.app']);
// AOT compilation avoids software-emulation cold-start timeouts. The APK,
// Android checks, and test assertions are unchanged.
for(const pkg of ['org.fieldnotes.app','org.fieldnotes.app.test']){const out=await call(['shell','cmd','package','compile','-m','speed','-f',pkg],360000);if(!out.includes('Success'))throw Error('Emulator AOT preparation failed: '+out);}
async function instrument(classes,extra=[],report){
 const output=await call(['shell','am','instrument','-w','-e','class',classes,...extra,'org.fieldnotes.app.test/androidx.test.runner.AndroidJUnitRunner'],360000);
 if(report)await writeFile(join('dist/android-test-results',report),output);
 if(!/OK \(\d+ tests?\)/.test(output)||output.includes('FAILURES!!!')||output.includes('INSTRUMENTATION_FAILED'))throw Error(output);
 return output;
}
console.log('Running Android Keystore and first-launch UI tests.');
const first=await instrument('org.fieldnotes.app.VaultDeviceTest,org.fieldnotes.app.InterfaceDeviceTest,org.fieldnotes.app.EditorDeviceTest',[],'vault-ui.txt');
await writeFile('dist/android-test-results/vault-ui.txt',first);
const f=await fixture();let hub;
try{
 const configFile=join(f.root,'public-connection.json'),peerFile=join(f.root,'peer-public-key');
 hub=spawn(resolve('dist/testhub'),['--backend',f.device,'--peer-file',peerFile,'--config-file',configFile],{stdio:['ignore','ignore','pipe']});
 let config;
 for(let n=0;n<100;n++){try{config=await readFile(configFile);break;}catch{}await delay(50);}
 if(!config)throw Error('Test hub did not become ready.');
 let done=false;
 const monitor=(async()=>{while(!done){try{const key=(await call(['shell','run-as','org.fieldnotes.app','cat','files/native-test-public.txt'],3000)).trim();if(/^[A-Za-z0-9+/]{43}=$/.test(key))await writeFile(peerFile,key,{mode:0o600});}catch{}await delay(150);}})();
 let second;
 try{console.log('Running real Android JNI → WireGuard → TLS → Rust integration.');second=await instrument('org.fieldnotes.app.NativeDeviceTest',['-e','fieldNotesConfig',config.toString('base64')],'native.txt');}
 finally{done=true;await monitor;}
 await writeFile('dist/android-test-results/native.txt',second);
 console.log('Android emulator: 4 vault tests, 2 Compose UI tests, 2 native/manifest integration tests passed.');
}finally{hub?.kill('SIGTERM');await f.close();}
