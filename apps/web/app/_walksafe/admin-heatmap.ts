/** Projects report grid clusters into a dependency-free geographic overview for the admin console. */
import type { AdminReportGridCluster, ReportLocationBounds } from "@/lib/report-api";

export type AdminHeatmapCell = {
  cluster: AdminReportGridCluster;
  leftPercent: number;
  topPercent: number;
  widthPercent: number;
  heightPercent: number;
  intensity: number;
  fakeRatio: number;
};

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function buildAdminHeatmapCells(
  clusters: readonly AdminReportGridCluster[],
  bounds: ReportLocationBounds | null
): AdminHeatmapCell[] {
  if (!bounds || clusters.length === 0) {
    return [];
  }
  const latitudeSpan = Math.max(bounds.maxLatitude - bounds.minLatitude, 0.000001);
  const longitudeSpan = Math.max(bounds.maxLongitude - bounds.minLongitude, 0.000001);
  const maximumCount = Math.max(1, ...clusters.map((cluster) => cluster.count));

  return clusters.map((cluster) => {
    const rawLeft = ((cluster.bounds.minLongitude - bounds.minLongitude) / longitudeSpan) * 100;
    const rawTop = ((bounds.maxLatitude - cluster.bounds.maxLatitude) / latitudeSpan) * 100;
    const rawWidth = ((cluster.bounds.maxLongitude - cluster.bounds.minLongitude) / longitudeSpan) * 100;
    const rawHeight = ((cluster.bounds.maxLatitude - cluster.bounds.minLatitude) / latitudeSpan) * 100;
    const widthPercent = clamp(rawWidth, 8, 100);
    const heightPercent = clamp(rawHeight, 10, 100);

    return {
      cluster,
      leftPercent: clamp(rawLeft, 0, 100 - widthPercent),
      topPercent: clamp(rawTop, 0, 100 - heightPercent),
      widthPercent,
      heightPercent,
      intensity: clamp(cluster.count / maximumCount, 0.2, 1),
      fakeRatio: cluster.count > 0 ? clamp(cluster.fake / cluster.count, 0, 1) : 0
    };
  });
}
