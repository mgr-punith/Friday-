export interface EvaluationObservation {
  readonly passed: boolean;
  readonly completed: boolean;
  readonly durationMs?: number;
  readonly inputBytes?: number;
  readonly stages?: Readonly<Record<string, number>>;
  readonly failure?: string;
}

export interface LatencySummary {
  readonly samples: number;
  readonly medianMs: number | null;
  readonly p95Ms: number | null;
  readonly maxMs: number | null;
}

export interface BenchmarkSummary {
  readonly provider: string;
  readonly models: readonly string[];
  readonly cases: number;
  readonly passed: number;
  readonly failed: number;
  readonly accuracyPercent: number;
  readonly completedCases: number;
  readonly completedAccuracyPercent: number;
  readonly latency: LatencySummary;
  readonly upload: SizeSummary;
  readonly stages: Readonly<Record<string, LatencySummary>>;
  readonly failures: Readonly<Record<string, number>>;
}

export interface SizeSummary {
  readonly samples: number;
  readonly medianBytes: number | null;
  readonly p95Bytes: number | null;
  readonly maxBytes: number | null;
}

export function buildBenchmarkSummary(
  provider: string,
  models: readonly string[],
  observations: readonly EvaluationObservation[]
): BenchmarkSummary {
  const passed = observations.filter((observation) => observation.passed).length;
  const completedCases = observations.filter((observation) => observation.completed).length;
  const durations = observations.flatMap((observation) =>
    observation.durationMs === undefined ? [] : [observation.durationMs]
  );
  const inputSizes = observations.flatMap((observation) =>
    observation.inputBytes === undefined ? [] : [observation.inputBytes]
  );
  const stageDurations = new Map<string, number[]>();
  const failures: Record<string, number> = {};

  for (const observation of observations) {
    for (const [stage, durationMs] of Object.entries(observation.stages ?? {})) {
      const values = stageDurations.get(stage) ?? [];
      values.push(durationMs);
      stageDurations.set(stage, values);
    }
    if (observation.failure) failures[observation.failure] = (failures[observation.failure] ?? 0) + 1;
  }

  const stages = Object.fromEntries(
    [...stageDurations.entries()]
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([stage, values]) => [stage, summarizeLatencies(values)])
  );
  const orderedFailures = Object.fromEntries(
    Object.entries(failures).sort(([left], [right]) => left.localeCompare(right))
  );

  return {
    provider,
    models,
    cases: observations.length,
    passed,
    failed: observations.length - passed,
    accuracyPercent: observations.length === 0 ? 0 : (passed / observations.length) * 100,
    completedCases,
    completedAccuracyPercent: completedCases === 0 ? 0 : (passed / completedCases) * 100,
    latency: summarizeLatencies(durations),
    upload: summarizeSizes(inputSizes),
    stages,
    failures: orderedFailures
  };
}

export function summarizeLatencies(values: readonly number[]): LatencySummary {
  if (values.length === 0) {
    return { samples: 0, medianMs: null, p95Ms: null, maxMs: null };
  }
  const sorted = [...values].sort((left, right) => left - right);
  return {
    samples: sorted.length,
    medianMs: percentile(sorted, 0.5),
    p95Ms: percentile(sorted, 0.95),
    maxMs: sorted.at(-1) ?? null
  };
}

export function summarizeSizes(values: readonly number[]): SizeSummary {
  if (values.length === 0) {
    return { samples: 0, medianBytes: null, p95Bytes: null, maxBytes: null };
  }
  const sorted = [...values].sort((left, right) => left - right);
  return {
    samples: sorted.length,
    medianBytes: percentile(sorted, 0.5),
    p95Bytes: percentile(sorted, 0.95),
    maxBytes: sorted.at(-1) ?? null
  };
}

function percentile(sorted: readonly number[], fraction: number): number {
  const index = Math.min(sorted.length - 1, Math.ceil(sorted.length * fraction) - 1);
  return sorted[Math.max(0, index)] ?? 0;
}
