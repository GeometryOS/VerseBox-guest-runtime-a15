// Hashes the files that a release will carry and writes them as JSON.
//
// Input is the set of files the publish workflow downloaded from the tag build; output is
// [{ name, bytes, sha256 }] with the relative path kept, because the release notes print the
// same list and a reader matches a download against SHA256SUMS with it.
// Files are streamed, since a ROM package is routinely over a gigabyte, and an empty file is
// refused here rather than discovered by whoever installs it.

import { createHash } from 'node:crypto';
import { createReadStream, statSync, writeFileSync } from 'node:fs';
import { parseArgs } from 'node:util';

async function sha256File(filePath) {
  const hash = createHash('sha256');
  await new Promise((resolve, reject) => {
    createReadStream(filePath)
      .on('data', chunk => hash.update(chunk))
      .on('error', reject)
      .on('end', resolve);
  });
  return hash.digest('hex');
}

const { values, positionals } = parseArgs({
  options: { out: { type: 'string' } },
  allowPositionals: true,
});

if (!values.out || positionals.length === 0) {
  console.error('Usage: node collect-release-assets.mjs --out <assets.json> <file...>');
  process.exit(1);
}

const assets = [];
for (const filePath of positionals) {
  const stats = statSync(filePath);
  if (!stats.isFile()) throw new Error(`not a regular file: ${filePath}`);
  if (stats.size === 0) throw new Error(`zero bytes, refusing to publish: ${filePath}`);
  assets.push({
    name: filePath.split('\\').join('/'),
    bytes: stats.size,
    sha256: await sha256File(filePath),
  });
}

assets.sort((left, right) => left.name.localeCompare(right.name));
writeFileSync(values.out, `${JSON.stringify(assets, null, 2)}\n`, 'utf8');
console.log(`collected ${assets.length} assets into ${values.out}`);
