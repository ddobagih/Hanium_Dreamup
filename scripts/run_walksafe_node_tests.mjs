#!/usr/bin/env node
// Explicit recursive inventory: no shell glob dependence, including Node 22.
import { mkdirSync, mkdtempSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const packageRoot = join(repo, 'apps/android-gateway');
const root = resolve(process.argv[2] ?? join(packageRoot, 'dist/test'));
// Discovery is under dist/test, but package-relative fixtures/workers use package cwd.
// An explicit fixture root retains the caller's cwd for isolated regression runs.
const testCwd = process.argv[2] === undefined ? packageRoot : process.cwd();
const timeout = Number(process.env.WALKSAFE_NODE_TEST_TIMEOUT_MS ?? '60000');
if (!Number.isSafeInteger(timeout) || timeout <= 0) {
  throw new Error('WALKSAFE_NODE_TEST_TIMEOUT_MS must be a positive integer');
}
function discover(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return discover(path);
    return entry.isFile() && entry.name.endsWith('.test.js') ? [path] : [];
  }).sort();
}
const files = discover(root);
if (files.length === 0) throw new Error(`No .test.js files found under ${root}`);
const base = join(repo, 'artifacts/test-results');
if (!process.env.WALKSAFE_TEST_REPORT_DIR) mkdirSync(base, { recursive: true });
const reports = process.env.WALKSAFE_TEST_REPORT_DIR
  ? resolve(process.env.WALKSAFE_TEST_REPORT_DIR)
  : mkdtempSync(join(base, 'gateway-'));
mkdirSync(reports, { recursive: true });
writeFileSync(join(reports, 'node-gateway-inventory.json'), JSON.stringify({ root, cwd: testCwd, timeout_ms: timeout, files }, null, 2) + '\n');
writeFileSync(join(reports, 'node-gateway.started'), new Date().toISOString() + '\n');
const result = spawnSync(process.execPath, [
  '--test', `--test-timeout=${timeout}`,
  '--test-reporter=spec', '--test-reporter-destination=stdout',
  '--test-reporter=junit', `--test-reporter-destination=${join(reports, 'node-gateway.xml')}`,
  ...files,
], { cwd: testCwd, stdio: 'inherit' });
const status = result.status ?? 1;
writeFileSync(join(reports, 'node-gateway.exit'), `${status}\n`);
if (result.error) console.error(result.error.message);
if (result.signal) console.error(`Node tests terminated by ${result.signal}`);
process.exitCode = status;
