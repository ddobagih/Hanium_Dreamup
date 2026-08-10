import { readFileSync } from "node:fs";
import path from "node:path";
import {
  agencyExportFilters,
  filterReportsByDemoMode,
  isFakeOrDemoReport,
  reportExportUrl,
  summarizeAdminReports,
  type ReportResponse
} from "../lib/report-api";
import { buildAdminHeatmapCells } from "../app/_walksafe/admin-heatmap";
import { koreanCalendarDayEnd, koreanCalendarDayStart } from "../lib/admin-report-filters";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

function report(overrides: Partial<ReportResponse> = {}): ReportResponse {
  return {
    id: "report-fixture",
    status: "new",
    class_id: 1,
    class_name: "damaged_tactile_block",
    confidence: 0.82,
    bbox: { x: 0.1, y: 0.2, width: 0.3, height: 0.4 },
    captured_at: "2026-05-25T00:00:00.000Z",
    source: "server",
    gps: { latitude: 37.56652, longitude: 126.97802, accuracy_m: 8 },
    heading: null,
    image_path: "/reports/report-fixture.jpg",
    image_content_type: "image/jpeg",
    metadata: {},
    location_quality: "high",
    review_flags: [],
    duplicate_report_ids: [],
    created_at: "2026-05-25T00:00:00.000Z",
    updated_at: "2026-05-25T00:00:00.000Z",
    ...overrides
  };
}

const fixtureReports: ReportResponse[] = [
  report({ id: "fake-source", source: "fake", gps: { latitude: 37.56651, longitude: 126.97801, accuracy_m: 8 } }),
  report({ id: "server-a", source: "server", gps: { latitude: 37.56654, longitude: 126.97807, accuracy_m: 9 } }),
  report({ id: "android-a", source: "android", gps: { latitude: 37.56655, longitude: 126.97808, accuracy_m: 7 } }),
  report({ id: "onnx-a", source: "onnx", gps: { latitude: 37.5669, longitude: 126.9785, accuracy_m: 10 } }),
  report({
    id: "flagged-demo",
    source: "server",
    gps: { latitude: 37.5681, longitude: 126.9802, accuracy_m: 12 },
    review_flags: ["fake_source"]
  }),
  report({ id: "missing-location", source: "server", gps: null, location_quality: "missing" }),
  report({ id: "invalid-location", source: "server", gps: { latitude: 91, longitude: 126.978, accuracy_m: null } })
];

function testFakeDemoSummary() {
  const summary = summarizeAdminReports(fixtureReports, { gridSizeDegrees: 0.001, topClusterLimit: 2 });

  assert(summary.fake.total === 7, "summary should count all reports");
  assert(summary.fake.fake === 2, "source=fake and fake_source flag should be fake/demo");
  assert(summary.fake.nonFake === 5, "non-fake count should exclude fake/demo reports");
  assert(summary.fake.sourceFake === 1, "source fake bucket should count source=fake only");
  assert(summary.fake.sourceServer === 4, "source server bucket should count server reports");
  assert(summary.fake.sourceOnnx === 1, "source onnx bucket should count onnx reports");
  assert(summary.fake.sourceAndroid === 1, "source android bucket should count android reports");
  assert(summary.status.new === 7, "status dashboard should count new reports");
}

function testLocationAndClusters() {
  const summary = summarizeAdminReports(fixtureReports, { gridSizeDegrees: 0.001, topClusterLimit: 2 });

  assert(summary.location.located === 5, "valid GPS reports should be located");
  assert(summary.location.missing === 2, "null or invalid GPS reports should be missing");
  assert(summary.location.topClusters.length === 2, "top cluster limit should be applied");
  assert(summary.location.topClusters[0].count === 4, "largest grid cluster should contain four reports");
  assert(summary.location.topClusters[0].fake === 1, "largest cluster should keep fake/demo count");
  assert(summary.location.topClusters[0].nonFake === 3, "largest cluster should keep non-fake count");
  assert(summary.location.topClusters[0].sourceCounts.server === 1, "cluster should keep source breakdown");
  assert(summary.location.topClusters[0].sourceCounts.android === 1, "cluster should keep android source breakdown");
  assert(summary.location.topClusters[1].count === 1, "second cluster should contain one report");
  assert(summary.location.topClusters[1].fake === 1, "second cluster should include flagged demo report");
}

function testHeatmapProjectsClustersWithinStableBounds() {
  const summary = summarizeAdminReports(fixtureReports, { gridSizeDegrees: 0.001, topClusterLimit: 5 });
  const cells = buildAdminHeatmapCells(summary.location.topClusters, summary.location.bounds);

  assert(cells.length === summary.location.topClusters.length, "each summarized grid cluster should become a heatmap cell");
  assert(cells.every((cell) => cell.leftPercent >= 0 && cell.leftPercent + cell.widthPercent <= 100), "heatmap cells must fit horizontally");
  assert(cells.every((cell) => cell.topPercent >= 0 && cell.topPercent + cell.heightPercent <= 100), "heatmap cells must fit vertically");
  assert(cells[0].intensity === 1, "largest cluster should have full heat intensity");
  assert(cells.every((cell) => cell.fakeRatio >= 0 && cell.fakeRatio <= 1), "fake ratio should stay normalized");
}

function testDemoFilters() {
  const onlyFake = filterReportsByDemoMode(fixtureReports, "only_fake").map((item) => item.id);
  const withoutFake = filterReportsByDemoMode(fixtureReports, "exclude_fake").map((item) => item.id);

  assert(onlyFake.join(",") === "fake-source,flagged-demo", "only fake filter should keep fake/demo reports");
  assert(!withoutFake.includes("fake-source"), "exclude fake filter should remove source=fake report");
  assert(!withoutFake.includes("flagged-demo"), "exclude fake filter should remove fake_source flagged report");
  assert(withoutFake.length === 5, "exclude fake filter should keep non-fake reports");
  assert(isFakeOrDemoReport(report({ metadata: { source_model: "fake-v2" } })), "fake metadata should be demo");
  assert(isFakeOrDemoReport(report({ metadata: { data_origin: "demo" } })), "data_origin demo should be demo");
  assert(
    !isFakeOrDemoReport(report({ metadata: { fake_source: false, source_model: "unified_walksafe" } })),
    "an explicit fake_source=false marker on a real v2 report must remain non-demo"
  );
  assert(
    !isFakeOrDemoReport(report({ metadata: { performance_excluded: true } })),
    "performance excluded alone should not be treated as fake/demo"
  );
}

function testExportUrlSupportsPrivacyAndManifestOptions() {
  const url = new URL(
    reportExportUrl({ status: "reviewed", demo_filter: "exclude_fake" }, "geojson", {
      profile: "internal",
      aggregate: "grid"
    }),
    "http://localhost"
  );

  assert(url.searchParams.get("status") === "reviewed", "status filter should be preserved");
  assert(url.searchParams.get("demo_filter") === "exclude_fake", "demo filter should be preserved");
  assert(url.searchParams.get("redacted") === null, "admin exact grid must not claim location redaction");
  assert(url.searchParams.get("aggregate") === "grid", "geojson grid aggregate should be encoded");
  assert(url.searchParams.get("profile") === "internal", "exact grid must declare its internal profile");

  const manifestUrl = new URL(reportExportUrl({}, "json", { manifest: true }), "http://localhost");
  assert(manifestUrl.searchParams.get("manifest") === "true", "manifest export should be encoded");

  const reviewedDamageExportUrl = new URL(
    reportExportUrl(
      agencyExportFilters({
        status: "new",
        source: "server",
        model_key: "unified_walksafe",
        trigger: "auto",
        auto_reported: true,
        created_from: "2026-07-01T00:00:00Z",
        lat: "37.5665",
        lng: "126.9780",
        radius_m: "300",
        performance_excluded: true
      }),
      "csv",
      { profile: "agency", auditId: "11111111-1111-4111-8111-111111111111" }
    ),
    "http://localhost"
  );
  assert(reviewedDamageExportUrl.searchParams.get("status") === "reviewed", "internal export status should be fixed");
  assert(
    reviewedDamageExportUrl.searchParams.get("demo_filter") === "exclude_fake",
    "internal export should exclude fake reports"
  );
  assert(
    reviewedDamageExportUrl.searchParams.get("class_name") === "damaged_tactile_block",
    "internal export should be tactile damage only"
  );
  assert(reviewedDamageExportUrl.searchParams.get("format") === "csv", "agency export should use the documented CSV format");
  assert(!reviewedDamageExportUrl.searchParams.has("performance_excluded"), "agency human review must stay separate from model-performance exclusion");
  assert(reviewedDamageExportUrl.searchParams.get("profile") === "agency", "agency export should use its exact-location minimum profile");
  assert(reviewedDamageExportUrl.searchParams.get("source") === "server", "agency export should retain the current source filter");
  assert(reviewedDamageExportUrl.searchParams.get("model_key") === "unified_walksafe", "agency export should retain the current model filter");
  assert(reviewedDamageExportUrl.searchParams.get("trigger") === "auto", "agency export should retain the current trigger filter");
  assert(reviewedDamageExportUrl.searchParams.get("auto_reported") === "true", "agency export should retain the current automatic-report filter");
  assert(reviewedDamageExportUrl.searchParams.get("created_from") === "2026-07-01T00:00:00Z", "agency export should retain the current date filter");
  assert(reviewedDamageExportUrl.searchParams.get("lat") === "37.5665", "agency export should retain the current radius center");
  assert(reviewedDamageExportUrl.searchParams.get("lng") === "126.9780", "agency export should retain the current radius center");
  assert(reviewedDamageExportUrl.searchParams.get("radius_m") === "300", "agency export should retain the current radius filter");
  assert(
    reviewedDamageExportUrl.searchParams.get("audit_id") === "11111111-1111-4111-8111-111111111111",
    "agency export and manifest must share one audit correlation id"
  );
}

function testKoreanCalendarDateRange() {
  assert(
    koreanCalendarDayStart("2026-07-11") === "2026-07-11T00:00:00+09:00",
    "admin start date must preserve the Korean calendar day"
  );
  assert(
    koreanCalendarDayEnd("2026-07-11") === "2026-07-11T23:59:59.999999+09:00",
    "admin end date must preserve the Korean calendar day"
  );
  assert(koreanCalendarDayStart("") === undefined, "empty start date must stay omitted");
}

function main() {
  const adminSource = readFileSync(path.join(process.cwd(), "app/admin/page.tsx"), "utf8");
  assert(adminSource.includes("현재 페이지 신뢰도순"), "paged confidence sorting must be labeled as page-local");
  assert(adminSource.includes("현재 페이지 상태순"), "paged status sorting must be labeled as page-local");
  testFakeDemoSummary();
  testLocationAndClusters();
  testHeatmapProjectsClustersWithinStableBounds();
  testDemoFilters();
  testExportUrlSupportsPrivacyAndManifestOptions();
  testKoreanCalendarDateRange();
  console.log("admin report summary policy checks passed");
}

main();
