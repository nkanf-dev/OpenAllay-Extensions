import { readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const catalogPath = resolve(root, "catalog.json");
const catalog = JSON.parse(await readFile(catalogPath, "utf8"));

assertExactKeys(catalog, ["schemaVersion", "kind", "generatedAt", "extensions"], "catalog");
assert(catalog.schemaVersion === 2, "schemaVersion must be 2");
assert(catalog.kind === "extension", "kind must be extension");
assert(!Number.isNaN(Date.parse(catalog.generatedAt)), "generatedAt must be ISO-8601");
assert(Array.isArray(catalog.extensions), "extensions must be an array");

const identities = new Set();
const entries = catalog.extensions.map((entry) => {
  assertExactKeys(
    entry,
    [
      "id",
      "name",
      "version",
      "provider",
      "summary",
      "minecraftVersionRange",
      "openAllayApiVersionRange",
      "artifacts",
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
    "source"
  ]) {
    assert(typeof entry[field] === "string" && entry[field].length > 0, `${field} must be non-empty`);
  }
  const identity = `${entry.id}\u0000${entry.version}`;
  assert(!identities.has(identity), `duplicate extension identity: ${entry.id}@${entry.version}`);
  identities.add(identity);
  assert(Array.isArray(entry.artifacts) && entry.artifacts.length > 0, `${entry.id}: artifacts must not be empty`);
  const loaders = new Set();
  const artifacts = entry.artifacts.map((artifact) => {
    assertExactKeys(artifact, ["loader", "artifact", "sha256", "modIds"], `${entry.id}: artifact`);
    assert(["fabric", "neoforge"].includes(artifact.loader), `${entry.id}: unsupported loader`);
    assert(!loaders.has(artifact.loader), `${entry.id}: duplicate ${artifact.loader} artifact`);
    loaders.add(artifact.loader);
    assert(
      typeof artifact.artifact === "string" && artifact.artifact.startsWith("https://"),
      `${entry.id}/${artifact.loader}: artifact must use HTTPS`
    );
    assert(
      typeof artifact.sha256 === "string" && /^[0-9a-f]{64}$/.test(artifact.sha256),
      `${entry.id}/${artifact.loader}: invalid SHA-256`
    );
    assertUniqueStrings(artifact.modIds, `${entry.id}/${artifact.loader}: modIds`);
    assert(artifact.modIds.length > 0, `${entry.id}/${artifact.loader}: modIds must not be empty`);
    return {
      ...artifact,
      modIds: [...artifact.modIds].sort()
    };
  }).sort((left, right) => left.loader.localeCompare(right.loader));
  return {
    ...entry,
    artifacts
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
