import esbuild from "esbuild";
import fs from "node:fs/promises";

const MAX_BUNDLE_BYTES = 5_000_000;

await fs.rm("dist", { recursive: true, force: true });

await Promise.all([
  build("src/handler.ts", "dist/handler.js"),
  build("src/vercel/index.ts", "dist/vercel.js")
]);

for (const [label, file] of [["Lambda", "dist/handler.js"], ["Vercel", "dist/vercel.js"]]) {
  const { size } = await fs.stat(file);
  console.log(`${label} bundle: ${size} bytes`);
  if (size > MAX_BUNDLE_BYTES) {
    throw new Error(`${label} bundle exceeds ${MAX_BUNDLE_BYTES} bytes`);
  }
}

async function build(entryPoint, outfile) {
  await esbuild.build({
    entryPoints: [entryPoint],
    outfile,
    bundle: true,
    platform: "node",
    target: "node24",
    format: "cjs",
    minify: true,
    sourcemap: false,
    legalComments: "none",
    logLevel: "info"
  });
}
