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
    outputDirectory: "public",
    regions: ["bom1"],
    functions: {
      "api/index.ts": {
        maxDuration: 15,
        supportsCancellation: true
      }
    },
    rewrites: [
      { source: "/v1/register", destination: "/api/index?fridayRoute=register" },
      { source: "/v1/warm", destination: "/api/index?fridayRoute=warm" },
      { source: "/v1/command", destination: "/api/index?fridayRoute=command" }
    ]
  };
}

function projectDirectory() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "friday-vercel-config-"));
  fs.mkdirSync(path.join(directory, "api"));
  fs.mkdirSync(path.join(directory, "public"));
  fs.writeFileSync(path.join(directory, "api/index.ts"), "export default {}\n");
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
