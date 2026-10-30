// Disposable loopback-only development fixture. Synthetic credentials are in
// tests/support.mjs; no production account or persistent workspace is created.
import {fixture} from '../tests/support.mjs';
const f=await fixture();
console.log('Disposable Field Notes fixture started. Data is removed on exit.');
console.log('Admin backend:',f.admin,'Device backend:',f.device);
console.log('Synthetic test credentials are defined in tests/support.mjs.');
let stopping=false;
async function stop(){if(stopping)return;stopping=true;await f.close();process.exit(0);}
process.once('SIGINT',stop);process.once('SIGTERM',stop);
if(process.argv.includes('--smoke')){
 const token=await f.login();const r=await f.request('/api/snapshot',{token});
 if(r.status!==200||r.data.protocol!==1)throw Error('Fixture readiness failed');
 console.log('Authenticated fixture readiness passed.');await stop();
}
await new Promise(()=>{});
