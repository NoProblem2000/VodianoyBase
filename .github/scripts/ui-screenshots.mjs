// Takes screenshots of the main UI states with Playwright and records browser console errors.
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';

const out = process.argv[2] ?? 'screenshots';
mkdirSync(out, { recursive: true });
const errors = [];
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 960 } });
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
page.on('requestfailed', (r) => errors.push(`requestfailed: ${r.url()} ${r.failure()?.errorText}`));
let expectHttpError = false; // set around a step that provokes a server-side validation error on purpose
page.on('console', (m) => {
  if (m.type() !== 'error') return;
  if (expectHttpError && m.text().startsWith('Failed to load resource')) return;
  errors.push(`console: ${m.text()}`);
});
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
await step('10b-discard-item', async () => {
  await page.getByLabel('Filter schema objects').fill('');
  await page.getByText('Working Tree (Uncommitted Changes)').first().click();
  await page.getByRole('button', { name: 'Discard / Revert Item: sales.customers_phone_idx' }).click();
  await page.getByText('DROP INDEX sales.customers_phone_idx;').first().waitFor({ timeout: 15000 });
  await shot('10b-discard-item');
  await page.getByRole('button', { name: 'Revert This Change' }).click();
  await toast(/Reverted 1 change\(s\), 2 left/);
});
await step('10c-discard-all-dialog', async () => {
  await page.getByText('Working Tree (Uncommitted Changes)').first().click();
  await page.getByRole('button', { name: /Discard All Changes \(Reset to HEAD\)/ }).click();
  await page.getByText('SQL to execute (you can edit it)').waitFor({ timeout: 15000 });
  await page.getByText(/RENAME COLUMN remark TO note/).first().waitFor({ timeout: 10000 });
  await shot('10c-discard-all-dialog');
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
});
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

// ---- deleting commits: "Customer phone…" is still Stage's HEAD, "Add coupons" was abandoned by the rollback
await step('16b-delete-blocked', async () => {
  await page.getByText('Customer phone, rename note to remark').first().click();
  const del = page.getByRole('button', { name: 'Delete…' });
  await del.waitFor({ timeout: 10000 });
  if (await del.isEnabled()) throw new Error('Delete must be disabled for a commit in the history of Stage');
});
await step('16c-delete-commit', async () => {
  await page.getByText('Add coupons').first().click();
  await page.getByRole('button', { name: 'Delete…' }).click();
  await page.getByText('Will be deleted').waitFor({ timeout: 10000 });
  await page.getByRole('radio', { name: /The whole branch/ }).click();
  await page.getByRole('button', { name: 'Delete 1 commit', exact: true }).waitFor({ timeout: 10000 });
  await shot('16c-delete-commit');
  await page.getByRole('button', { name: 'Delete 1 commit', exact: true }).click();
  await toast(/Deleted 1 commit/);
  if (await page.getByText('Add coupons').count()) throw new Error('"Add coupons" is still in the graph');
  await shot('16d-deleted');
});

// ---- reference snapshot: export from Dev, compare and apply on the customer database
const snapshotFile = `${out}/reference.pgsnap.json`;
await step('16e-export-snapshot', async () => {
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 20000 }),
    page.getByRole('button', { name: 'Export Snapshot (.pgsnap.json)' }).click(),
  ]);
  const name = download.suggestedFilename();
  if (!/^snapshot_dev_[0-9a-f]{7}\.pgsnap\.json$/.test(name)) throw new Error(`unexpected file name ${name}`);
  await download.saveAs(snapshotFile);
  await toast(/Snapshot saved/);
});
await step('16f-compare-snapshot', async () => {
  await page.getByLabel('Current environment').selectOption('customer');
  await page.getByRole('button', { name: 'Compare with Snapshot' }).click();
  await page.getByText('Drop a .pgsnap.json file here').waitFor({ timeout: 10000 });
  await shot('16f-snapshot-drop');
  await page.getByLabel('Reference snapshot file').setInputFiles(snapshotFile);
  await page.getByText('Missing or different').first().waitFor({ timeout: 20000 });
  const extra = page.getByRole('checkbox', { name: 'public.customer_only', exact: true });
  if (await extra.isChecked()) throw new Error('the customer table must not be selected by default');
  const notes = page.getByRole('checkbox', { name: 'sales.notes', exact: true });
  if (!(await notes.isChecked())) throw new Error('sales.notes must be selected by default');
  await shot('16f-compare-snapshot');
  // unselect / select rebuilds the script
  await notes.uncheck();
  await notes.check();
});
await step('16g-apply-snapshot', async () => {
  await page.getByRole('button', { name: 'Apply Selected Changes & Commit' }).click();
  await toast(/Applied and committed/);
  await page.getByText('Nothing here.').first().waitFor({ timeout: 20000 });
  await shot('16g-applied');
  await page.keyboard.press('Escape');
  await page.getByText(/Sync with external snapshot/).first().waitFor({ timeout: 10000 });
  await page.getByLabel('Current environment').selectOption('dev');
});

// ---- Migration Safety & Lock Analyzer, zero-downtime pattern, dry-run, multi-step execution (Mode B)
await step('16h-safety-analysis', async () => {
  await page.getByRole('button', { name: /New SQL Migration/ }).click();
  await page.locator('.monaco-editor').first().click();
  await page.keyboard.press('Control+End');
  await page.keyboard.type('CREATE INDEX ledger_account_idx ON billing.ledger (account);');
  await page.locator('[data-risk="DANGEROUS"]').first().waitFor({ timeout: 20000 });
  await page.getByText('SHARE', { exact: true }).first().waitFor({ timeout: 5000 });
  await shot('16h-safety-analysis');
});
await step('16i-zero-downtime-pattern', async () => {
  await page.getByRole('button', { name: /Use Zero-Downtime Pattern/ }).first().click();
  await toast(/Zero-downtime pattern inserted/);
  await page.getByText('CREATE INDEX CONCURRENTLY ledger_account_idx').first().waitFor({ timeout: 10000 });
  // the re-analysis sees a step that runs outside of a transaction, and nothing dangerous any more
  await page.getByText('Outside transaction').first().waitFor({ timeout: 15000 });
  await page.locator('[data-risk="SAFE"]').first().waitFor({ timeout: 15000 });
  await shot('16i-zero-downtime-pattern');
});
await step('16j-dry-run', async () => {
  await page.getByLabel('statement_timeout').fill('90s');
  await page.getByRole('button', { name: /Dry-Run \(Test in Rollback Transaction\)/ }).click();
  await page.getByText(/Dry-run succeeded in/).waitFor({ timeout: 30000 });
  await page.getByText(/outside a transaction for real/).first().waitFor({ timeout: 5000 });
  await shot('16j-dry-run');
});
await step('16k-execute-steps', async () => {
  await page.getByPlaceholder('What does this migration do?').fill('Index ledger by account (zero-downtime)');
  await page.getByRole('button', { name: /Execute & Commit/ }).click();
  await toast(/Executed and committed .*step/);
  await page.getByText('Index ledger by account (zero-downtime)').first().waitFor({ timeout: 10000 });
  await shot('16k-executed');
});

// ---- N-database matrix and ignore rules
await step('16l-matrix', async () => {
  await page.getByRole('button', { name: 'N-Database Matrix' }).click();
  await page.getByText('Choose databases on the left and press Compare.').waitFor({ timeout: 10000 });
  await page.getByLabel('Snapshot files').setInputFiles(snapshotFile);
  await page.getByText('reference', { exact: true }).first().waitFor({ timeout: 5000 });
  await page.getByRole('button', { name: 'Compare', exact: true }).click();
  const table = page.getByRole('table', { name: '🌐 N-Database Matrix' });
  await table.waitFor({ timeout: 30000 });
  await table.getByText('public.customer_only').waitFor({ timeout: 5000 });
  await table.getByRole('button', { name: 'only here', exact: true }).first().waitFor({ timeout: 5000 });
  // prod never got the customer notes (its history diverged with a hotfix)
  const notes = table.getByRole('row').filter({ hasText: 'sales.notes' });
  await notes.getByRole('button', { name: 'missing', exact: true }).first().waitFor({ timeout: 5000 });
  await shot('16l-matrix');
});
await step('16m-matrix-cell', async () => {
  const table = page.getByRole('table', { name: '🌐 N-Database Matrix' });
  // prod has its hotfix index on sales.orders: the cell explains it and offers the SQL that removes it
  const row = table.getByRole('row').filter({ hasText: 'sales.orders' }).filter({ hasNotText: 'sales.orders_' });
  const header = await table.locator('thead th').allInnerTexts();
  const prodColumn = header.findIndex((h) => h.startsWith('Prod'));
  if (prodColumn < 0) throw new Error(`no Prod column in ${header.join(' | ')}`);
  await row.first().locator('td').nth(prodColumn).getByRole('button').click();
  await page.getByText(/SQL that brings Prod to the baseline Dev/).first().waitFor({ timeout: 5000 });
  await page.getByText(/orders_status_hotfix_idx/).first().waitFor({ timeout: 10000 });
  await shot('16m-matrix-cell');
});
await step('16n-matrix-ignore-pattern', async () => {
  await page.getByRole('button', { name: /Ignore rules of this comparison/ }).click();
  await page.getByLabel('Excluded objects').fill('customer_only');
  await page.getByRole('button', { name: 'Compare', exact: true }).click();
  await page.waitForTimeout(1500);
  const table = page.getByRole('table', { name: '🌐 N-Database Matrix' });
  await table.waitFor({ timeout: 30000 });
  if (await table.getByText('public.customer_only').count()) throw new Error('customer_only is not ignored');
  await page.getByRole('button', { name: 'Save as default' }).click();
  await toast(/Default ignore rules saved/);
  await shot('16n-matrix-ignore-pattern');
  await page.keyboard.press('Escape');
});
await step('16o-ignore-rules-modal', async () => {
  await page.getByRole('button', { name: 'Ignore rules', exact: true }).click();
  const patterns = page.getByLabel('Excluded objects');
  await patterns.waitFor({ timeout: 5000 });
  if ((await patterns.inputValue()) !== 'customer_only') throw new Error(`saved patterns: ${await patterns.inputValue()}`);
  await shot('16o-ignore-rules-modal');
  await patterns.fill('');
  await page.getByRole('button', { name: 'Save as default' }).click();
  await toast(/Default ignore rules saved/);
});

// ---- reference data and the Git folder export
await step('16p-reference-data', async () => {
  await page.getByRole('button', { name: '📌 Reference Data Tables' }).click();
  const table = page.getByRole('table', { name: '📌 Reference Data Tables' });
  await table.waitFor({ timeout: 20000 });
  const box = page.getByRole('checkbox', { name: '📌 Track as Reference Data: sales.customers' });
  await box.click();
  await toast(/sales\.customers is tracked as reference data/);
  await page.waitForTimeout(800);
  if (!(await page.getByRole('checkbox', { name: '📌 Track as Reference Data: sales.customers' }).isChecked())) {
    throw new Error('sales.customers is not shown as tracked');
  }
  await shot('16p-reference-data');
  await page.keyboard.press('Escape');
});
await step('16q-git-export-zip', async () => {
  await page.getByRole('button', { name: '📁 Export Git Folder (.zip / Local Path)' }).click();
  await page.getByText('Download .zip').waitFor({ timeout: 5000 });
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30000 }),
    page.getByRole('button', { name: 'Export', exact: true }).click(),
  ]);
  const name = download.suggestedFilename();
  if (!/^pgvc-git-export_dev_[0-9a-f]{7}\.zip$/.test(name)) throw new Error(`unexpected file name ${name}`);
  await download.saveAs(`${out}/${name}`);
  await toast(/Downloaded pgvc-git-export_dev_/);
});
await step('16r-git-export-folder', async () => {
  await page.getByText('Write into a local folder').click();
  await page.getByPlaceholder('/home/me/projects/db-schema').fill('/tmp/ui-artifacts/git-export');
  await page.getByRole('button', { name: 'Export', exact: true }).click();
  await toast(/written · .* unchanged · .* deleted/);
  await page.getByText('schema/tables/sales.customers.sql').first().waitFor({ timeout: 10000 });
  await shot('16r-git-export-folder');
  await page.keyboard.press('Escape');
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
  await page.getByText('Index notes by customer').first().click();
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
await step('20b-ru-matrix', async () => {
  await page.getByRole('button', { name: 'Матрица N баз' }).click();
  await page.getByRole('button', { name: 'Сравнить', exact: true }).click();
  await page.getByRole('table', { name: '🌐 Матрица N баз' }).waitFor({ timeout: 30000 });
  await page.getByText('Только различия').waitFor({ timeout: 5000 });
  await shot('20b-ru-matrix');
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
