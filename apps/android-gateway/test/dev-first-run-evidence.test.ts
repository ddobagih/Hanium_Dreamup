import assert from "node:assert/strict";
import { afterEach, test } from "node:test";

import {
  DEV_FIRST_RUN_EVIDENCE_PATH,
  devFirstRunEvidenceEnabled,
  handleDevFirstRunEvidenceRequest
} from "../src/dev-first-run-evidence.js";

const url = `http://127.0.0.1:8081${DEV_FIRST_RUN_EVIDENCE_PATH}`;

const saved = {
  nodeEnv: process.env.NODE_ENV,
  flag: process.env.WALKSAFE_DEV_FIRST_RUN_EVIDENCE_ENABLED
};

function enable(): void {
  process.env.NODE_ENV = "development";
  process.env.WALKSAFE_DEV_FIRST_RUN_EVIDENCE_ENABLED = "true";
}

function post(stage: unknown): Request {
  return new Request(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ stage })
  });
}

afterEach(() => {
  if (saved.nodeEnv === undefined) delete process.env.NODE_ENV;
  else process.env.NODE_ENV = saved.nodeEnv;
  if (saved.flag === undefined) {
    delete process.env.WALKSAFE_DEV_FIRST_RUN_EVIDENCE_ENABLED;
  } else {
    process.env.WALKSAFE_DEV_FIRST_RUN_EVIDENCE_ENABLED = saved.flag;
  }
});

test("both conditions are required to open the route", () => {
  assert.equal(
    devFirstRunEvidenceEnabled({
      NODE_ENV: "production",
      WALKSAFE_DEV_FIRST_RUN_EVIDENCE_ENABLED: "true"
    }),
    false
  );
  assert.equal(devFirstRunEvidenceEnabled({ NODE_ENV: "development" }), false);
  assert.equal(
    devFirstRunEvidenceEnabled({
      NODE_ENV: "development",
      WALKSAFE_DEV_FIRST_RUN_EVIDENCE_ENABLED: "true"
    }),
    true
  );
});

test("production answers 404 even with the flag set", async () => {
  process.env.NODE_ENV = "production";
  process.env.WALKSAFE_DEV_FIRST_RUN_EVIDENCE_ENABLED = "true";
  const response = await handleDevFirstRunEvidenceRequest(post("VERIFIED_SMS"));
  assert.equal(response.status, 404);
  assert.equal(
    (await response.json()).code,
    "dev_first_run_evidence_disabled"
  );
});

test("each remote stage gets a receipt and only the two carrier stages get handles", async () => {
  enable();
  const cases: ReadonlyArray<[string, string | null]> = [
    ["LOCAL_CREDENTIAL_PHONE_SUBMISSION", "submission_handle"],
    ["VERIFIED_SMS", null],
    ["GUARDIAN_APPROVAL", null],
    ["ACCOUNT_ACTIVATION", null],
    ["VERIFIED_LOGIN", "actor_binding"]
  ];
  for (const [stage, extra] of cases) {
    const body = await (await handleDevFirstRunEvidenceRequest(post(stage))).json();
    assert.equal(body.stage, stage);
    assert.match(body.receipt_sha256, /^[0-9a-f]{64}$/);
    assert.equal(body.evidence_kind, "development_only_not_production_evidence");
    if (extra === "submission_handle") {
      assert.match(body.submission_handle, /^onb_[0-9a-f]{32}$/);
      assert.match(body.submission_handle, /[a-f]/);
      assert.equal(body.actor_binding, undefined);
    } else if (extra === "actor_binding") {
      assert.match(body.actor_binding, /^actor_[0-9a-f]{32}$/);
      assert.match(body.actor_binding, /[a-f]/);
      assert.equal(body.submission_handle, undefined);
    } else {
      assert.equal(body.submission_handle, undefined);
      assert.equal(body.actor_binding, undefined);
    }
  }
});

test("stages outside the five remote ones are refused", async () => {
  enable();
  for (const stage of [
    "INTEGRATED_CONSENT",
    "COMPLETE",
    "PURPOSE_AND_SAFETY",
    "",
    42
  ]) {
    const response = await handleDevFirstRunEvidenceRequest(post(stage));
    assert.equal(response.status, 400, String(stage));
  }
});

test("receipts are not reused between calls", async () => {
  enable();
  const first = await (
    await handleDevFirstRunEvidenceRequest(post("VERIFIED_SMS"))
  ).json();
  const second = await (
    await handleDevFirstRunEvidenceRequest(post("VERIFIED_SMS"))
  ).json();
  assert.notEqual(first.receipt_sha256, second.receipt_sha256);
});
