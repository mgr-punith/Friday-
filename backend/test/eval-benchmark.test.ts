import assert from "node:assert/strict";
import test from "node:test";
import { buildBenchmarkSummary, summarizeLatencies, summarizeSizes } from "../eval/benchmark";

test("summarizes benchmark accuracy, failures, and percentile latency", () => {
  const summary = buildBenchmarkSummary("provider", ["model"], [
    { passed: true, completed: true, durationMs: 100, inputBytes: 1000, stages: { model: 80 } },
    { passed: false, completed: true, durationMs: 200, inputBytes: 2000, stages: { model: 180 }, failure: "wrong_result" },
    { passed: false, completed: false, durationMs: 500, inputBytes: 5000, failure: "timeout" },
    { passed: true, completed: true, durationMs: 400, inputBytes: 4000, stages: { model: 350 } }
  ]);

  assert.equal(summary.passed, 2);
  assert.equal(summary.failed, 2);
  assert.equal(summary.accuracyPercent, 50);
  assert.equal(summary.completedCases, 3);
  assert.equal(Math.abs(summary.completedAccuracyPercent - (200 / 3)) < 0.000001, true);
  assert.deepEqual(summary.latency, { samples: 4, medianMs: 200, p95Ms: 500, maxMs: 500 });
  assert.deepEqual(summary.upload, { samples: 4, medianBytes: 2000, p95Bytes: 5000, maxBytes: 5000 });
  assert.deepEqual(summary.stages.model, { samples: 3, medianMs: 180, p95Ms: 350, maxMs: 350 });
  assert.deepEqual(summary.failures, { timeout: 1, wrong_result: 1 });
});

test("returns empty upload fields for text-only runs", () => {
  assert.deepEqual(summarizeSizes([]), {
    samples: 0,
    medianBytes: null,
    p95Bytes: null,
    maxBytes: null
  });
});

test("returns empty latency fields when every request fails before a response", () => {
  assert.deepEqual(summarizeLatencies([]), {
    samples: 0,
    medianMs: null,
    p95Ms: null,
    maxMs: null
  });
});
