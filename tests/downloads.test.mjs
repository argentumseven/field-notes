import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,rm,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {fixture} from './support.mjs';

test('LAN serves fixed APK/checksum/signer downloads without exposing household data or device routes',async()=>{
 const root=await mkdtemp(join(tmpdir(),'field-notes-downloads-'));let f;
 try{
  const release=JSON.parse(await readFile('release.json','utf8'));
  const apk=Buffer.from('PK synthetic binary download fixture; signature is verified separately by packaging');
  const digest=createHash('sha256').update(apk).digest('hex');
  const info={version:release.version,version_code:release.android_version_code,architecture:'arm64-v8a',signing:'development',sha256:digest,signer_sha256:'a'.repeat(64),source_commit:null,source_dirty:true};
  await writeFile(join(root,'field-notes.apk'),apk);await writeFile(join(root,'android.json'),JSON.stringify(info));
  await writeFile(join(root,'field-notes.apk.sha256'),digest+'  field-notes.apk\n');await writeFile(join(root,'field-notes.apk.signer.txt'),'synthetic signer report');
  f=await fixture({downloads:root});
  const metadata=await f.request('/downloads/android');assert.equal(metadata.status,200);assert.equal(metadata.data.available,true);assert.deepEqual(metadata.data.artifact,info);
  const download=await f.request('/downloads/field-notes.apk');assert.equal(download.status,200);assert.deepEqual(download.data,apk);
  assert.equal(download.headers.get('content-type'),'application/vnd.android.package-archive');assert.match(download.headers.get('content-disposition'),/attachment; filename="field-notes.apk"/);
  assert.equal(Number(download.headers.get('content-length')),apk.length);assert.equal(download.headers.get('cache-control'),'no-store');
  assert.equal((await f.request('/downloads/field-notes.apk.sha256')).data.toString(),digest+'  field-notes.apk\n');
  for(const path of ['/downloads/android','/downloads/field-notes.apk'])assert.equal((await f.request(path,{base:f.device})).status,404);
  for(const path of ['/downloads/master.key','/downloads/%2e%2e/master.key','/downloads/android.json'])assert.equal((await f.request(path)).status,404);
  await f.close();f=null;await writeFile(join(root,'field-notes.apk'),'tampered binary');
  f=await fixture({downloads:root});
  assert.deepEqual((await f.request('/downloads/android')).data,{available:false});
  assert.equal((await f.request('/downloads/field-notes.apk')).status,404);
  const owner=await f.login();assert.equal((await f.request('/api/snapshot',{token:owner})).status,200,'invalid APK disables only downloads, not household access');
 }finally{await f?.close();await rm(root,{recursive:true,force:true})}
});

test('development servers without a packaged APK advertise its absence',async()=>{
 const f=await fixture({downloads:null});try{
  assert.deepEqual((await f.request('/downloads/android')).data,{available:false});
  assert.equal((await f.request('/downloads/field-notes.apk')).status,404);
 }finally{await f.close()}
});

test('private APK chunks require authentication, bind the digest and reassemble exact bounded bytes',async()=>{
 const root=await mkdtemp(join(tmpdir(),'field-notes-update-'));let f;
 try{
  const release=JSON.parse(await readFile('release.json','utf8')),apk=Buffer.alloc(512*1024+17,137),digest=createHash('sha256').update(apk).digest('hex');
  const info={version:release.version,version_code:release.android_version_code,architecture:'arm64-v8a',signing:'development',sha256:digest,signer_sha256:'a'.repeat(64),source_commit:null,source_dirty:true};
  await writeFile(join(root,'field-notes.apk'),apk);await writeFile(join(root,'android.json'),JSON.stringify(info));
  f=await fixture({downloads:root});
  assert.equal((await f.request('/api/device/update',{base:f.device})).status,401);
  assert.equal((await f.request('/api/device/update')).status,404);
  const token=await f.login();const offered=await f.request('/api/device/update',{base:f.device,token});
  assert.equal(offered.status,200);assert.deepEqual(offered.data.artifact,info);assert.equal(offered.data.size,apk.length);assert.equal(offered.data.chunk_size,256*1024);
  const chunks=[];
  for(let i=0;i<3;i++){
   const part=await f.request(`/api/device/update/chunks/${digest}/${i}`,{base:f.device,token});assert.equal(part.status,200);assert(part.data.length<=256*1024);assert.equal(part.headers.get('content-type'),'application/octet-stream');chunks.push(part.data);
  }
  assert.deepEqual(Buffer.concat(chunks),apk);
  assert.equal((await f.request(`/api/device/update/chunks/${'0'.repeat(64)}/0`,{base:f.device,token})).status,409);
  assert.equal((await f.request(`/api/device/update/chunks/${digest}/3`,{base:f.device,token})).status,404);
  assert.equal((await f.request(`/api/device/update/chunks/${digest}/18446744073709551615`,{base:f.device,token})).status,404);
  const actor=(await f.request('/api/session',{base:f.device,token})).data.actor;
  await f.request('/api/reauth',{token,body:{password:'household-test-password-only'}});
  assert.equal((await f.request('/api/admin',{token,body:{action:'session.revoke',target:actor.session_id}})).status,200);
  assert.equal((await f.request(`/api/device/update/chunks/${digest}/0`,{base:f.device,token})).status,401);
 }finally{await f?.close();await rm(root,{recursive:true,force:true})}
});
