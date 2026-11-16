// Records one published release in the index.
//
// The file is a JSON array with one entry per tag. The tag is the identity because one version
// can be released on several dates: v1.0.0 cut today and v1.0.0 cut next month share the version
// but are different releases with different files. Publishing a tag that is already recorded
// replaces it in place, the newest entry always sits at the end, and only the last MAX_ENTRIES
// releases are kept, so a retry of the same tag leaves one row and the file cannot grow without
// bound.
//
// An entry that does not match the schema stops the run. A reader looking up a hash by version
// would otherwise stop de-duplicating without any error, and the next publish would append a
// second row for a version that already had one.
//
// Usage:
//   node update-release-index.mjs --file release/release.json --version <version> \
//     --tag <tag> --title <title> \
//     --rom-properties rom/rom.properties.yaml \
//     --pack-report <pack report> --commit <sha> \
//     --run-url <url> --assets release-assets.json

import { readFileSync, writeFileSync } from 'node:fs';
import { parseArgs } from 'node:util';
import { readPackReport, readRomProperties } from './lib/release-facts.mjs';

const MAX_ENTRIES = 100;
const VERSION_PATTERN = /^v\d+\.\d+\.\d+$/;
const TAG_PATTERN = /^v\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const ROM_KEY_PATTERN = /^([A-Za-z0-9-]+)@(.+)$/;

function fail (message) {
  console.error(message);
  process.exit(1);
}

const { values } = parseArgs({
  options: {
    file: { type: 'string' },
    version: { type: 'string' },
    tag: { type: 'string' },
    title: { type: 'string' },
    'rom-properties': { type: 'string' },
    'pack-report': { type: 'string' },
    commit: { type: 'string' },
    'run-url': { type: 'string' },
    assets: { type: 'string' },
    'max-entries': { type: 'string' },
  },
});

const REQUIRED = ['file', 'version', 'tag', 'title', 'rom-properties', 'pack-report', 'commit', 'assets'];
for (const name of REQUIRED) {
  if (!values[name]) fail(`--${name} is required`);
}

const version = values.version;
if (!VERSION_PATTERN.test(version)) fail(`--version ${version} is not vX.Y.Z`);

const tag = values.tag;
if (typeof tag !== 'string' || !TAG_PATTERN.test(tag)) fail(`--tag ${tag} is not vX.Y.Z or vX.Y.Z-suffix`);

const maxEntries = values['max-entries'] ? Number(values['max-entries']) : MAX_ENTRIES;
if (!Number.isInteger(maxEntries) || maxEntries < 1) fail('--max-entries must be a positive integer');

const rom = readRomProperties(values['rom-properties']);
const report = readPackReport(values['pack-report']);
const romKey = ROM_KEY_PATTERN.exec(report.romKey);
if (!romKey) fail(`${values['pack-report']}: romKey ${report.romKey} is not <id>@<version>`);
if (romKey[1] !== rom.id || romKey[2] !== rom.defaultRomVersion) {
  fail(`package ${report.romKey} does not match ${values['rom-properties']}` +
    ` (${rom.id}@${rom.defaultRomVersion})`);
}

const allAssets = JSON.parse(readFileSync(values.assets, 'utf8'));
if (!Array.isArray(allAssets) || allAssets.length === 0) fail(`${values.assets}: expected a non-empty array`);
for (const asset of allAssets) {
  if (typeof asset.name !== 'string' || !asset.name) fail(`${values.assets}: an asset has no name`);
  if (!Number.isInteger(asset.bytes) || asset.bytes <= 0) fail(`${values.assets}: ${asset.name} has no byte count`);
  if (!SHA256_PATTERN.test(asset.sha256)) fail(`${values.assets}: ${asset.name} has no sha256`);
}

// The checksum file itself is left out. A checksum of the list of checksums describes only
// that file, and every reader compares the assets against it anyway.
const assets = allAssets.filter(asset => asset.name !== 'SHA256SUMS');

let entries = [];
try {
  entries = JSON.parse(readFileSync(values.file, 'utf8'));
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
}
if (!Array.isArray(entries)) fail(`${values.file}: expected a JSON array`);
entries.forEach((item, index) => {
  if (!item || typeof item !== 'object' || typeof item.version !== 'string'
    || !VERSION_PATTERN.test(item.version)) {
    fail(`${values.file}: entry ${index} has no version in vX.Y.Z form; fix the file by hand`);
  }
  if (typeof item.tag !== 'string' || !TAG_PATTERN.test(item.tag)) {
    fail(`${values.file}: entry ${index} has no tag in vX.Y.Z or vX.Y.Z-suffix form; fix the file by hand`);
  }
});

const entry = {
  version,
  tag: values.tag,
  title: values.title,
  romVersion: romKey[2],
  romKey: report.romKey,
  payloadSha256: report.payloadSha256,
  payloadBytes: report.payloadBytes,
  payloadFiles: report.payloadFiles,
  commit: values.commit,
  runUrl: values['run-url'] ?? null,
  publishedAt: new Date().toISOString(),
  assets,
};

const replaced = entries.filter(item => item.tag === tag).length;
const updated = [
  ...entries.filter(item => item.tag !== tag),
  entry,
].slice(-maxEntries);

writeFileSync(values.file, `${JSON.stringify(updated, null, 2)}\n`, 'utf8');
console.log(`${values.file}: ${replaced} entry for ${tag} replaced, ${updated.length} entries kept`);