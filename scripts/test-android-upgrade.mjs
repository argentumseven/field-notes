// Installs two same-signer APKs on ONLY the disposable test AVD, retaining app data.
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {join} from 'node:path';
const run=promisify(execFile),tools=process.env.FIELD_NOTES_TOOLS||'/workspace/.cache/field-notes',adb=join(tools,'android-sdk/platform-tools/adb');
const serial=process.env.FIELD_NOTES_EMULATOR||'emulator-5554';
if(!serial.startsWith('emulator-'))throw Error('Only the disposable AVD may run this upgrade check');
async function call(args,timeout=120000){return(await run(adb,['-s',serial,...args],{timeout,maxBuffer:8*1024*1024})).stdout}
const avd=((await call(['shell','getprop','ro.boot.qemu.avd_name'])).trim()||(await call(['shell','getprop','ro.kernel.qemu.avd_name'])).trim());if(avd!=='field-notes-test')throw Error('Use ONLY field-notes-test; user phones are never modified');
const old=process.env.FIELD_NOTES_PREVIOUS_APK||'dist/android/field-notes-0.3.2-android-x86_64-debug.apk',current='dist/android-emulator/FieldNotes-debug.apk';
await readFile(old);await readFile(current);
const packageInfo=async file=>{
 const text=(await run(join(tools,'android-sdk/build-tools/35.0.0/aapt'),['dump','badging',file])).stdout;
 const match=text.match(/^package: name='org\.fieldnotes\.app' versionCode='(\d+)' versionName='([^']+)'/m);
 if(!match)throw Error('Upgrade fixture APK identity is invalid');
 return {code:Number(match[1]),version:match[2]};
};
const before=await packageInfo(old),after=await packageInfo(current);
if(after.code<=before.code)throw Error('Upgrade fixture requires a strictly higher Android version code');
console.log(`Checking ${before.version} (code ${before.code}) → ${after.version} (code ${after.code}).`);
const signer=async file=>(await run(join(tools,'android-sdk/build-tools/35.0.0/apksigner'),['verify','--print-certs',file])).stdout.match(/^Signer #1 certificate SHA-256 digest: (.+)$/m)?.[1];
if(!await signer(old)||await signer(old)!==await signer(current))throw Error('Upgrade test needs two APKs with the SAME signer');
// This deliberate reset applies only before the seed on the guarded disposable AVD.
await call(['uninstall','org.fieldnotes.app']).catch(()=>{});
await call(['install',old]);
await call(['install','-r','dist/android-emulator/FieldNotes-debug-androidTest.apk']);
async function compileFixture(){
 for(const pkg of ['org.fieldnotes.app','org.fieldnotes.app.test']){
  const result=await call(['shell','cmd','package','compile','-m','speed',pkg],360000);
  if(!result.includes('Success'))throw Error('Emulator AOT preparation failed');
 }
}
async function phase(name){const result=await call(['shell','am','instrument','-w','-e','class','org.fieldnotes.app.UpgradeDeviceTest','-e','upgradePhase',name,'org.fieldnotes.app.test/androidx.test.runner.AndroidJUnitRunner'],360000);if(!/OK \(1 test\)/.test(result)||result.includes('FAILURES!!!'))throw Error(result);return result}
await compileFixture();
const first=await phase('seed');
await call(['install','-r',current]); // No clear, uninstall, enrollment, key reset or data copy here.
await compileFixture();
const second=await phase('verify');
await mkdir('dist/android-test-results',{recursive:true});await writeFile('dist/android-test-results/upgrade.txt',first+'\n'+second);
console.log('Same-signer APK update preserved the actual Keystore-backed phone keys, saved server binding and unacknowledged task command. No re-enrollment or pairing purge was performed.');
