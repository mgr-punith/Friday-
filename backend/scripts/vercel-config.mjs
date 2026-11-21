import fs from "node:fs";
import path from "node:path";

const EXPECTED_SCHEMA = "https://openapi.vercel.sh/vercel.json";
const EXPECTED_REGION = "bom1";
const FUNCTION_ENTRY = "api/index.mjs";
const TYPESCRIPT_FUNCTION_ENTRY = "api/index.ts";
const OUTPUT_DIRECTORY = "public";
const EXPECTED_REWRITES = new Map([
  ["/v1/auth/:path*", "/api/index?fridayRoute=auth/:path*"],
  ["/v1/register", "/api/index?fridayRoute=register"],
  ["/v1/warm", "/api/index?fridayRoute=warm"],
  ["/v1/command", "/api/index?fridayRoute=command"]
]);

export function validateVercelConfig(config, projectDirectory) {
  requireValue(config?.$schema === EXPECTED_SCHEMA, "vercel_schema_mismatch");
  requireValue(config.framework === null, "vercel_framework_must_be_other");
  requireValue(config.buildCommand === "npm run verify", "vercel_verification_required");
  requireValue(config.ignoreCommand === "node scripts/ignore-build.mjs", "vercel_change_detection_required");
  requireValue(config.outputDirectory === OUTPUT_DIRECTORY, "vercel_output_directory_invalid");
  requireValue(
    fs.existsSync(path.join(projectDirectory, OUTPUT_DIRECTORY, "index.html")),
    "vercel_output_source_missing"
  );
  requireValue(
    Array.isArray(config.regions) && config.regions.length === 1 && config.regions[0] === EXPECTED_REGION,
    "vercel_region_must_be_bom1"
  );

  const functionConfig = config.functions?.[FUNCTION_ENTRY];
  requireValue(functionConfig !== undefined, "vercel_function_entry_missing");
  requireValue(functionConfig.maxDuration === 15, "vercel_function_duration_must_be_15");
  requireValue(functionConfig.supportsCancellation === true, "vercel_function_cancellation_required");
  requireValue(fs.existsSync(path.join(projectDirectory, FUNCTION_ENTRY)), "vercel_function_source_missing");
  requireValue(
    fs.readFileSync(path.join(projectDirectory, FUNCTION_ENTRY), "utf8").includes('../dist/vercel.js'),
    "vercel_function_bundle_import_missing"
  );
  requireValue(
    !fs.existsSync(path.join(projectDirectory, TYPESCRIPT_FUNCTION_ENTRY)),
    "vercel_typescript_function_entry_forbidden"
  );

  requireValue(Array.isArray(config.rewrites), "vercel_rewrites_missing");
  requireValue(config.rewrites.length === EXPECTED_REWRITES.size, "vercel_rewrite_count_invalid");

  const seenSources = new Set();
  for (const rewrite of config.rewrites) {
    requireValue(typeof rewrite?.source === "string", "vercel_rewrite_source_invalid");
    requireValue(typeof rewrite?.destination === "string", "vercel_rewrite_destination_invalid");
    requireValue(!seenSources.has(rewrite.source), "vercel_rewrite_source_duplicate");
    seenSources.add(rewrite.source);
    requireValue(EXPECTED_REWRITES.get(rewrite.source) === rewrite.destination, "vercel_rewrite_mapping_invalid");
  }
}

export function readAndValidateVercelConfig(projectDirectory) {
  const configPath = path.join(projectDirectory, "vercel.json");
  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  validateVercelConfig(config, projectDirectory);
}

function requireValue(condition, code) {
  if (!condition) {
    throw new Error(code);
  }
}
