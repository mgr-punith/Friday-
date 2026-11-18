import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const projectDirectory = path.dirname(scriptDirectory);

test("keeps Node type definitions in Vercel's production install", () => {
  const packageJson = JSON.parse(fs.readFileSync(path.join(projectDirectory, "package.json"), "utf8"));
  const packageLock = JSON.parse(fs.readFileSync(path.join(projectDirectory, "package-lock.json"), "utf8"));

  assert.equal(packageJson.dependencies?.["@types/node"], "24.19.2");
  assert.equal(packageJson.devDependencies?.["@types/node"], undefined);
  assert.equal(packageLock.packages?.["node_modules/@types/node"]?.dev, undefined);
});
