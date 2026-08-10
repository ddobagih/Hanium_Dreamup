# Phase 1 WalkSafe safety authoring 독립 검토 R001

## 검토 식별

| 항목 | 값 |
|---|---|
| review ID | `WS-PHASE1-WALKSAFE-SAFETY-INDEPENDENT-REVIEW-20260727-R001` |
| 검토일 | `2026-07-27` |
| 검토 범위 | `DLV-WS-08`, `DLV-WS-10`, `DLV-WS-18` |
| 검토 방식 | 정적 독립 검토와 물리 SHA-256/byte 대조 |
| build/test | `NOT_RUN` |
| 승인 상태 | `NOT_APPROVED` |
| release 상태 | `NOT_ELIGIBLE` |

검토 대상:

- `docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-walksafe-safety-authoring/evidence.json`
- `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md`
- `docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md`
- `docs/walksafe-v2/navigation_integration_policy.md`

## 종합 판정

| 항목 | 판정 |
|---|---|
| finding count | `1` |
| 최고 severity | `MAJOR` |
| packet authoring acceptance | `NO_GO_PENDING_FINDING_CLOSURE` |
| 실행·시험·승인 승격 | `NONE` |
| release | `NOT_ELIGIBLE` |

정책 freshness, exact artifact scope, class별 안전분석, 후보 모델 `UNKNOWN` 경계,
미실행 parity·device·API·quota 경계, 물리 output binding과 packet non-self
fingerprint는 보수적으로 유지된다. 그러나 safety 정본이 동일 navigation
정본을 `latest` source로 두 번 인용하면서 현재 output과 다른 SHA-256을
기록하므로 packet 전체를 content acceptance로 넘길 수 없다.

## Findings

| Finding ID | Severity | 범위 | Exact location | 내용 | 요구 조치 |
|---|---|---|---|---|---|
| `WS-SAFETY-R001-F001` | `MAJOR` | `DLV-WS-08`, `DLV-WS-18`, output-to-output source binding | `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md:309`, `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md:379`, 대조 기준 `docs/control/execution/artifact-closure/run-20260727-001/packets/phase1-walksafe-safety-authoring/evidence.json:138` | 두 source 표는 `latest navigation policy` SHA-256을 `2871973337d4e8c25301f2a62b26841f71f13c0a5d5efe62287e0942a191afd5`로 기록한다. packet output manifest와 현재 물리 파일은 20,675 bytes, SHA-256 `af9a05935a3f54a7f06e6dfcba4a39a6d57ef94879cf1c3afa0288ca050a63a8`이다. 같은 정본을 latest라고 주장하면서 content binding이 달라 WS-08/18 source trace가 재현되지 않는다. | 두 위치를 현재 navigation output hash에 결속하거나, 의도한 값이 pre-authoring snapshot이면 `latest` 표현을 제거하고 exact input subject로 manifest에 별도 결속한다. 이후 변경된 safety output SHA/bytes, output manifest fingerprint와 packet non-self fingerprint를 재생성하고 재검토한다. |

## 확인 결과

| 검토 항목 | 결과 | 근거 |
|---|---|---|
| exact scope | `PASS` | packet scope와 disposition은 정확히 `DLV-WS-08`, `DLV-WS-10`, `DLV-WS-18` 세 건이다. |
| policy freshness | `PASS` | packet은 `PB-WALKSAFE-FEATURE-POLICY-1.0.1`을 current/effective로 두고 세 output도 1.0.1을 현행 기준선으로 사용한다. 검토 대상에서 현행 `1.0.0` 또는 `NOT_EFFECTIVE` 승격 표현은 발견되지 않았다. |
| WS-08 exact13 | `PASS_WITHOUT_EXECUTION_PROMOTION` | `walksafe-safety-and-policy.md:313`부터 exact13 각 class에 FP, FN, harm, exposure, detectability, model/policy/UI control, fail-closed, required evidence와 residual field limitation이 있다. 수치평가와 실기기 결과는 `NOT_RUN`이다. |
| offline source claim | `PASS_WITH_LIMITATION` | packet, safety 정본과 acceptance matrix는 2,461 frame, generated probe, risk bucket 926/883/652, class 결과 부재, `arcore_pass=false`, ground-truth 아님을 같은 경계로 기록한다. 이 검토는 입력 archive나 평가를 재실행하지 않았다. |
| WS-10 candidate binding | `PASS` | candidate model ID/hash는 `UNKNOWN_NOT_BOUND`, numeric tolerance는 `UNKNOWN_REQUIRES_APPROVED_RUN_CONTRACT`, parity는 `NOT_RUN`, 결과는 `UNKNOWN`이다. 입력 ZIP hash를 model hash로 승격하지 않는다. |
| WS-10 device/field claim | `PASS` | offline reference를 ARCore, Android device, field, GPS, TTS/진동 또는 최종 안전성 PASS로 사용하지 않는다. |
| WS-18 API/quota | `PASS` | endpoint 배포와 live TMAP은 `NOT_RUN`, quota·비용·support는 `NOT_ESTABLISHED`, alternate provider는 미승인이다. |
| WS-18 fail-closed | `PASS` | retry budget 0, stale route 금지, invalid response 폐기, 위치 불신 시 회전안내 중지, 독립 안전성 미확인 시 전체 안전정지를 명시한다. |
| historical live smoke | `PASS_WITH_BOUNDARY` | `navigation_integration_policy.md:127`의 historical 단일 live smoke `PASS`는 `:130`에서 현재 API 계약·quota·Android 실기기·현장·출시 검증을 대체하지 않는다고 제한한다. |
| 허위 release 승격 | `PASS` | packet `release_eligible=false`; 세 output은 현재 release를 `NOT_ELIGIBLE`로 유지한다. |
| 중복·모순 | `FAIL_ONE_SOURCE_BINDING_CONTRADICTION` | `WS-SAFETY-R001-F001` 외에 실행 PASS나 release 승격 모순은 발견되지 않았다. |

## 물리 output binding

| Output | Packet byte/SHA | Physical byte/SHA | 판정 |
|---|---|---|---|
| `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md` | 70,734 / `2c2be8ff5771cc33c219c1928e1c669e124dc9789b8ef831a9f9904a6663aa58` | 70,734 / `2c2be8ff5771cc33c219c1928e1c669e124dc9789b8ef831a9f9904a6663aa58` | `PASS` |
| `docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md` | 53,428 / `5b0efdced1b9a17ecdf73311c78b83c55643521a671289c7fd18c1aa0a9e54cc` | 53,428 / `5b0efdced1b9a17ecdf73311c78b83c55643521a671289c7fd18c1aa0a9e54cc` | `PASS` |
| `docs/walksafe-v2/navigation_integration_policy.md` | 20,675 / `af9a05935a3f54a7f06e6dfcba4a39a6d57ef94879cf1c3afa0288ca050a63a8` | 20,675 / `af9a05935a3f54a7f06e6dfcba4a39a6d57ef94879cf1c3afa0288ca050a63a8` | `PASS` |

Packet 자체의 물리 크기는 11,577 bytes이고 SHA-256은
`efe170f357e9fc4483e9cb8815743c4e458015369360019ec22bbf924d4d2e3b`이다.

## Fingerprint 검토

| 항목 | 값 |
|---|---|
| stored non-self SHA-256 | `128497810d1d9bed3f71241b2b3490ba8307ae1d1ba4f80b81b557e47441c723` |
| recomputed non-self SHA-256 | `128497810d1d9bed3f71241b2b3490ba8307ae1d1ba4f80b81b557e47441c723` |
| canonicalization | UTF-8, recursive lexicographic key order, compact JSON, top-level `packet_content_fingerprint` 제외 |
| 판정 | `PASS` |

`markdown_fence_parity=PASS_3_OF_3`은 정적 형식검사일 뿐 실행·시험·승인
PASS로 해석되지 않으며 packet도 execution promotion을 `NONE`으로 둔다.

## Artifact disposition

| Artifact | 독립 검토 disposition | 이유 |
|---|---|---|
| `DLV-WS-08` | `NO_GO_PENDING_WS-SAFETY-R001-F001` | exact13 내용과 보수적 실행 경계는 적절하나 navigation source hash가 stale하다. |
| `DLV-WS-10` | `LIMITED_GO_AUTHORING_ONLY` | 실행 계약은 충분히 보수적이며 candidate hash, tolerance, parity와 device 결과를 모두 미결정·미실행으로 유지한다. 실제 run과 승인은 남는다. |
| `DLV-WS-18` | `NO_GO_PENDING_WS-SAFETY-R001-F001` | API/quota/fallback 계약은 적절하나 navigation source hash가 stale하다. |

## 남은 실제 gate

| Gate | 현재 상태 |
|---|---|
| `GATE-WS08-CLASS-LEVEL-FIELD-VALIDATION` | `NOT_RUN` |
| `GATE-WS10-CANDIDATE-MODEL-BINDING` | `OPEN_UNKNOWN` |
| `GATE-WS10-MODEL-PARITY-RUN` | `NOT_RUN` |
| `GATE-WS18-MAP-CONTRACT-AND-QUOTA` | `OPEN_UNKNOWN` |
| `GATE-WS18-DEVICE-FIELD-FALLBACK` | `NOT_RUN` |
| `GATE-PHONE-QUEUE-BYTE-LIMIT` | `NOT_RUN` |
| `GATE-SERVER-CAPACITY-STATE-CONTRACT` | `NOT_RUN` |
| `GATE-RAW-COLLECTION-RELEASE-REVIEW` | `NOT_RUN` |
| `GATE-PHASE1-SAFETY-CONTENT-REVIEW-AND-APPROVAL` | `OPEN`; finding closure와 재검토 전 승인 불가 |

추가로 실제 후보 source/TFLite ID와 hash, 승인된 수치 허용오차와 parity
증거 목적지, 동일 입력 parity 실행, Android 실기기·현장 class별 안전검증,
TMAP 계약·quota·provider exit 검증, 지정 권한자의 content 승인 receipt가
필요하다. 이 검토는 이들 실행이나 승인을 대신하지 않는다.

## 검토 제한

- 검토 대상 네 파일의 정적 내용과 물리 bytes만 확인했다.
- packet이 인용하는 외부 archive, runtime config, model register와 provider 문서는 별도로 재취득하거나 재실행하지 않았다.
- build, test, device/field run, external API 호출, Git 작업은 수행하지 않았다.
- target packet과 세 output은 수정하지 않았다.
