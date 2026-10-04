import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import test from "node:test";

const exec = promisify(execFile);
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const manifest = JSON.parse(await readFile(join(root, "examples/openallay-extension.json"), "utf8"));
const catalogText = await readFile(join(root, "catalog.json"), "utf8");
const catalog = JSON.parse(catalogText);
const declared = {
  capabilities: ["unknown.future:host-api", "javascript.unrestricted", "1future", "1future:host/path", "namespace:_host"],
  extensions: ["example:viewer/nested", "other_mod:api"],
  skills: ["example-workflow", "a".repeat(64)]
};

async function validateManifest(value) {
  const temp = await mkdtemp(join(tmpdir(), "openallay-manifest-test-"));
  try {
    const path = join(temp, "manifest.json");
    const text = JSON.stringify(value);
    await writeFile(path, text);
    const result = await exec(process.execPath, [join(root, "scripts/validate-package-manifest.mjs"), path]);
    assert.equal(await readFile(path, "utf8"), text, "validation must not rewrite the package");
    return result.stdout;
  } finally {
    await rm(temp, { recursive: true, force: true });
  }
}

async function buildCatalog(value) {
  const temp = await mkdtemp(join(tmpdir(), "openallay-catalog-test-"));
  try {
    await mkdir(join(temp, "scripts"));
    const script = join(temp, "scripts/build-catalog.mjs");
    await copyFile(join(root, "scripts/build-catalog.mjs"), script);
    const path = join(temp, "catalog.json");
    const input = typeof value === "string" ? value : `${JSON.stringify(value, null, 2)}\n`;
    await writeFile(path, input);
    try {
      await exec(process.execPath, [script]);
    } catch (error) {
      assert.equal(await readFile(path, "utf8"), input, "invalid input must not be published");
      throw error;
    }
    const output = await readFile(path, "utf8");
    await exec(process.execPath, [script]);
    assert.equal(await readFile(path, "utf8"), output, "catalog writes must be stable");
    return output;
  } finally {
    await rm(temp, { recursive: true, force: true });
  }
}

function catalogWith(requirements) {
  const value = structuredClone(catalog);
  value.extensions[0].requirements = requirements;
  return value;
}

test("legacy inputs keep their previous output", async () => {
  assert.equal(await validateManifest(manifest), `valid package manifest: ${manifest.id}@${manifest.version}\n`);
  assert.equal(await buildCatalog(catalogText), catalogText);
});

test("requirements preserve exact unknown IDs without checking availability", async () => {
  await validateManifest({ ...manifest, requirements: declared });
  const result = JSON.parse(await buildCatalog(catalogWith(declared)));
  assert.deepEqual(result.extensions[0].requirements, declared);
});

for (const requirements of [{}, { capabilities: [] }, { capabilities: [], extensions: [], skills: [] }]) {
  test(`empty requirements are accepted and omitted on write: ${JSON.stringify(requirements)}`, async () => {
    await validateManifest({ ...manifest, requirements });
    assert.equal(await buildCatalog(catalogWith(requirements)), catalogText);
  });
}

test("absent requirement members mean empty and empty members are omitted on write", async () => {
  const requirements = { skills: ["example-workflow"], capabilities: [] };
  await validateManifest({ ...manifest, requirements });
  const result = JSON.parse(await buildCatalog(catalogWith(requirements)));
  assert.deepEqual(result.extensions[0].requirements, { skills: ["example-workflow"] });
});

const invalidRequirements = [
  ["null object", null],
  ["array object", []],
  ["boolean object", true],
  ["string object", "capabilities"],
  ["unknown member", { capability: [] }],
  ["prototype member", JSON.parse('{"__proto__": []}')],
  ["unknown member alongside valid declaration", { capabilities: ["unknown-future-api"], unexpected: [] }]
];
for (const [field, validId, invalidIds] of [
  ["capabilities", "future:host-api", ["", " ", " future:host-api", "future:host-api\n", "Future:host", "future/api", "future*", "future:host:api", "future:-host", 42]],
  ["extensions", "example:viewer", ["", "example", ":viewer", "example:", "Example:viewer", "example:viewer\n", "example:viewer ", "example:viewer*", 42]],
  ["skills", "example-workflow", ["", " ", "Example", "example_workflow", "-example", "example-", "example--workflow", "example\n", "a".repeat(65), 42]]
]) {
  invalidRequirements.push([`${field} null`, { [field]: null }]);
  invalidRequirements.push([`${field} not an array`, { [field]: validId }]);
  invalidRequirements.push([`${field} object value`, { [field]: {} }]);
  invalidRequirements.push([`${field} duplicate`, { [field]: [validId, validId] }]);
  for (const invalid of invalidIds) {
    invalidRequirements.push([`${field} invalid ${JSON.stringify(invalid)}`, { [field]: [invalid] }]);
  }
}
for (const [label, requirements] of invalidRequirements) {
  test(`reject ${label} in both formats`, async () => {
    await assert.rejects(validateManifest({ ...manifest, requirements }), /requirements/);
    await assert.rejects(buildCatalog(catalogWith(requirements)), /requirements/);
  });
}

test("unknown fields and versions remain rejected", async () => {
  await assert.rejects(validateManifest({ ...manifest, unexpected: [] }), /fields do not match schema/);
  await assert.rejects(validateManifest({ ...manifest, schemaVersion: 2 }), /fields do not match schema/);
  const missing = { ...manifest };
  delete missing.source;
  await assert.rejects(validateManifest(missing), /fields do not match schema/);
  await assert.rejects(buildCatalog({ ...catalog, unexpected: [] }), /fields do not match schema/);
  await assert.rejects(buildCatalog({ ...catalog, schemaVersion: 3 }), /schemaVersion must be 2/);
  await assert.rejects(buildCatalog({ ...catalog, requirements: {} }), /fields do not match schema/);
  const unknownEntry = structuredClone(catalog);
  unknownEntry.extensions[0].unexpected = [];
  await assert.rejects(buildCatalog(unknownEntry), /fields do not match schema/);
  const unknownArtifact = structuredClone(catalog);
  unknownArtifact.extensions[0].artifacts[0].requirements = {};
  await assert.rejects(buildCatalog(unknownArtifact), /fields do not match schema/);
});

test("schemas declare the same strict optional requirement contract", async () => {
  const schemas = await Promise.all(["package-manifest", "catalog"].map(async (name) =>
    JSON.parse(await readFile(join(root, `schema/${name}.schema.json`), "utf8"))));
  const [packageSchema, catalogSchema] = schemas;
  const legacyPackageSchema = packageSchema.oneOf.find((branch) => branch.properties.schemaVersion.const === 1);
  assert.ok(legacyPackageSchema, "the published schema 1 contract must remain present");
  assert.equal(legacyPackageSchema.properties.schemaVersion.const, 1);
  assert.equal(catalogSchema.properties.schemaVersion.const, 2);
  for (const [schema, owner] of [[packageSchema, legacyPackageSchema], [catalogSchema, catalogSchema.$defs.extension]]) {
    assert.equal(owner.additionalProperties, false);
    assert.equal(owner.required.includes("requirements"), false);
    assert.deepEqual(owner.properties.requirements, { $ref: "#/$defs/requirements" });
    const requirements = schema.$defs.requirements;
    assert.equal(requirements.type, "object");
    assert.equal(requirements.additionalProperties, false);
    assert.deepEqual(Object.keys(requirements.properties).sort(), ["capabilities", "extensions", "skills"]);
    assert.deepEqual(requirements.required ?? [], []);
    for (const [field, values] of Object.entries(declared)) {
      const rule = requirements.properties[field];
      assert.equal(rule.type, "array");
      assert.equal(rule.uniqueItems, true);
      assert.equal(rule.minItems ?? 0, 0);
      assert.equal(rule.items.type, "string");
      for (const value of values) {
        assert.match(value, new RegExp(rule.items.pattern));
        assert.ok(value.length <= (rule.items.maxLength ?? Infinity));
      }
      for (const [, invalid] of invalidRequirements) {
        if (!invalid || Object.keys(invalid).length !== 1 || !Object.hasOwn(invalid, field)
          || !Array.isArray(invalid[field]) || invalid[field].length !== 1) {
          continue;
        }
        const value = invalid[field][0];
        if (typeof value === "string") {
          assert.ok(!new RegExp(rule.items.pattern).test(value) || value.length > (rule.items.maxLength ?? Infinity),
            `${field} schema must reject ${JSON.stringify(value)}`);
        }
      }
    }
  }
  assert.deepEqual(packageSchema.$defs.requirements, catalogSchema.$defs.requirements);
});
