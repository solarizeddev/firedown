#!/usr/bin/env node
// Sets status.json's "changelog" field from the LATEST entry in CHANGELOG.md, so
// the in-app update sheet's "What's new" matches the published release notes
// without hand-copying. Run on each release, after bumping CHANGELOG.md +
// status.json's versionCode/versionName/sha256:
//
//   node scripts/update-status-changelog.mjs
//
// It reads the first "## [version] - date" block, takes its bullet lines, and
// writes them as "• "-prefixed, newline-separated text (the sheet renders \n as
// line breaks). Warns if the changelog's version doesn't match status.json's
// versionName, so a forgotten bump is caught.

import { readFileSync, writeFileSync } from 'node:fs';

const CHANGELOG = 'CHANGELOG.md';
const STATUS = 'status.json';

const md = readFileSync(CHANGELOG, 'utf8');

// Capture the first "## [version] - date" block's bullets, stopping at the next "##".
let version = null;
const bullets = [];
let inBlock = false;
for (const line of md.split('\n')) {
  const header = line.match(/^##\s*\[([^\]]+)\]/);
  if (header) {
    if (inBlock) break; // reached the next version — done
    version = header[1].trim();
    inBlock = true;
    continue;
  }
  if (inBlock) {
    const bullet = line.match(/^\s*[-*]\s+(.*)$/);
    if (bullet) bullets.push(bullet[1].trim());
  }
}

if (!version || bullets.length === 0) {
  console.error(`Could not parse a version + bullets from ${CHANGELOG}`);
  process.exit(1);
}

const changelog = bullets.map((b) => `• ${b}`).join('\n');

const status = JSON.parse(readFileSync(STATUS, 'utf8'));
if (status.versionName && !status.versionName.includes(version)) {
  console.warn(
    `Warning: CHANGELOG latest is ${version} but status.json versionName is ` +
      `"${status.versionName}" — make sure they match before releasing.`
  );
}

status.changelog = changelog;
writeFileSync(STATUS, JSON.stringify(status) + '\n');
console.log(`status.json changelog set from CHANGELOG.md [${version}] (${bullets.length} items).`);
