import { readFile } from "node:fs/promises";

const path = process.argv[2];
if (!path) {
  throw new Error("usage: node scripts/validate-package-manifest.mjs <manifest.json>");
}

const manifest = JSON.parse(await readFile(path, "utf8"));
assert(manifest !== null && typeof manifest === "object" && !Array.isArray(manifest),
  "package manifest must be an object");
assert(manifest.schemaVersion === 1 || manifest.schemaVersion === 2, "schemaVersion must be 1 or 2");
if (manifest.schemaVersion === 1) {
  validateLegacyManifest(manifest);
} else {
  validateUniversalManifest(manifest);
}
if (Object.hasOwn(manifest, "requirements")) {
  normalizeRequirements(manifest.requirements, "requirements");
}
process.stdout.write(`valid package manifest: ${manifest.id}@${manifest.version}\n`);

function validateLegacyManifest(manifest) {
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
}

function validateUniversalManifest(manifest) {
  assertExactKeys(manifest, [
    "schemaVersion", "id", "name", "version", "provider", "summary", "source", "entrypoint", "support"
  ], "package manifest", ["requirements"]);
  assert(typeof manifest.id === "string" && /^[a-z0-9_.-]+:[a-z0-9_./-]+(?![\s\S])/.test(manifest.id),
    "id must be a stable namespaced Extension ID");
  for (const field of ["name", "version", "provider", "summary", "source"]) {
    assertNonBlankString(manifest[field], field);
  }
  assert(typeof manifest.entrypoint === "string"
    && /^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*(?![\s\S])/.test(manifest.entrypoint),
    "entrypoint must be an explicit Java class name");

  const support = manifest.support;
  assertExactKeys(support, [
    "targets", "minimumJavaVersion", "requiredHostFeatures", "validatedTargetIds"
  ], "support");
  assert(Number.isInteger(support.minimumJavaVersion) && support.minimumJavaVersion >= 8
    && support.minimumJavaVersion <= 2147483647, "support.minimumJavaVersion must be an integer at least 8 within Java int bounds");
  for (const field of ["requiredHostFeatures", "validatedTargetIds"]) {
    assertUniqueStrings(support[field], `support.${field}`);
    // ApiValidation.ids(..., false) accepts tokens and namespaced tokens, including future IDs.
    assert(support[field].every((id) => /^[a-z0-9][a-z0-9_.-]*(?::[a-z0-9_][a-z0-9_./-]*)?(?![\s\S])/.test(id)),
      `support.${field} must contain exact valid IDs`);
  }

  const targetFields = ["loader", "minecraftVersionRange", "openAllayVersionRange", "openAllayApiVersionRange"];
  assert(Array.isArray(support.targets) && support.targets.length > 0, "support.targets must be a non-empty array");
  const coordinates = new Set();
  for (const target of support.targets) {
    assertExactKeys(target, targetFields, "support target");
    assert(typeof target.loader === "string" && /^[a-z][a-z0-9_.-]*(?![\s\S])/.test(target.loader),
      "support target loader must be a lowercase loader ID");
    // Validate declarations only. The core owns Maven range parsing and compatibility matching.
    for (const field of targetFields.slice(1)) {
      assertNonBlankString(target[field], `support target ${field}`);
    }
    const coordinate = JSON.stringify(targetFields.map((field) => field === "loader" ? target[field] : target[field].trim()));
    assert(!coordinates.has(coordinate), "support.targets must contain unique coordinates");
    coordinates.add(coordinate);
  }
}

function assertNonBlankString(value, label) {
  assert(typeof value === "string" && value.trim().length > 0, `${label} must be a non-blank string`);
}

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
