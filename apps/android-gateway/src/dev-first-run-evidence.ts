import { createHash, randomBytes } from "node:crypto";

/**
 * 개발 전용 첫 실행 증거 발급.
 *
 * FP-010의 4~8단계는 운영 전화번호 제출·SMS 검증·보호자 승인·계정 활성화·검증 로그인 증거를
 * 기다린다. 그 외부 연동은 FP-010 Goal이 범위 밖으로 선언해 아직 없고, 그래서 로컬에서는 첫 실행을
 * 끝까지 통과시킬 방법이 없다. 이 경로는 그 다섯 단계를 로컬에서 지나가게 하려고만 존재한다.
 *
 * 실제 검증을 하지 않는다. 전화번호를 받지도, SMS를 보내지도, 보호자에게 알리지도 않는다. 발급하는
 * 값은 전부 난수이며 사용자 입력에서 파생하지 않는다. 따라서 이 증거는 운영 증거가 아니고
 * `productionEvidenceAvailable` 을 여는 근거로도 쓸 수 없다.
 *
 * NODE_ENV 가 production 이 아니고 전용 플래그가 켜져 있을 때만 열린다. 두 조건은 서로 독립이라
 * 한쪽 실수로는 켜지지 않는다.
 */

export const DEV_FIRST_RUN_EVIDENCE_PATH = "/api/dev/first-run-evidence";

const REMOTE_STAGES: ReadonlySet<string> = new Set([
  "LOCAL_CREDENTIAL_PHONE_SUBMISSION",
  "VERIFIED_SMS",
  "GUARDIAN_APPROVAL",
  "ACCOUNT_ACTIVATION",
  "VERIFIED_LOGIN"
]);

const MAX_BODY_BYTES = 4096;

export function devFirstRunEvidenceEnabled(
  environment: NodeJS.ProcessEnv = process.env
): boolean {
  return (
    environment.NODE_ENV !== "production" &&
    environment.WALKSAFE_DEV_FIRST_RUN_EVIDENCE_ENABLED === "true"
  );
}

/**
 * 앱의 불투명 핸들 규칙은 32 hex 안에 a~f 가 최소 하나 있기를 요구한다. 난수가 우연히 숫자만
 * 나오는 경우를 위해 조건을 만족할 때까지 다시 뽑는다.
 */
function opaqueHex(): string {
  for (;;) {
    const candidate = randomBytes(16).toString("hex");
    if (/[a-f]/.test(candidate)) return candidate;
  }
}

function devReceiptSha256(stage: string): string {
  return createHash("sha256")
    .update(`walksafe.dev-first-run-evidence\0${stage}\0${opaqueHex()}`)
    .digest("hex");
}

function json(status: number, body: unknown): Response {
  return Response.json(body, {
    status,
    headers: { "cache-control": "no-store" }
  });
}

export async function handleDevFirstRunEvidenceRequest(
  request: Request
): Promise<Response> {
  if (!devFirstRunEvidenceEnabled()) {
    return json(404, {
      code: "dev_first_run_evidence_disabled",
      message: "This development-only route is not enabled."
    });
  }
  if (request.method !== "POST") {
    return json(405, {
      code: "method_not_allowed",
      message: "Use POST."
    });
  }

  const raw = await request.text();
  if (raw.length > MAX_BODY_BYTES) {
    return json(413, {
      code: "dev_first_run_evidence_body_too_large",
      message: "Request body is too large."
    });
  }
  let payload: unknown;
  try {
    payload = JSON.parse(raw);
  } catch {
    return json(400, {
      code: "dev_first_run_evidence_invalid_json",
      message: "Request body must be JSON."
    });
  }
  const stage =
    payload && typeof payload === "object" && "stage" in payload
      ? (payload as { stage?: unknown }).stage
      : undefined;
  if (typeof stage !== "string" || !REMOTE_STAGES.has(stage)) {
    return json(400, {
      code: "dev_first_run_evidence_stage_invalid",
      message: "stage must name one of the five remote first-run stages."
    });
  }

  const body: Record<string, string> = {
    stage,
    receipt_sha256: devReceiptSha256(stage),
    evidence_kind: "development_only_not_production_evidence"
  };
  if (stage === "LOCAL_CREDENTIAL_PHONE_SUBMISSION") {
    body.submission_handle = `onb_${opaqueHex()}`;
  }
  if (stage === "VERIFIED_LOGIN") {
    body.actor_binding = `actor_${opaqueHex()}`;
  }
  return json(200, body);
}
