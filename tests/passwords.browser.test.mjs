import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { fixture, password } from './support.mjs';

test('browser creates users, changes own password and resets another account without retaining secret fields', async () => {
  const f = await fixture({ enrollment: true });
  let browser, releaseChange;
  try {
    const owner = await f.login();
    await f.request('/api/reauth', { token: owner, body: { password } });
    assert.equal((await f.request('/api/admin', { token: owner, body: { action: 'user.create', name: 'Alex', role: 'admin', password } })).status, 200);
    browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
    const errors = [];
    const newPage = async () => {
      const context = await browser.newContext({ viewport: { width: 1440, height: 1050 } });
      const page = await context.newPage();
      page.on('pageerror', error => errors.push(error.message));
      return page;
    };
    const login = async (page, name, secret) => {
      await page.goto(f.admin);
      await page.getByLabel('User name', { exact: true }).fill(name);
      await page.getByLabel('Password', { exact: true }).fill(secret);
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
      await page.getByRole('heading', { name: 'Focus', exact: true }).waitFor();
    };
    const ownerPage = await newPage();
    await login(ownerPage, 'owner', password);
    await ownerPage.getByRole('button', { name: 'Management', exact: true }).click();
    await ownerPage.getByRole('button', { name: '+ Add person', exact: true }).click();
    await ownerPage.getByLabel('Password', { exact: true }).fill(password);
    await ownerPage.getByRole('button', { name: 'Verify', exact: true }).click();
    await ownerPage.getByLabel('Name', { exact: true }).fill('Taylor');
    await ownerPage.getByLabel('Initial password (12+ characters)', { exact: true }).fill(password);
    await ownerPage.getByLabel('Confirm initial password', { exact: true }).fill('a-different-test-password');
    await ownerPage.getByLabel('Role', { exact: true }).selectOption('viewer');
    await ownerPage.locator('#dialog-submit').click();
    await ownerPage.getByText('Initial passwords do not match.', { exact: true }).waitFor();
    await ownerPage.getByLabel('Confirm initial password', { exact: true }).fill(password);
    await ownerPage.locator('#dialog-submit').click();
    const person = page => page.locator('.admin-grid .item-row').filter({ has: page.getByText('Taylor', { exact: true }) });
    await person(ownerPage).getByRole('button', { name: 'Reset password', exact: true }).waitFor();
    assert.equal(await ownerPage.getByLabel('Initial password (12+ characters)', { exact: true }).inputValue(), '');

    const administrator = await newPage();
    await login(administrator, 'Alex', password);
    await administrator.getByRole('button', { name: 'Management', exact: true }).click();
    await person(administrator).getByRole('button', { name: 'Reset password', exact: true }).waitFor();
    const ownerRow = administrator.locator('.admin-grid .item-row').filter({ has: administrator.getByText('owner', { exact: true }) });
    assert.equal(await ownerRow.getByRole('button', { name: 'Reset password', exact: true }).count(), 0);

    const viewer = await newPage();
    await login(viewer, 'Taylor', password);
    assert.equal(await viewer.getByRole('button', { name: 'Management', exact: true }).count(), 0);
    const ownButton = viewer.locator('.sidebar-bottom').getByRole('button', { name: 'Change password', exact: true });
    await ownButton.click();
    await viewer.getByLabel('Current password', { exact: true }).fill('wrong-current-password');
    await viewer.getByLabel('New password (12+ characters)', { exact: true }).fill('self-service-test-password');
    await viewer.getByLabel('Confirm new password', { exact: true }).fill('confirmation-does-not-match');
    await viewer.locator('#dialog-submit').click();
    await viewer.getByText('New passwords do not match.', { exact: true }).waitFor();
    await viewer.getByLabel('Confirm new password', { exact: true }).fill('self-service-test-password');
    await viewer.locator('#dialog-submit').click();
    await viewer.getByText('Current password not accepted', { exact: true }).waitFor();
    await viewer.getByRole('button', { name: 'Cancel', exact: true }).click();
    assert.equal(await viewer.getByLabel('Current password', { exact: true }).inputValue(), '');
    await ownButton.click();
    await viewer.getByLabel('Current password', { exact: true }).fill(password);
    await viewer.getByLabel('New password (12+ characters)', { exact: true }).fill('self-service-test-password');
    await viewer.getByLabel('Confirm new password', { exact: true }).fill('self-service-test-password');
    let observed;
    const sent = new Promise(resolve => { observed = resolve; });
    const gate = new Promise(resolve => { releaseChange = resolve; });
    await viewer.route('**/api/account/password', async route => { observed(); await gate; await route.continue(); });
    await viewer.locator('#dialog-submit').click();
    await sent;
    assert.equal(await viewer.locator('#dialog-cancel').isDisabled(), true);
    await viewer.keyboard.press('Escape');
    assert.equal(await viewer.locator('#dialog').evaluate(node => node.open), true, 'cannot dismiss an in-flight password change');
    releaseChange();
    await viewer.getByRole('heading', { name: 'Sign in', exact: true }).waitFor();
    assert.equal(await viewer.getByLabel('Current password', { exact: true }).inputValue(), '');
    assert.equal(await viewer.getByLabel('New password (12+ characters)', { exact: true }).inputValue(), '');
    assert.equal((await viewer.context().cookies()).some(cookie => cookie.name === 'field_session'), false);
    await viewer.unroute('**/api/account/password');
    await login(viewer, 'Taylor', 'self-service-test-password');

    await person(ownerPage).getByRole('button', { name: 'Reset password', exact: true }).click();
    await ownerPage.getByRole('heading', { name: 'Reset password for Taylor', exact: true }).waitFor();
    await ownerPage.getByLabel('New password (12+ characters)', { exact: true }).fill('administrator-reset-test-password');
    await ownerPage.getByLabel('Confirm new password', { exact: true }).fill('administrator-reset-test-password');
    await ownerPage.locator('#dialog-submit').click();
    await ownerPage.getByText('Password reset for Taylor. Their browsers have been signed out.', { exact: true }).waitFor();
    assert.equal(await ownerPage.getByLabel('New password (12+ characters)', { exact: true }).inputValue(), '');
    assert.equal(await viewer.evaluate(async () => (await fetch('/api/snapshot')).status), 401);
    await viewer.reload();
    await viewer.getByRole('heading', { name: 'Sign in', exact: true }).waitFor();
    await login(viewer, 'Taylor', 'administrator-reset-test-password');
    await viewer.setViewportSize({ width: 390, height: 844 });
    await viewer.locator('.sidebar-bottom').getByRole('button', { name: 'Change password', exact: true }).click();
    assert.equal(await viewer.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    assert.deepEqual(errors, []);
  } finally {
    releaseChange?.();
    await browser?.close();
    await f.close();
  }
});
