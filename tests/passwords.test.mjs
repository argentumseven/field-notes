import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { fixture, password } from './support.mjs';

const replacement = 'replacement-test-password-only';
const admin = (f, token, body) => f.request('/api/admin', { token, body });
const change = (f, token, current_password, new_password = replacement, extra = {}) =>
  f.request('/api/account/password', { token, body: { current_password, new_password, ...extra } });
async function elevate(f, token, secret = password) {
  assert.equal((await f.request('/api/reauth', { token, body: { password: secret } })).status, 200);
}
async function users(f, owner, entries) {
  await elevate(f, owner);
  for (const [name, role] of entries)
    assert.equal((await admin(f, owner, { action: 'user.create', name, role, password })).status, 200);
  return (await admin(f, owner)).data.users;
}
function database(f, callback) {
  const db = new DatabaseSync(join(f.data, 'field-notes.sqlite3'));
  try { return callback(db); } finally { db.close(); }
}
const hashes = f => database(f, db => db.prepare('SELECT id,name,hash FROM users ORDER BY name').all());

test('install-time owner and additional users have separate salted Argon2id hashes without exposed credentials', async () => {
  const f = await fixture();
  try {
    const owner = await f.login();
    await users(f, owner, [['partner', 'member'], ['guest', 'viewer'], ['second-owner', 'owner']]);
    const stored = hashes(f);
    assert.equal(stored.length, 4);
    assert.equal(new Set(stored.map(u => u.hash)).size, 4, 'same password must use a new random salt per user');
    for (const user of stored) assert.match(user.hash, /^\$argon2id\$v=19\$m=19456,t=2,p=1\$/);
    for (const path of ['/api/admin', '/api/snapshot', '/api/people']) {
      const result = await f.request(path, { token: owner });
      assert.equal(result.status, 200);
      const raw = JSON.stringify(result.data);
      assert(!raw.includes(password));
      for (const user of stored) assert(!raw.includes(user.hash));
    }
    for (const suffix of ['', '-wal']) {
      try { assert(!(await readFile(join(f.data, 'field-notes.sqlite3' + suffix))).includes(Buffer.from(password))); }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
  } finally { await f.close(); }
});

test('password reset enforces fresh administrator proof and owner/admin/member/viewer boundaries', async () => {
  const f = await fixture();
  try {
    const owner = await f.login();
    const accounts = await users(f, owner, [['admin-one', 'admin'], ['admin-two', 'admin'], ['partner', 'member'], ['guest', 'viewer'], ['other-owner', 'owner']]);
    const byName = name => accounts.find(u => u.name === name).id;
    const reset = target => ({ action: 'user.password', target: byName(target), password: replacement });
    assert.equal((await admin(f, owner, reset('owner'))).status, 403);
    const administrator = await f.login('admin-one'), partner = await f.login('partner'), guest = await f.login('guest');
    assert.equal((await admin(f, undefined, reset('partner'))).status, 401);
    assert.equal((await admin(f, administrator, reset('partner'))).status, 403);
    await elevate(f, administrator);
    for (const target of ['owner', 'other-owner', 'admin-one', 'admin-two'])
      assert.equal((await admin(f, administrator, reset(target))).status, 403);
    for (const token of [partner, guest]) {
      await elevate(f, token);
      assert.equal((await admin(f, token, reset('partner'))).status, 403);
    }
    assert.equal((await admin(f, owner, { ...reset('partner'), password: 'short' })).status, 400);
    assert.equal((await admin(f, owner, { ...reset('partner'), target: randomUUID() })).status, 404);
    const partnerOtherSession = await f.login('partner');
    assert.equal((await admin(f, administrator, reset('partner'))).status, 200);
    for (const token of [partner, partnerOtherSession])
      assert.equal((await f.request('/api/head', { token })).status, 401);
    assert.equal((await f.request('/api/head', { token: guest })).status, 200);
    assert.equal((await f.request('/api/head', { token: administrator })).status, 200);
    assert.equal((await f.login('partner', replacement)).length, 64);
    assert.equal((await admin(f, administrator, reset('guest'))).status, 200);
    assert.equal((await admin(f, owner, reset('admin-two'))).status, 200);
    assert.equal((await admin(f, owner, reset('other-owner'))).status, 200);
    const activity = (await admin(f, owner)).data.audit;
    assert.equal(activity.filter(a => a.action === 'user.password.reset').length, 4);
    assert(!JSON.stringify(activity).includes(replacement));
    assert.equal((await admin(f, owner, { action: 'user.disable', target: byName('partner') })).status, 200);
    assert.equal((await admin(f, owner, reset('partner'))).status, 404);
  } finally { await f.close(); }
});

test('every role can change its own password; current proof, exact bytes, confirmation boundaries and CSRF remain enforced', async () => {
  const f = await fixture();
  try {
    const owner = await f.login();
    await users(f, owner, [['partner', 'member'], ['guest', 'viewer'], ['administrator', 'admin']]);
    const partner = await f.login('partner');
    assert.equal((await change(f, undefined, password)).status, 401);
    assert.equal((await change(f, partner, 'wrong-current-password')).status, 403);
    assert.equal((await change(f, partner, password, 'short')).status, 400);
    assert.equal((await change(f, partner, password, password)).status, 400);
    assert.equal((await change(f, partner, password, replacement, { user_id: 'someone-else' })).status, 422);
    assert.equal((await f.request('/api/account/password', { base: f.device, token: partner, body: { current_password: password, new_password: replacement } })).status, 404);
    const cookie = { Cookie: `field_session=${partner}` };
    assert.equal((await f.request('/api/account/password', { headers: cookie, body: { current_password: password, new_password: replacement } })).status, 403);
    const session = (await f.request('/api/session', { token: partner })).data;
    const headers = { ...cookie, 'X-Field-CSRF': session.csrf };
    assert.equal((await f.request('/api/account/password', { headers, origin: 'https://attacker.invalid', body: { current_password: password, new_password: replacement } })).status, 403);
    const exact = '  a new test password with spaces  ';
    const result = await f.request('/api/account/password', { headers, origin: f.admin, body: { current_password: password, new_password: exact } });
    assert.equal(result.status, 200);
    assert.match(result.headers.get('set-cookie'), /^field_session=;.*Max-Age=0/);
    assert.equal((await f.request('/api/head', { token: partner })).status, 401);
    assert.equal((await f.request('/api/login', { body: { name: 'partner', password: exact.trim(), device: 'wrong trimmed secret' } })).status, 401);
    assert.equal((await f.login('partner', exact)).length, 64);
    for (const name of ['guest', 'administrator', 'owner']) {
      const token = await f.login(name);
      assert.equal((await change(f, token, password)).status, 200, name);
      assert.equal((await f.request('/api/head', { token })).status, 401);
      assert.equal((await f.login(name, replacement)).length, 64);
    }
  } finally { await f.close(); }
});

test('reset atomically cancels pending enrollment and rolls back hash/sessions/invitations on audit failure', async () => {
  const f = await fixture({ enrollment: true });
  try {
    const owner = await f.login();
    const accounts = await users(f, owner, [['partner', 'member']]);
    const target = accounts.find(u => u.name === 'partner').id, partner = await f.login('partner');
    const invitation = async () => {
      const result = await f.request('/api/enrollment/invitations', { token: owner, body: { user_id: target } });
      assert.equal(result.status, 200);
      return JSON.parse(Buffer.from(result.data.code.split('#')[1], 'base64url'));
    };
    const unused = await invitation(), used = await invitation();
    const claimBody = { token: used.token, claim_secret: randomBytes(32).toString('hex'), public_key: randomBytes(32).toString('base64'), name: 'pending phone' };
    const claim = await f.request('/api/enrollment/claim', { body: claimBody });
    assert.equal(claim.status, 200);
    assert.equal((await f.request(`/api/enrollment/devices/${claim.data.id}/approve`, { token: owner, body: { verification: claim.data.verification } })).status, 200);
    const before = hashes(f).find(u => u.id === target).hash;
    database(f, db => db.exec("CREATE TRIGGER fail_password_audit BEFORE INSERT ON audit WHEN NEW.action='user.password.reset' BEGIN SELECT RAISE(ABORT,'injected failure'); END"));
    const reset = () => admin(f, owner, { action: 'user.password', target, password: replacement, sign_out_devices: true });
    try {
      assert.equal((await reset()).status, 500);
      assert.equal(hashes(f).find(u => u.id === target).hash, before);
      assert.equal((await f.request('/api/head', { token: partner })).status, 200);
      assert.equal(database(f, db => db.prepare('SELECT COUNT(*) AS n FROM enrollment_invites WHERE user_id=?').get(target).n), 2);
      assert.equal(database(f, db => db.prepare('SELECT state FROM devices WHERE id=?').get(claim.data.id).state), 'provisioning');
    } finally { database(f, db => db.exec('DROP TRIGGER fail_password_audit')); }
    assert.equal((await reset()).status, 200);
    assert.equal((await f.request('/api/head', { token: partner })).status, 401);
    assert.equal((await f.request('/api/enrollment/status', { body: { id: claim.data.id, claim_secret: claimBody.claim_secret } })).status, 410);
    const stale = await f.request('/api/enrollment/claim', { body: { ...claimBody, token: unused.token, public_key: randomBytes(32).toString('base64') } });
    assert.equal(stale.status, 410);
    assert.equal(database(f, db => db.prepare('SELECT COUNT(*) AS n FROM enrollment_invites WHERE user_id=?').get(target).n), 0);
    await f.stop(); await f.start();
    assert.equal((await f.request('/api/login', { body: { name: 'partner', password, device: 'old password' } })).status, 401);
    assert.equal((await f.login('partner', replacement)).length, 64);
    assert.notEqual(hashes(f).find(u => u.id === target).hash, before);
  } finally { await f.close(); }
});

test('routine password changes preserve paired credentials but phone tokens cannot authorize the LAN manager', async () => {
  const f = await fixture();
  try {
    const owner = await f.login();
    const accounts = await users(f, owner, [['partner', 'member']]);
    const target = accounts.find(u => u.name === 'partner').id;
    const web = await f.login('partner'), phone = await f.login('partner'), device = randomUUID();
    // Seed a device-session association for route/credential scope checks only.
    // Actual activation/source binding is exercised by the Docker WG fixture.
    database(f, db => {
      db.prepare("INSERT INTO devices VALUES(?,?,?,?,?,'active','claim','activation',0,'1234ABCD',X'00')")
        .run(device, target, randomBytes(32).toString('base64'), '10.82.0.2', 'Bound phone');
      db.prepare('INSERT INTO device_sessions VALUES(?,?)').run(createHash('sha256').update(phone).digest('hex'), device);
    });
    for (const path of ['/api/session', '/api/snapshot', '/api/admin'])
      assert.equal((await f.request(path, { token: phone })).status, 403);
    assert.equal((await f.request('/api/reauth', { token: phone, body: { password } })).status, 403);
    assert.equal((await change(f, web, password)).status, 200);
    assert.equal((await f.request('/api/head', { base: f.device, token: phone })).status, 200);
    assert.equal((await f.request('/api/head', { token: web })).status, 401);
    assert.equal((await admin(f, owner, { action: 'user.password', target, password, sign_out_devices: 'yes' })).status, 400);
    assert.equal((await admin(f, owner, { action: 'user.password', target, password, sign_out_devices: true })).status, 200);
    assert.equal((await f.request('/api/head', { base: f.device, token: phone })).status, 401);
    assert.equal(database(f, db => db.prepare('SELECT state FROM devices WHERE id=?').get(device).state), 'active', 'sign-out does not silently delete the tunnel binding');
  } finally { await f.close(); }
});

test('failed current-password guesses stay rate limited across restart', async () => {
  const f = await fixture();
  try {
    const owner = await f.login();
    for (let n = 0; n < 5; n++) assert.equal((await change(f, owner, 'wrong-current-password')).status, 403);
    await f.stop(); await f.start();
    assert.equal((await change(f, owner, password)).status, 429);
    assert.equal((await f.request('/api/head', { token: owner })).status, 200);
    assert.equal((await f.login()).length, 64);
  } finally { await f.close(); }
});

test('simultaneous password changes allow one winner and cannot reuse the revoked session', async () => {
  const f = await fixture();
  try {
    const owner = await f.login(), another = 'a-different-test-password-only';
    const results = await Promise.all([change(f, owner, password, replacement), change(f, owner, password, another)]);
    assert.deepEqual(results.map(r => r.status).sort(), [200, 401]);
    const accepted = results[0].status === 200 ? replacement : another;
    assert.equal((await f.login('owner', accepted)).length, 64);
    assert.equal((await change(f, owner, password)).status, 401);
  } finally { await f.close(); }
});
