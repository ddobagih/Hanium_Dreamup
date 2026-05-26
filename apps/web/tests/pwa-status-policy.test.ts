import {
  describePwaInstallState,
  describePwaUpdateState,
  isStandaloneDisplayMode
} from "../app/_walksafe/hooks/usePwaStatus";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

function testStandaloneDetection() {
  assert(isStandaloneDisplayMode(true, false), "display-mode standalone should be recognized");
  assert(isStandaloneDisplayMode(false, true), "iOS navigator.standalone should be recognized");
  assert(!isStandaloneDisplayMode(false, false), "browser mode should not be standalone");
}

function testPwaMessages() {
  assert(describePwaInstallState("available").includes("설치 가능"), "install prompt availability should be announced");
  assert(describePwaUpdateState("ready", "test-version").includes("test-version"), "SW version should be included in update message");
  assert(describePwaUpdateState("unsupported", null).includes("지원하지"), "unsupported SW should be explicit");
}

function main() {
  testStandaloneDetection();
  testPwaMessages();
  console.log("pwa status policy checks passed");
}

main();
