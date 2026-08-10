import { readFileSync } from "node:fs";
import {
  describeScreenWakeLockState,
  describePwaInstallState,
  describePwaUpdateState,
  isWalkSafePwaEnabled,
  isWalkSafeServiceWorkerScript,
  isStandaloneDisplayMode,
  resolvePwaInstallState,
  shouldWarnForScreenWakeLock
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

function testPwaInstallStateResolution() {
  assert(
    resolvePwaInstallState({ standalone: true, promptAvailable: true, installRequested: true }) === "standalone",
    "only an actual standalone launch should confirm installed execution"
  );
  assert(
    resolvePwaInstallState({ standalone: false, promptAvailable: false, installRequested: true }) === "install_requested",
    "prompt acceptance or appinstalled must remain an unconfirmed install request"
  );
  assert(
    resolvePwaInstallState({ standalone: false, promptAvailable: true, installRequested: false }) === "available",
    "an available prompt should remain actionable"
  );
  assert(
    resolvePwaInstallState({ standalone: false, promptAvailable: false, installRequested: false }) === "browser",
    "a browser without a prompt or accepted request should remain browser mode"
  );
}

function testPwaMessages() {
  assert(describePwaInstallState("available").includes("설치 가능"), "install prompt availability should be announced");
  assert(describePwaInstallState("install_requested").includes("확인 필요"), "an accepted request must not claim installation");
  assert(describePwaInstallState("standalone").includes("실행 확인됨"), "standalone launch should confirm installed execution");
  assert(describePwaUpdateState("ready", "test-version").includes("test-version"), "SW version should be included in update message");
  assert(describePwaUpdateState("applying", null).includes("적용 확인 중"), "update should stay pending until controllerchange");
  assert(describePwaUpdateState("applied", null).includes("새로고침"), "applied update should explain the safe reload step");
  assert(describePwaUpdateState("unsupported", null).includes("지원하지"), "unsupported SW should be explicit");
}

function testPwaRegistrationIsOptIn() {
  assert(!isWalkSafePwaEnabled(undefined), "PWA service worker should default to disabled");
  assert(!isWalkSafePwaEnabled("false"), "PWA service worker should stay disabled for false");
  assert(isWalkSafePwaEnabled("true"), "PWA service worker should register only on explicit opt-in");
  assert(
    isWalkSafeServiceWorkerScript("https://field.example/sw.js?version=2", "https://field.example"),
    "owned service-worker URLs should be recognized for opt-out cleanup"
  );
  assert(
    !isWalkSafeServiceWorkerScript("https://other.example/sw.js", "https://field.example"),
    "opt-out cleanup must not unregister another origin's worker"
  );
  const hookSource = readFileSync("app/_walksafe/hooks/usePwaStatus.ts", "utf8");
  const workerSource = readFileSync("public/sw.js", "utf8");
  assert(
    !hookSource.includes('setInstallState("installed")') &&
      hookSource.includes('installRequested: choice.outcome === "accepted"'),
    "prompt acceptance must stay install_requested until a standalone launch is observed"
  );
  assert(
    hookSource.includes('.register("/sw.js", { updateViaCache: "none" })'),
    "service-worker update checks must bypass the HTTP cache"
  );
  assert(workerSource.includes('importScripts("/sw-version.js")'), "the worker must import the build-bound release identity");
  assert(workerSource.includes("source-${SOURCE_COMMIT}"), "the worker cache/version must change with the source commit");
  assert(
    workerSource.includes("event.waitUntil(self.skipWaiting())"),
    "user-approved activation must keep the skipWaiting promise alive"
  );
}

function testScreenWakeLockStatesAreFailClosedAndExplicit() {
  assert(!shouldWarnForScreenWakeLock("active"), "an active screen wake lock should not show a safety warning");
  for (const state of ["unsupported", "denied", "released", "error"] as const) {
    assert(shouldWarnForScreenWakeLock(state), `${state} must produce a visible safety warning`);
    assert(
      describeScreenWakeLockState(state).includes("중지") || describeScreenWakeLockState(state).includes("다시 요청"),
      `${state} must explain the fail-closed screen-lock consequence or recovery`
    );
  }
  const hookSource = readFileSync("app/_walksafe/hooks/usePwaStatus.ts", "utf8");
  const pageSource = readFileSync("app/page.tsx", "utf8");
  assert(hookSource.includes('manager.request("screen")'), "an active assist session must explicitly request a screen wake lock");
  assert(hookSource.includes("sentinel.release()"), "wake lock ownership must be explicitly released during teardown");
  assert(
    pageSource.includes("fieldSessionAuthenticated && assistSessionLifecycle === \"active\" && cameraReady"),
    "wake lock acquisition must be scoped to an authenticated active camera session"
  );
  assert(pageSource.includes("screenWakeLock.message"), "wake lock failures and release must be user-visible");
}

function main() {
  testStandaloneDetection();
  testPwaInstallStateResolution();
  testPwaMessages();
  testPwaRegistrationIsOptIn();
  testScreenWakeLockStatesAreFailClosedAndExplicit();
  console.log("pwa status policy checks passed");
}

main();
