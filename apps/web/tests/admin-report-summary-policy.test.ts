import {
  filterReportsByDemoMode,
  isFakeOrDemoReport,
  reportExportUrl,
  summarizeAdminReports,
  type ReportResponse
} from "../lib/report-api";

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
    !isFakeOrDemoReport(report({ metadata: { performance_excluded: true } })),
    "performance excluded alone should not be treated as fake/demo"
  );
}

function testExportUrlSupportsPrivacyAndManifestOptions() {
  const url = new URL(
    reportExportUrl({ status: "reviewed", demo_filter: "exclude_fake" }, "geojson", {
      redacted: true,
      aggregate: "grid"
    })
  );

  assert(url.searchParams.get("status") === "reviewed", "status filter should be preserved");
  assert(url.searchParams.get("demo_filter") === "exclude_fake", "demo filter should be preserved");
  assert(url.searchParams.get("redacted") === "true", "redacted export should be opt-in");
  assert(url.searchParams.get("aggregate") === "grid", "geojson grid aggregate should be encoded");

  const manifestUrl = new URL(reportExportUrl({}, "json", { manifest: true }));
  assert(manifestUrl.searchParams.get("manifest") === "true", "manifest export should be encoded");

  const reviewedDamageExportUrl = new URL(
    reportExportUrl(
      { status: "reviewed", demo_filter: "exclude_fake", class_name: "damaged_tactile_block" },
      "geojson",
      { redacted: true }
    )
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
  assert(reviewedDamageExportUrl.searchParams.get("format") === "geojson", "internal export should be geojson");
  assert(reviewedDamageExportUrl.searchParams.get("redacted") === "true", "internal export should be redacted");
  assert(!reviewedDamageExportUrl.searchParams.has("model_key"), "internal export should not be model-specific");
}

function main() {
  testFakeDemoSummary();
  testLocationAndClusters();
  testDemoFilters();
  testExportUrlSupportsPrivacyAndManifestOptions();
  console.log("admin report summary policy checks passed");
}

main();
