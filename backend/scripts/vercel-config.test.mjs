import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { validateVercelConfig } from "./vercel-config.mjs";

function validConfig() {
  return {
    $schema: "https://openapi.vercel.sh/vercel.json",
    framework: null,
    buildCommand: "npm run verify",
    ignoreCommand: "node scripts/ignore-build.mjs",
    outputDirectory: "public",
    regions: ["bom1"],
    functions: {
      "api/index.mjs": {
        maxDuration: 30,
        supportsCancellation: true
      }
    },
    rewrites: [
      { source: "/v1/register", destination: "/api/index?fridayRoute=register" },
      { source: "/v1/warm", destination: "/api/index?fridayRoute=warm" },
      { source: "/v1/command", destination: "/api/index?fridayRoute=command" },
      { source: "/v1/auth/:path*", destination: "/api/index?fridayRoute=auth/:path*" },
      { source: "/v1/answers/report", destination: "/api/index?fridayRoute=answers/report" },
      { source: "/v1/speech", destination: "/api/index?fridayRoute=speech" }
    ]
  };
}

function projectDirectory() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "friday-vercel-config-"));
  fs.mkdirSync(path.join(directory, "api"));
  fs.mkdirSync(path.join(directory, "public"));
  fs.writeFileSync(
    path.join(directory, "api/index.mjs"),
    'import vercelBundle from "../dist/vercel.js";\nexport default vercelBundle.default;\n'
  );
  fs.writeFileSync(path.join(directory, "public/index.html"), "<!doctype html>\n");
  return directory;
}

test("accepts Friday's required Vercel deployment configuration", (context) => {
  const directory = projectDirectory();
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  assert.doesNotThrow(() => validateVercelConfig(validConfig(), directory));
});

test("rejects a deployment outside Mumbai", (context) => {
  const directory = projectDirectory();
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const config = validConfig();
  config.regions = ["iad1"];
  assert.throws(() => validateVercelConfig(config, directory), /vercel_region_must_be_bom1/);
});

test("deployment cannot bypass backend tests or change-based deployment selection", context => {
  const directory = projectDirectory();
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  assert.throws(() => validateVercelConfig({ ...validConfig(), buildCommand: "npm run build" }, directory), /vercel_verification_required/);
  assert.throws(() => validateVercelConfig({ ...validConfig(), ignoreCommand: "exit 0" }, directory), /vercel_change_detection_required/);
});

test("rejects a missing static output directory", (context) => {
  const directory = projectDirectory();
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const config = validConfig();
  config.outputDirectory = "dist";
  assert.throws(() => validateVercelConfig(config, directory), /vercel_output_directory_invalid/);
});

test("rejects a public route mapped to the wrong handler route", (context) => {
  const directory = projectDirectory();
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const config = validConfig();
  config.rewrites[2].destination = "/api/index?fridayRoute=warm";
  assert.throws(() => validateVercelConfig(config, directory), /vercel_rewrite_mapping_invalid/);
});

test("rejects a TypeScript file in Vercel's API directory", (context) => {
  const directory = projectDirectory();
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  fs.writeFileSync(path.join(directory, "api/index.ts"), "export default {}\n");
  assert.throws(() => validateVercelConfig(validConfig(), directory), /vercel_typescript_function_entry_forbidden/);
});

test("rejects a JavaScript entry that does not load the built bundle", (context) => {
  const directory = projectDirectory();
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  fs.writeFileSync(path.join(directory, "api/index.mjs"), "export default {}\n");
  assert.throws(() => validateVercelConfig(validConfig(), directory), /vercel_function_bundle_import_missing/);
});
