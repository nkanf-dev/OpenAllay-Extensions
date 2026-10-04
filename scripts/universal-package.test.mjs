import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import test from "node:test";

const exec = promisify(execFile);
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const builderPath = "extensions/minecraft-builder/universal/src/main/resources/META-INF/openallay-extension.json";
const builderText = await readFile(join(root, builderPath), "utf8");
const builder = JSON.parse(builderText);
const packageSchema = JSON.parse(await readFile(join(root, "schema/package-manifest.schema.json"), "utf8"));

async function validateManifest(value) {
  const temp = await mkdtemp(join(tmpdir(), "openallay-universal-package-test-"));
  try {
    const path = join(temp, "manifest.json");
    const text = typeof value === "string" ? value : JSON.stringify(value);
    await writeFile(path, text);
    try {
      const result = await exec(process.execPath, [join(root, "scripts/validate-package-manifest.mjs"), path]);
      return result.stdout;
    } finally {
      assert.equal(await readFile(path, "utf8"), text, "validation must not rewrite the package");
    }
  } finally {
    await rm(temp, { recursive: true, force: true });
  }
}

function changed(change) {
  const value = structuredClone(builder);
  change(value);
  return value;
}

function successfulOutput(value) {
  return `valid package manifest: ${value.id}@${value.version}\n`;
}

test("the actual universal Builder source manifest has the schema 2 declaration shape", async () => {
  assert.equal(builder.schemaVersion, 2);
  assert.equal(builder.id, "openallay:builder", "the OpenAllay namespace is not forbidden");
  assert.equal(builder.support.minimumJavaVersion, 8);
  assert.equal(await validateManifest(builderText), successfulOutput(builder));
  assert.equal(await readFile(join(root, builderPath), "utf8"), builderText);
});

test("future loaders and noncontiguous 1.12 targets are declarations, not game-validation proof", async () => {
  const value = changed((manifest) => {
    manifest.support.targets = [
      { loader: "future-loader", minecraftVersionRange: "[1.12,1.13)",
        openAllayVersionRange: "[0.4.1,)", openAllayApiVersionRange: "[0.3.0,0.4.0)" },
      { loader: "future-loader", minecraftVersionRange: "[26.2]",
        openAllayVersionRange: "[0.4.1,)", openAllayApiVersionRange: "[0.3.0,0.4.0)" }
    ];
    manifest.support.requiredHostFeatures = ["1future", "namespace:_host", "future:host/path"];
    manifest.support.validatedTargetIds = [];
    manifest.requirements = {
      capabilities: ["unknown.future:host-api"], extensions: ["unknown:extension"], skills: ["future-workflow"]
    };
  });
  assert.equal(await validateManifest(value), successfulOutput(value));
  assert.deepEqual(value.support.validatedTargetIds, [], "declared compatibility must not fabricate validation facts");
  assert.equal(value.support.targets.length, 2, "the gap between separate target ranges is not filled");
});

test("validated target IDs are syntax-only separate facts, not authority or support gates", async () => {
  const value = changed((manifest) => {
    manifest.support.validatedTargetIds = ["future:target/unknown", "fabric-26.2"];
  });
  assert.equal(await validateManifest(value), successfulOutput(value));
});

for (const requirements of [undefined, {}, { capabilities: [], extensions: [], skills: [] }]) {
  test(`universal requirements remain optional advisory declarations: ${JSON.stringify(requirements)}`, async () => {
    const value = changed((manifest) => {
      if (requirements === undefined) delete manifest.requirements;
      else manifest.requirements = requirements;
    });
    assert.equal(await validateManifest(value), successfulOutput(value));
  });
}

for (const relativePath of [
  "examples/openallay-extension.json",
  "examples/hello-extension/src/fabric/resources/META-INF/openallay-extension.json",
  "examples/hello-extension/src/neoforge/resources/META-INF/openallay-extension.json"
]) {
  test(`published loader schema 1 keeps its previous output: ${relativePath}`, async () => {
    const text = await readFile(join(root, relativePath), "utf8");
    const value = JSON.parse(text);
    assert.equal(value.schemaVersion, 1);
    assert.equal(await validateManifest(text), successfulOutput(value));
    assert.equal(await readFile(join(root, relativePath), "utf8"), text);
  });
}

const invalid = [];
for (const schemaVersion of [0, 3, null, "2", true, 2.5]) {
  invalid.push([`schema version ${JSON.stringify(schemaVersion)}`, changed((value) => { value.schemaVersion = schemaVersion; }), /schemaVersion/]);
}
for (const [label, value] of [["null", null], ["array", []], ["boolean", true], ["string", "manifest"]]) {
  invalid.push([`root ${label}`, typeof value === "string" ? JSON.stringify(value) : value, /package manifest/]);
}
for (const field of ["schemaVersion", "id", "name", "version", "provider", "summary", "source", "entrypoint", "support"]) {
  invalid.push([`missing ${field}`, changed((value) => { delete value[field]; }), /schemaVersion|fields do not match schema/]);
}
for (const field of ["id", "name", "version", "provider", "summary", "source", "entrypoint", "support"]) {
  invalid.push([`${field} null`, changed((value) => { value[field] = null; }), /object|string|ID|entrypoint/]);
  invalid.push([`${field} wrong type`, changed((value) => { value[field] = 42; }), /object|string|ID|entrypoint/]);
}
for (const field of ["name", "version", "provider", "summary", "source"]) {
  invalid.push([`${field} blank`, changed((value) => { value[field] = " \t\n"; }), /non-blank string/]);
}
for (const id of ["builder", "OpenAllay:builder", "openallay:builder\n", "openallay:builder "]) {
  invalid.push([`invalid ID ${JSON.stringify(id)}`, changed((value) => { value.id = id; }), /Extension ID/]);
}
for (const entrypoint of ["", "example..Builder", ".Builder", "example.Builder-Extension", "1Builder", "example.Builder\n"]) {
  invalid.push([`invalid entrypoint ${JSON.stringify(entrypoint)}`, changed((value) => { value.entrypoint = entrypoint; }), /entrypoint/]);
}
for (const [field, extra] of Object.entries({
  unexpected: true, loaders: ["fabric"], modIds: ["builder"], packageType: "universal",
  minecraftVersionRange: "26.2", openAllayVersionRange: "[0.4.1,)", openAllayApiVersionRange: "[0.3.0,0.4.0)"
})) {
  invalid.push([`unknown or legacy root field ${field}`, changed((value) => { value[field] = extra; }), /fields do not match schema/]);
}
for (const field of ["targets", "minimumJavaVersion", "requiredHostFeatures", "validatedTargetIds"]) {
  invalid.push([`missing support ${field}`, changed((value) => { delete value.support[field]; }), /fields do not match schema/]);
}
invalid.push(["unknown support member", changed((value) => { value.support.unexpected = []; }), /fields do not match schema/]);
for (const minimum of [7, 8.5, "8", null, true, 2147483648]) {
  invalid.push([`minimum Java ${JSON.stringify(minimum)}`, changed((value) => { value.support.minimumJavaVersion = minimum; }), /minimumJavaVersion/]);
}
for (const targets of [[], null, {}, "fabric", [null], [true], [[]]]) {
  invalid.push([`invalid targets ${JSON.stringify(targets)}`, changed((value) => { value.support.targets = targets; }), /targets|support target/]);
}
for (const field of ["loader", "minecraftVersionRange", "openAllayVersionRange", "openAllayApiVersionRange"]) {
  invalid.push([`missing target ${field}`, changed((value) => { delete value.support.targets[0][field]; }), /fields do not match schema/]);
  for (const item of [null, 42, ""]) {
    invalid.push([`invalid target ${field} ${JSON.stringify(item)}`, changed((value) => { value.support.targets[0][field] = item; }), /loader|non-blank string/]);
  }
}
for (const loader of ["Fabric", "future:loader", "1loader", "future loader", "fabric\n"]) {
  invalid.push([`invalid loader ${JSON.stringify(loader)}`, changed((value) => { value.support.targets[0].loader = loader; }), /loader/]);
}
invalid.push(["unknown target member", changed((value) => { value.support.targets[0].unexpected = true; }), /fields do not match schema/]);
invalid.push(["repeated target coordinate", changed((value) => {
  const target = value.support.targets[0];
  value.support.targets.push({
    openAllayApiVersionRange: target.openAllayApiVersionRange, loader: target.loader,
    openAllayVersionRange: target.openAllayVersionRange, minecraftVersionRange: target.minecraftVersionRange
  });
}), /unique coordinates/]);
for (const field of ["requiredHostFeatures", "validatedTargetIds"]) {
  for (const ids of [null, "future:host", {}, ["future:host", "future:host"], [42], [""], ["future:host\n"], ["Future:host"], ["future:-host"]]) {
    invalid.push([`invalid support ${field} ${JSON.stringify(ids)}`, changed((value) => { value.support[field] = ids; }), /support\./]);
  }
}
for (const requirements of [null, [], true, { unknown: [] }, { capabilities: ["future:host", "future:host"] },
  { extensions: ["example:extension", "example:extension"] }, { skills: ["example-workflow", "example-workflow"] },
  { capabilities: ["future:host\n"] }, { extensions: ["example"] }, { skills: ["bad_skill"] }, { skills: ["a".repeat(65)] }]) {
  invalid.push([`invalid universal requirements ${JSON.stringify(requirements)}`, changed((value) => { value.requirements = requirements; }), /requirements/]);
}
for (const [label, value, pattern] of invalid) {
  test(`reject ${label}`, async () => {
    await assert.rejects(validateManifest(value), pattern);
  });
}

test("schema branches keep exact independent external shapes", () => {
  assert.equal(packageSchema.oneOf.length, 2);
  const legacy = packageSchema.oneOf.find((branch) => branch.properties.schemaVersion.const === 1);
  const universal = packageSchema.oneOf.find((branch) => branch.properties.schemaVersion.const === 2);
  assert.ok(legacy);
  assert.ok(universal);
  assert.equal(legacy.additionalProperties, false);
  assert.ok(legacy.required.includes("loaders"));
  assert.ok(legacy.required.includes("modIds"));
  assert.equal(universal.additionalProperties, false);
  assert.deepEqual([...universal.required].sort(), [
    "schemaVersion", "id", "name", "version", "provider", "summary", "source", "entrypoint", "support"
  ].sort());
  assert.deepEqual(Object.keys(universal.properties).sort(), [...universal.required, "requirements"].sort());
  assert.deepEqual(universal.properties.requirements, { $ref: "#/$defs/requirements" });
  assert.deepEqual(universal.properties.support, { $ref: "#/$defs/support" });
  assert.match(builder.id, new RegExp(universal.properties.id.pattern));
  assert.match(builder.entrypoint, new RegExp(universal.properties.entrypoint.pattern));
  assert.doesNotMatch("example.Builder\n", new RegExp(universal.properties.entrypoint.pattern));
  const support = packageSchema.$defs.support;
  assert.equal(support.additionalProperties, false);
  assert.deepEqual([...support.required].sort(), ["targets", "minimumJavaVersion", "requiredHostFeatures", "validatedTargetIds"].sort());
  assert.equal(support.properties.targets.minItems, 1);
  assert.equal(support.properties.targets.uniqueItems, true);
  assert.deepEqual(support.properties.targets.items, { $ref: "#/$defs/supportTarget" });
  assert.equal(support.properties.minimumJavaVersion.type, "integer");
  assert.equal(support.properties.minimumJavaVersion.minimum, 8);
  assert.equal(support.properties.minimumJavaVersion.maximum, 2147483647);
  const target = packageSchema.$defs.supportTarget;
  assert.equal(target.additionalProperties, false);
  assert.deepEqual([...target.required].sort(), ["loader", "minecraftVersionRange", "openAllayVersionRange", "openAllayApiVersionRange"].sort());
  assert.match("future-loader", new RegExp(target.properties.loader.pattern));
  assert.doesNotMatch("fabric\n", new RegExp(target.properties.loader.pattern));
  for (const field of ["requiredHostFeatures", "validatedTargetIds"]) {
    assert.deepEqual(support.properties[field], { $ref: "#/$defs/supportIds" });
  }
  const ids = packageSchema.$defs.supportIds;
  assert.equal(ids.type, "array");
  assert.equal(ids.uniqueItems, true);
  for (const id of ["1future", "namespace:_host", "future:host/path", "fabric-26.2"]) {
    assert.match(id, new RegExp(ids.items.pattern));
  }
  for (const id of ["Future:host", "future:-host", "future:host\n"]) {
    assert.doesNotMatch(id, new RegExp(ids.items.pattern));
  }
});
