import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const projectDirectory = fileURLToPath(new URL("../", import.meta.url));

function isolatedBuild(context) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "friday-sam-build-"));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  for (const name of ["Makefile", "package.json", "build.mjs", "src"]) {
    fs.cpSync(path.join(projectDirectory, name), path.join(directory, name), { recursive: true });
  }
  // Reuse npm ci's dependencies without copying local output, config or secrets.
  fs.symlinkSync(path.join(projectDirectory, "node_modules"), path.join(directory, "node_modules"), "dir");
  const artifacts = path.join(directory, "SAM artifacts", "FridayFunction");
  return {
    directory,
    artifacts,
    run: () => spawnSync("make", ["build-FridayFunction", `ARTIFACTS_DIR=${artifacts}`], {
      cwd: directory,
      encoding: "utf8",
      timeout: 60_000
    })
  };
}

test("SAM make target builds and packages the handler without an earlier backend build", context => {
  const fixture = isolatedBuild(context);
  assert.equal(fs.existsSync(path.join(fixture.directory, "dist")), false);

  const result = fixture.run();
  assert.equal(result.error, undefined);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.deepEqual(fs.readdirSync(fixture.artifacts), ["handler.js"]);
  const handler = path.join(fixture.artifacts, "handler.js");
  const bytes = fs.readFileSync(handler);
  assert.ok(bytes.length > 0 && bytes.length <= 5_000_000);
  assert.deepEqual(bytes, fs.readFileSync(path.join(fixture.directory, "dist/handler.js")));
  const syntax = spawnSync(process.execPath, ["--check", handler], { encoding: "utf8", timeout: 10_000 });
  assert.equal(syntax.status, 0, syntax.stderr);
});

test("SAM make target stops on compilation failure instead of packaging a stale handler", context => {
  const fixture = isolatedBuild(context);
  fs.mkdirSync(path.join(fixture.directory, "dist"));
  fs.writeFileSync(path.join(fixture.directory, "dist/handler.js"), "stale bundle\n");
  fs.writeFileSync(path.join(fixture.directory, "src/handler.ts"), "export function handler( {\n");

  const result = fixture.run();
  assert.equal(result.error, undefined);
  assert.notEqual(result.status, 0);
  assert.equal(fs.existsSync(path.join(fixture.artifacts, "handler.js")), false);
});
