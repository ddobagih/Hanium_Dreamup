import type { KnownTwoModelClassName, TwoModelDetection } from "@/types/inference-v2";

type PriorityGroup =
  | "tactile_damage"
  | "vehicle"
  | "vulnerable_road_user"
  | "low_context"
  | "unknown";

export type TwoModelPrioritySelection = {
  primary: TwoModelDetection | null;
  secondary?: TwoModelDetection;
};

type RankedDetection = {
  detection: TwoModelDetection;
  rank: number;
  group: PriorityGroup;
  index: number;
};

const CLASS_PRIORITY: Record<KnownTwoModelClassName, { rank: number; group: PriorityGroup }> = {
  tactile_damage_area: { rank: 0, group: "tactile_damage" },
  damaged_tactile_block: { rank: 1, group: "tactile_damage" },
  car: { rank: 2, group: "vehicle" },
  bus: { rank: 2, group: "vehicle" },
  truck: { rank: 2, group: "vehicle" },
  motorcycle: { rank: 2, group: "vehicle" },
  person: { rank: 3, group: "vulnerable_road_user" },
  bicycle: { rank: 3, group: "vulnerable_road_user" },
  "traffic light": { rank: 4, group: "low_context" },
  normal_tactile_block: { rank: 4, group: "low_context" },
  bench: { rank: 4, group: "low_context" }
};

const UNKNOWN_PRIORITY = { rank: 5, group: "unknown" as const };

function normalizeClassName(className: string): string {
  return className.trim().toLowerCase();
}

function priorityFor(detection: TwoModelDetection): { rank: number; group: PriorityGroup } {
  return CLASS_PRIORITY[normalizeClassName(detection.class_name) as KnownTwoModelClassName] ?? UNKNOWN_PRIORITY;
}

function compareRankedDetections(a: RankedDetection, b: RankedDetection): number {
  if (a.rank !== b.rank) {
    return a.rank - b.rank;
  }

  if (a.detection.confidence !== b.detection.confidence) {
    return b.detection.confidence - a.detection.confidence;
  }

  return a.index - b.index;
}

export function selectTwoModelPriorityDetections(
  detections: readonly TwoModelDetection[]
): TwoModelPrioritySelection {
  if (detections.length === 0) {
    return { primary: null };
  }

  const ranked = detections
    .map((detection, index): RankedDetection => ({
      detection,
      index,
      ...priorityFor(detection)
    }))
    .sort(compareRankedDetections);

  const primary = ranked[0];
  const secondary = ranked.find(
    (candidate) => candidate.index !== primary.index && candidate.group !== primary.group
  );

  return {
    primary: primary.detection,
    ...(secondary ? { secondary: secondary.detection } : {})
  };
}
