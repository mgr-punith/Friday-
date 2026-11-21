import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

// Vercel: exit 0 skips a deployment; exit 1 builds it.
export function shouldBuild(paths) {
  return paths.some(path => path.startsWith("backend/") && !path.toLowerCase().endsWith(".md"));
}

export function buildDecision(previous, current, run = spawnSync) {
  const valid = value => typeof value === "string" && /^[a-f0-9]{40,64}$/i.test(value) && !/^0+$/.test(value);
  if (!valid(previous) || !valid(current) || previous === current) return true;
  const diff = run("git", ["diff", "--name-only", "--no-renames", "-z", previous, current, "--"], { encoding: "utf8" });
  if (diff.status !== 0 || typeof diff.stdout !== "string") return true;
  return shouldBuild(diff.stdout.split("\0").filter(Boolean));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const build = buildDecision(process.env.VERCEL_GIT_PREVIOUS_SHA, process.env.VERCEL_GIT_COMMIT_SHA);
  console.log(build ? "Backend changed or comparison unavailable: build" : "Backend unchanged: skip deployment");
  process.exitCode = build ? 1 : 0;
}
