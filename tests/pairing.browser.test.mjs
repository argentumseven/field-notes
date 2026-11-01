import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { fixture, password } from './support.mjs';

test('invitation expiry, replacement and session loss remove stale QR and password controls', { timeout: 90_000 }, async () => {
  const f = await fixture({ enrollment: true });
  let browser;
  try {
    const admin = await f.login();
    await f.request('/api/reauth', { token: admin, body: { password } });
    browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    const login = async () => {
      await page.goto(f.admin);
      await page.getByLabel('User name', { exact: true }).fill('owner');
      await page.getByLabel('Password', { exact: true }).fill(password);
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
      await page.getByRole('heading', { name: 'Focus', exact: true }).waitFor();
    };
    const invite = async () => {
      await page.getByRole('button', { name: 'Create invitation', exact: true }).click();
      if (await page.getByRole('heading', { name: 'Confirm it’s you', exact: true }).isVisible()) {
        await page.getByLabel('Password', { exact: true }).fill(password);
        await page.getByRole('button', { name: 'Verify', exact: true }).click();
      }
      await page.getByRole('heading', { name: 'Enroll a phone', exact: true }).waitFor();
      await page.locator('#dialog-submit').click();
      await page.locator('.invite-qr').waitFor();
      return page.locator('.invite-qr').getAttribute('src');
    };
    const revokeBrowser = async () => {
      const overview = await f.request('/api/admin', { token: admin });
      const session = overview.data.sessions.find(s => s.device === 'LAN web browser');
      assert.ok(session);
      assert.equal((await f.request('/api/admin', { token: admin, body: { action: 'session.revoke', target: session.id } })).status, 200);
    };
    await login();
    await page.getByRole('button', { name: 'Management', exact: true }).click();
    await page.getByRole('heading', { name: 'Devices', exact: true }).waitFor();
    await page.clock.install();
    const first = await invite();
    await page.clock.fastForward(601_000);
    assert.equal(await page.locator('.invite-qr').count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Copy invitation', exact: true }).isDisabled(), true);
    await page.getByText('This invitation has expired. Close this dialog and create a fresh one.', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Done', exact: true }).click();
    await page.clock.setSystemTime(new Date());
    const second = await invite();
    assert.notEqual(first, second);
    await revokeBrowser();
    assert.equal(await page.evaluate(() => api('/snapshot').catch(e => e.status)), 401);
    assert.equal(await page.locator('#dialog').evaluate(n => n.open), false);
    assert.equal(await page.locator('.invite-qr').count(), 0);
    await login();
    await page.locator('.sidebar-bottom').getByRole('button', { name: 'Change password', exact: true }).click();
    await page.getByLabel('Current password', { exact: true }).fill(password);
    await page.getByLabel('New password (12+ characters)', { exact: true }).fill('new-but-unsubmitted-password');
    await page.getByLabel('Confirm new password', { exact: true }).fill('new-but-unsubmitted-password');
    await revokeBrowser();
    await page.locator('#dialog-submit').click();
    await page.getByRole('heading', { name: 'Sign in', exact: true }).waitFor();
    assert.equal(await page.locator('#dialog').evaluate(n => n.open), false);
    assert.equal(await page.getByLabel('Current password', { exact: true }).inputValue(), '');
    assert.equal(await page.getByLabel('New password (12+ characters)', { exact: true }).inputValue(), '');
    assert.deepEqual(errors, []);
  } finally {
    await browser?.close();
    await f.close();
  }
});
