// Facts the release text and the release index both need, read from the workspace.
//
// Two sources: rom/rom.properties.yaml decides the artifact file name, and
// dist/pack-<id>-<version>.json is the packer's own report for the package being published.
// Reading both through one module keeps the notes and the index from disagreeing about a
// version or a hash, which is what a downstream consumer matches a download against.

import { readFileSync } from 'node:fs';

// rom.properties.yaml is a flat map of scalars. A nested or indented key means the schema
// grew, and guessing at it here would silently change which version the artifacts carry.
export function readRomProperties(filePath) {
  const values = {};
  readFileSync(filePath, 'utf8').split(/\r?\n/).forEach((line, index) => {
    if (line.trim() === '' || line.trimStart().startsWith('#')) return;
    if (/^[ \t]/.test(line)) {
      throw new Error(`${filePath}:${index + 1}: indented key, this reader only accepts top-level scalars`);
    }
    const match = /^([A-Za-z][A-Za-z0-9]*):[ \t]*(.*)$/.exec(line);
    if (!match) throw new Error(`${filePath}:${index + 1}: not a key: value line: ${line}`);
    const key = match[1];
    if (Object.hasOwn(values, key)) throw new Error(`${filePath}:${index + 1}: duplicate key ${key}`);
    let value = match[2].trim();
    if (value.length >= 2 && (value[0] === '"' || value[0] === "'") && value.at(-1) === value[0]) {
      value = value.slice(1, -1);
    }
    values[key] = value;
  });
  return values;
}

export function readRomVersion(filePath) {
  const values = readRomProperties(filePath);
  const version = values.defaultRomVersion;
  if (!version) throw new Error(`${filePath}: defaultRomVersion is missing, the artifact name comes from it`);
  return version;
}

const REPORT_FIELDS = ['romKey', 'payloadSha256', 'payloadBytes', 'payloadFiles', 'zipBytes', 'libMode'];

export function readPackReport(filePath) {
  const report = JSON.parse(readFileSync(filePath, 'utf8'));
  for (const field of REPORT_FIELDS) {
    if (report[field] === undefined) throw new Error(`${filePath}: pack report has no ${field}`);
  }
  if (!/^[0-9a-f]{64}$/.test(report.payloadSha256)) {
    throw new Error(`${filePath}: payloadSha256 is not a sha256 hex string`);
  }
  return report;
}
