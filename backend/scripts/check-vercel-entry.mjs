const requiredEnvironment = {
  GEMINI_MODEL: "gemini-test",
  GEMINI_API_KEY: "1234567890123456",
  JWT_SIGNING_KEY: "12345678901234567890123456789012",
  UPSTASH_REDIS_REST_URL: "https://example.upstash.io",
  UPSTASH_REDIS_REST_TOKEN: "1234567890123456"
};

Object.assign(process.env, requiredEnvironment);

const entry = await import("../api/index.mjs");
if (typeof entry.default?.fetch !== "function") {
  throw new Error("vercel_function_fetch_export_missing");
}

console.log("Vercel JavaScript function entry passed");
