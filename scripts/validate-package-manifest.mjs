import { readFile } from "node:fs/promises";

const path = process.argv[2];
if (!path) {
  throw new Error("usage: node scripts/validate-package-manifest.mjs <manifest.json>");
}

const manifest = JSON.parse(await readFile(path, "utf8"));
const fields = [
  "schemaVersion",
  "id",
  "name",
  "version",
  "provider",
  "summary",
  "loaders",
  "minecraftVersionRange",
  "openAllayApiVersionRange",
  "modIds",
  "source"
];
assertExactKeys(manifest, fields, "package manifest");
assert(manifest.schemaVersion === 1, "schemaVersion must be 1");
assert(
  /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(manifest.id),
  "id must be a stable namespaced Extension ID"
);
for (const field of [
  "name",
  "version",
  "provider",
  "summary",
  "minecraftVersionRange",
  "openAllayApiVersionRange",
  "source"
]) {
  assert(
    typeof manifest[field] === "string" && manifest[field].length > 0,
    `${field} must be non-empty`
  );
}
assertUniqueStrings(manifest.loaders, "loaders");
assert(
  manifest.loaders.every((loader) => loader === "fabric" || loader === "neoforge"),
  "loaders must contain only fabric or neoforge"
);
assertUniqueStrings(manifest.modIds, "modIds");
assert(
  manifest.modIds.length > 0
    && manifest.modIds.every((modId) => /^[a-z0-9_.-]+$/.test(modId)),
  "modIds must contain loader mod IDs"
);
process.stdout.write(`valid package manifest: ${manifest.id}@${manifest.version}\n`);

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function assertExactKeys(value, expected, label) {
  assert(value !== null && typeof value === "object" && !Array.isArray(value), `${label} must be an object`);
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  assert(JSON.stringify(actual) === JSON.stringify(wanted), `${label} fields do not match schema`);
}

function assertUniqueStrings(value, label) {
  assert(Array.isArray(value), `${label} must be an array`);
  assert(value.every((item) => typeof item === "string" && item.length > 0), `${label} must contain strings`);
  assert(new Set(value).size === value.length, `${label} must contain unique strings`);
}
