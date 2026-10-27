// Takes screenshots of the main UI states with Playwright and records browser console errors.
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';

const out = process.argv[2] ?? 'screenshots';
mkdirSync(out, { recursive: true });
const errors = [];
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 960 } });
page.on('console', (m) => m.type() === 'error' && errors.push(`console: ${m.text()}`));
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
page.on('requestfailed', (r) => errors.push(`requestfailed: ${r.url()} ${r.failure()?.errorText}`));
let expectHttpError = false; // set around a step that provokes a server-side validation error on purpose
page.on('response', (r) => r.status() >= 400 && !expectHttpError && errors.push(`http ${r.status()}: ${r.url()}`));

const shot = async (name) => {
  await page.waitForTimeout(700);
  await page.screenshot({ path: `${out}/${name}.png` });
};
const step = async (name, fn) => {
  try {
    await fn();
  } catch (e) {
    errors.push(`step ${name}: ${e.message.split('\n')[0]}`);
    await page.screenshot({ path: `${out}/${name}-failed.png` }).catch(() => {});
  }
};

// the scenarios below use the English UI; the Russian UI is checked at the end
await page.goto('http://127.0.0.1:18766/', { waitUntil: 'networkidle' });
await page.evaluate(() => localStorage.setItem('pgvc_lang', 'en'));
await page.reload({ waitUntil: 'networkidle' });
await step('01-main', async () => {
  await page.getByText('Working Tree (Uncommitted Changes)').first().waitFor({ timeout: 20000 });
  await shot('01-main');
});
await step('02-working-tree', async () => {
  await page.getByText('Working Tree (Uncommitted Changes)').first().click();
  await page.getByRole('button', { name: 'Commit Changes' }).waitFor();
  await page.waitForTimeout(1500);
  await shot('02-working-tree');
});
await step('03-commit', async () => {
  await page.getByText('Add customer notes').first().click();
  await page.getByRole('button', { name: /Rollback DB to here/ }).waitFor();
  await page.waitForTimeout(1200);
  await shot('03-commit');
});
await step('04-commit-sql', async () => {
  await page.getByRole('tab', { name: 'UP SQL' }).click();
  await shot('04-commit-sql');
});
await step('05-rollback', async () => {
  await page.getByRole('button', { name: /Rollback DB to here/ }).click();
  await page.getByText('Rollback script').waitFor();
  await page.waitForTimeout(1200);
  await shot('05-rollback');
  await page.keyboard.press('Escape');
});
await step('06-environments', async () => {
  await page.getByRole('button', { name: /Environments/ }).click();
  await page.getByRole('button', { name: /Test connection/ }).click();
  await page.waitForTimeout(1500);
  await shot('06-environments');
  await page.keyboard.press('Escape');
});
await step('07-mode-b', async () => {
  await page.getByRole('button', { name: /New SQL Migration/ }).click();
  await page.waitForTimeout(1500);
  await shot('07-mode-b');
  await page.keyboard.press('Escape');
});
await step('08-export', async () => {
  await page.getByRole('button', { name: /Export Offline/ }).click();
  await page.getByText(/commits? ·/).first().waitFor({ timeout: 10000 });
  await shot('08-export');
  await page.keyboard.press('Escape');
});
await step('09-sync', async () => {
  await page.getByRole('button', { name: /Sync \/ Push/ }).click();
  await page.getByText('Commits to push').waitFor({ timeout: 10000 });
  await shot('09-sync');
  await page.keyboard.press('Escape');
});
await step('10-explorer-filter', async () => {
  await page.getByLabel('Filter schema objects').fill('orders');
  await shot('10-explorer-filter');
});

// ---- real actions through the UI
const toast = (re) => page.getByRole('status').filter({ hasText: re }).first().waitFor({ timeout: 30000 });
await step('11-commit-working-tree', async () => {
  await page.getByLabel('Filter schema objects').fill('');
  await page.getByText('Working Tree (Uncommitted Changes)').first().click();
  await page.getByPlaceholder('your name').fill('alice');
  await page.getByPlaceholder('What changed and why').fill('Customer phone, rename note to remark');
  await page.getByRole('button', { name: 'Commit Changes' }).click();
  await toast(/Committed/);
  await shot('11-commit-working-tree');
});
await step('12-push-to-stage', async () => {
  await page.getByRole('button', { name: /Sync \/ Push/ }).click();
  await page.getByText('Commits to push').waitFor({ timeout: 10000 });
  await page.getByRole('button', { name: /^Push \d+ commit/ }).click();
  await toast(/Pushed/);
  await shot('12-push-to-stage');
});
await step('13-mode-b-execute', async () => {
  await page.getByRole('button', { name: /New SQL Migration/ }).click();
  await page.locator('.monaco-editor').first().click();
  await page.keyboard.press('Control+End');
  await page.keyboard.type('CREATE TABLE sales.coupons (code text PRIMARY KEY, discount numeric NOT NULL);');
  await page.getByPlaceholder('What does this migration do?').fill('Add coupons');
  await page.getByRole('button', { name: /Execute & Commit/ }).click();
  await toast(/Executed and committed/);
  await shot('13-mode-b-execute');
});
await step('14-rollback', async () => {
  await page.getByText('Index notes by customer').first().click();
  await page.getByRole('button', { name: /Rollback DB to here/ }).click();
  await page.getByText('Rollback script').waitFor();
  await page.getByRole('button', { name: /Roll back database/ }).click();
  await toast(/rolled back/);
  await shot('14-rollback');
});

await step('15-link-panel', async () => {
  await page.getByRole('button', { name: /Sync \/ Push/ }).click();
  await page.getByLabel('To (target)').selectOption('qa');
  await page.getByText(/Link QA to the history of Dev/).waitFor({ timeout: 15000 });
  await page.getByText('matches commit').waitFor({ timeout: 15000 });
  await shot('15-link-panel');
});
await step('16-linked-then-push', async () => {
  await page.getByRole('button', { name: /^Link to / }).click();
  await toast(/linked to/);
  await page.getByText('Commits to push').waitFor({ timeout: 15000 });
  await shot('16-linked');
  await page.getByRole('button', { name: /^Push \d+ commit/ }).click();
  await toast(/Pushed/);
  await shot('16-linked-then-push');
});

// ---- About & License, then the Russian UI
await step('17-about', async () => {
  await page.getByRole('button', { name: 'About & License' }).click();
  await page.getByText('Apache License 2.0').first().waitFor({ timeout: 10000 });
  await page.getByRole('button', { name: 'Show the full license text' }).click();
  await page.getByText('TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION').waitFor({ timeout: 5000 });
  await shot('17-about');
  await page.keyboard.press('Escape');
});
await step('18-ru-main', async () => {
  await page.getByRole('button', { name: 'RU', exact: true }).click();
  await page.getByText('Обозреватель схемы').waitFor({ timeout: 5000 });
  const stored = await page.evaluate(() => localStorage.getItem('pgvc_lang'));
  if (stored !== 'ru') throw new Error(`pgvc_lang is ${stored}`);
  await page.getByText('Add coupons').first().click();
  await page.getByRole('tab', { name: 'Снимок', exact: true }).click();
  await shot('18-ru-main');
});
await step('19-ru-sync', async () => {
  await page.getByRole('button', { name: /Синхронизация/ }).click();
  await page.getByText('Синхронизация / Push в окружение').waitFor({ timeout: 5000 });
  await page.getByText(/Разница схем/).waitFor({ timeout: 15000 });
  await shot('19-ru-sync');
  await page.keyboard.press('Escape');
});
await step('20-ru-server-error', async () => {
  // validation error produced by the server: must come back in Russian (Accept-Language: ru)
  await page.getByTitle('Управление окружениями').click();
  await page.getByRole('button', { name: 'Добавить окружение' }).click();
  await page.getByLabel('Имя', { exact: true }).fill('Broken');
  await page.getByLabel('JDBC URL', { exact: true }).fill('jdbc:mysql://localhost/db');
  expectHttpError = true;
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: 'JDBC URL должен начинаться с jdbc:postgresql:' }).waitFor({ timeout: 10000 });
  expectHttpError = false;
  await shot('20-ru-server-error');
  await page.keyboard.press('Escape');
});
await step('21-ru-reload-keeps-language', async () => {
  await page.reload({ waitUntil: 'networkidle' });
  await page.getByText('Обозреватель схемы').waitFor({ timeout: 10000 });
  await shot('21-ru-after-reload');
});

writeFileSync(`${out}/errors.txt`, errors.length ? errors.join('\n') + '\n' : 'no errors\n');
await browser.close();
console.log(errors.length ? errors.join('\n') : 'no browser errors');
