import path from "node:path";
import { fileURLToPath } from "node:url";
import { readAndValidateVercelConfig } from "./vercel-config.mjs";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const projectDirectory = path.dirname(scriptDirectory);

readAndValidateVercelConfig(projectDirectory);
console.log("Vercel configuration passed");
