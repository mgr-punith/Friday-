import assert from "node:assert/strict";
import test from "node:test";
import { buildDecision, shouldBuild } from "./ignore-build.mjs";

test("Vercel skips app/docs changes and builds backend runtime, config and dependency changes", () => {
  assert.equal(shouldBuild(["app/src/main/MainActivity.kt", "docs/actions.md", "backend/README.md"]), false);
  for (const path of ["backend/src/auth/api.ts", "backend/vercel.json", "backend/package-lock.json", "backend/scripts/ignore-build.mjs", "backend/public/index.html"]) assert.equal(shouldBuild([path]), true);
});

test("missing history and failed Git comparisons build instead of skipping", () => {
  assert.equal(buildDecision(undefined, "a".repeat(40)), true);
  assert.equal(buildDecision("0".repeat(40), "a".repeat(40)), true);
  assert.equal(buildDecision("a".repeat(40), "a".repeat(40)), true);
  assert.equal(buildDecision("b".repeat(40), "a".repeat(40), () => ({ status: 128 })), true);
});

test("compares both sides of a rename and passes refs as Git arguments", () => {
  const before = "b".repeat(40), after = "a".repeat(40);
  const run = (command, args) => {
    assert.equal(command, "git");
    assert.deepEqual(args, ["diff", "--name-only", "--no-renames", "-z", before, after, "--"]);
    return { status: 0, stdout: "backend/src/old.ts\0app/new.txt\0" };
  };
  assert.equal(buildDecision(before, after, run), true);
  assert.equal(buildDecision(before, after, () => ({ status: 0, stdout: "app/only.kt\0" })), false);
});
