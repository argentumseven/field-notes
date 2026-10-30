// Runs the actual Android/JVM JNI adapter through WireGuard/TLS to the Rust API.
import {spawn} from 'node:child_process';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
import {fixture} from '../tests/support.mjs';
const f=await fixture();let hub;
try{
 const config=join(f.root,'public-config.json'),peer=join(f.root,'public-peer');
 hub=spawn(resolve('dist/testhub'),['--backend',f.device,'--client-host','127.0.0.1','--peer-file',peer,'--config-file',config],{stdio:['ignore','ignore','pipe']});
 let ready=false;for(let n=0;n<100;n++){try{await readFile(config);ready=true;break;}catch{}await delay(50);}
 if(!ready)throw Error('JNI test hub did not start');
 const child=spawn('bash',['scripts/android.sh','nativeIntegrationTest','--rerun-tasks'],{stdio:'inherit',env:{...process.env,FIELD_NOTES_JNI_CONFIG:config,FIELD_NOTES_JNI_PEER_FILE:peer}});
 const code=await new Promise(r=>child.once('exit',r));if(code!==0)throw Error('JNI integration test failed with status '+code);
 await mkdir('dist/android-test-results',{recursive:true});
 const xml=await readFile('apps/mobile/build/test-results/nativeIntegrationTest/TEST-org.fieldnotes.integration.NativeStackTest.xml');
 await writeFile('dist/android-test-results/jni-integration.xml',xml);
 console.log('Actual JNI, WireGuard, TLS, server Unicode and claim authority checks passed.');
}finally{hub?.kill('SIGTERM');await f.close();}
