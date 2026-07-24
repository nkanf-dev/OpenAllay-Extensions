import { readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const catalogPath = resolve(root, "catalog.json");
const catalog = JSON.parse(await readFile(catalogPath, "utf8"));

assertExactKeys(catalog, ["schemaVersion", "kind", "generatedAt", "extensions"], "catalog");
assert(catalog.schemaVersion === 1, "schemaVersion must be 1");
assert(catalog.kind === "extension", "kind must be extension");
assert(!Number.isNaN(Date.parse(catalog.generatedAt)), "generatedAt must be ISO-8601");
assert(Array.isArray(catalog.extensions), "extensions must be an array");

const ids = new Set();
const entries = catalog.extensions.map((entry) => {
  assertExactKeys(
    entry,
    [
      "id",
      "name",
      "version",
      "provider",
      "summary",
      "loaders",
      "minecraftVersionRange",
      "openAllayApiVersionRange",
      "artifact",
      "sha256",
      "modIds",
      "source"
    ],
    "extension"
  );
  for (const field of [
    "id",
    "name",
    "version",
    "provider",
    "summary",
    "minecraftVersionRange",
    "openAllayApiVersionRange",
    "artifact",
    "sha256",
    "source"
  ]) {
    assert(typeof entry[field] === "string" && entry[field].length > 0, `${field} must be non-empty`);
  }
  assert(!ids.has(entry.id), `duplicate extension id: ${entry.id}`);
  ids.add(entry.id);
  assert(entry.artifact.startsWith("https://"), `${entry.id}: artifact must use HTTPS`);
  assert(/^[0-9a-f]{64}$/.test(entry.sha256), `${entry.id}: invalid SHA-256`);
  assertUniqueStrings(entry.loaders, `${entry.id}: loaders`);
  assertUniqueStrings(entry.modIds, `${entry.id}: modIds`);
  assert(entry.modIds.length > 0, `${entry.id}: modIds must not be empty`);
  return {
    ...entry,
    loaders: [...entry.loaders].sort(),
    modIds: [...entry.modIds].sort()
  };
}).sort((left, right) => left.id.localeCompare(right.id) || left.version.localeCompare(right.version));

const normalized = `${JSON.stringify({ ...catalog, extensions: entries }, null, 2)}\n`;
await writeFile(catalogPath, normalized, "utf8");

const digest = createHash("sha256").update(normalized).digest("hex");
process.stdout.write(`catalog entries=${entries.length} sha256=${digest}\n`);

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
