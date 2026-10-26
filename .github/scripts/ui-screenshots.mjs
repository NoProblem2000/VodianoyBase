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
page.on('response', (r) => r.status() >= 400 && errors.push(`http ${r.status()}: ${r.url()}`));

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

await page.goto('http://127.0.0.1:18766/', { waitUntil: 'networkidle' });
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

writeFileSync(`${out}/errors.txt`, errors.length ? errors.join('\n') + '\n' : 'no errors\n');
await browser.close();
console.log(errors.length ? errors.join('\n') : 'no browser errors');
