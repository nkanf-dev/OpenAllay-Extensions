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
assertExactKeys(manifest, fields, "package manifest", ["requirements"]);
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
if (Object.hasOwn(manifest, "requirements")) {
  normalizeRequirements(manifest.requirements, "requirements");
}
process.stdout.write(`valid package manifest: ${manifest.id}@${manifest.version}\n`);

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function assertExactKeys(value, expected, label, optional = []) {
  assert(value !== null && typeof value === "object" && !Array.isArray(value), `${label} must be an object`);
  const actual = Object.keys(value).filter((key) => !optional.includes(key)).sort();
  const wanted = [...expected].sort();
  assert(JSON.stringify(actual) === JSON.stringify(wanted), `${label} fields do not match schema`);
}

function assertUniqueStrings(value, label) {
  assert(Array.isArray(value), `${label} must be an array`);
  assert(value.every((item) => typeof item === "string" && item.length > 0), `${label} must contain strings`);
  assert(new Set(value).size === value.length, `${label} must contain unique strings`);
}

// Advisory only: validate exact IDs, not their current availability or permissions.
function normalizeRequirements(value, label) {
  const patterns = {
    // Match the whole ID even in schema validators with line-aware end anchors.
    capabilities: /^[a-z0-9][a-z0-9_.-]*(?::[a-z0-9_][a-z0-9_.\/-]*)?(?![\s\S])/,
    extensions: /^[a-z0-9_.-]+:[a-z0-9_.\/-]+(?![\s\S])/,
    skills: /^[a-z0-9]+(?:-[a-z0-9]+)*(?![\s\S])/
  };
  assertExactKeys(value, [], label, Object.keys(patterns));
  const normalized = {};
  for (const [field, pattern] of Object.entries(patterns)) {
    if (!Object.hasOwn(value, field)) {
      continue;
    }
    const ids = value[field];
    assertUniqueStrings(ids, `${label}.${field}`);
    assert(ids.every((id) => pattern.test(id) && (field !== "skills" || id.length <= 64)),
      `${label}.${field} must contain exact valid IDs${field === "skills" ? " (at most 64 characters)" : ""}`);
    if (ids.length > 0) {
      normalized[field] = [...ids];
    }
  }
  return normalized;
}
