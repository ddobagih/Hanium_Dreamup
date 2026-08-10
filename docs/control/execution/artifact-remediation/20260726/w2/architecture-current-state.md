# W2 아키텍처 현행 상태 보충본

- 문서 ID: `WS-ARTIFACT-REMEDIATION-W2-ARCHITECTURE-CURRENT-STATE-20260726-001`
- 상태: `CURRENT_STATE_SUPPLEMENT_READY_FOR_W2_REVIEW`
- 스냅샷: `source_commit=null`, `DIRTY_WORKTREE_EXACT_PATH_SHA256`
- 범위: `DLV-DES-01`~`DLV-DES-08` 정확히 8개
- 산출물 집합 SHA-256: `8c8750d63208d33c01d283db79d889148b67fbffc6e57ec587dd77d1b0bdca73`
- 근거: 42개 정확 경로/SHA, 집합 SHA-256 `982a4d4c6727f738dbc97b479518348cc00b42d560ce1c92fb0d54e4a5b9a5be`
- JSON content fingerprint: `7b6ba4c286d54fdc8abaa7fa4ddb87f6453fead47eb479a64e91c895fa463327`
- fingerprint 규약: `UTF-8 JSON, sort_keys=true, separators=(comma,colon), ensure_ascii=false, trailing LF, integrity.content_fingerprint.value=null`
- 권위: 기존 2026-07-21/22 정본·승인 기록을 수정하지 않는 add-only 현행 보충본

## 판정

이 문서는 stale한 설계 Draft를 덮어쓰지 않고 현재 코드와 정책을 기준으로 DES-01~08의 내용 공백을 보완한다. 내용은 W2 검토 준비 상태이며 별도 산출물 승인, 독립 검토, 정식시험, 배포 또는 출시 완료를 뜻하지 않는다.

| ID | 산출물 | 현행 보완 내용 | 상태 |
|---|---|---|---|
| DLV-DES-01 | SDD | 사용자 앱·관리자 앱·게이트웨이·백엔드·DB·모델·외부 서비스의 책임과 제약 | 검토 준비, 승인 미주장 |
| DLV-DES-02 | 시스템 컨텍스트 | 행위자, 외부 TMAP/Android 플랫폼, 프로토콜·신뢰 경계·환경 차이 | 검토 준비, 승인 미주장 |
| DLV-DES-03 | 구성요소·모듈 | 실제 `app`, `adminapp`, `android-gateway`, backend, DB, model, voice, web inventory | 검토 준비, 승인 미주장 |
| DLV-DES-04 | 런타임 흐름 | 카메라·길안내·신고·관리자·FP035 흐름과 취소·실패 경계 | 검토 준비, 승인 미주장 |
| DLV-DES-05 | 배포 아키텍처 | 개발 현행 설정, 통합/운영 목표, 미확정 도메인·TLS·스토리지 | 목표 미배포 |
| DLV-DES-06 | ADR | compact successor 135+1/135/428과 구현 관찰·미결정 사항 | 별도 ADR 승인 미주장 |
| DLV-DES-07 | 기술 스택 | 실제 버전, pinning, 지원·라이선스 위험과 철회 조건 | 위험 검토 미완료 |
| DLV-DES-08 | SIP | mock→live 통합 순서, 환경, 합격 기준, 격리·rollback | 계획 준비, 실행 미주장 |

## 현행 시스템 경계

```text
Android 사용자 앱
  -> 전용 android-gateway (debug 기본 127.0.0.1:8081, release는 정확한 HTTPS origin 요구)
    -> FastAPI backend (현재 gateway config는 127.0.0.1:8000만 허용)
      -> PostgreSQL/PostGIS
      -> 로컬 모델/파일
      -> TMAP 외부 API

Android 관리자 앱
  -> FastAPI backend /admin/security/* 직접 연결
  -> 보안 제어는 활성, 운영 workflow는 잠김
```

현재 관리자 직접 경로는 사용자 게이트웨이 경로와 다르다. FP-040 단일 보호 출입구와의 정합 결정을 내리고 구현·시험하기 전에는 관리자 운영 workflow와 출시를 열 수 없다. `apps/web`은 저장소에 존재하지만 Android gateway가 아니며, gateway는 별도 Node/TypeScript 프로세스다.

## FP-035 현행

1. 보행 중에는 어떤 망에서도 일반 활동원본을 전송하지 않는다.
2. 정지 후 명시적 이동통신망 opt-in과 허용망·기타 조건을 만족하면 cellular 전송을 허용한다.
3. opt-in이 없으면 Wi-Fi만 허용하며 cellular 상태는 `QUEUED_UNTIL_WIFI`다.
4. 허용망이 없으면 정책상 암호화 대기열에 보관해야 한다.

현재 코드는 `UNKNOWN` fail-close, 보행 차단, Wi-Fi 허용, 승인 cellular 허용, 비승인 cellular 대기, `OFFLINE/OTHER` fail-close와 움직임 재개 시 queued/active 업로드 취소를 구현한다. 네트워크 정책 17건과 uploader 취소 5건, 총 22개 내부 단위시험은 PASS다. 다만 영속 암호화 30일 대기열, chunk resume, 서버 receipt 기반 삭제와 전체 원본 파이프라인은 이 근거로 구현 완료를 주장할 수 없다.

## 환경별 토폴로지

### 개발 설정

- `docker-compose.yml`은 PostGIS 하나만 host loopback에 구성한다.
- backend와 gateway는 compose 서비스가 아니며 수동 프로세스 구성이다.
- 사용자 debug origin은 기본 `http://127.0.0.1:8081`, 관리자 debug origin은 기본 `http://127.0.0.1:8000`이다.
- 실제 기기에서 loopback을 연결할 bridge/host 구성이 확정되지 않았다.

### 통합 목표

- 고정 build/source hash, 격리 DB/파일/계정, same-host gateway/backend, 실제 Android 기기
- TMAP mock을 먼저 통과한 뒤 통제된 live key·quota 환경으로 전환
- 관리자 단일 출입구 결정을 닫은 뒤 보안·복구·운영 경계를 검증
- host, 인증서, fixture, 계정, 모델 digest, 관측 임계값은 미확정

### 운영 목표

정확한 HTTPS origin, TLS/reverse proxy, private DB, 암호화 object storage/KMS, 모델 버전, 관측·백업·복구·서명된 release bundle이 필요하다. 현재 어느 것도 배포·승인 완료로 기록하지 않는다.

## 통합 순서

1. 정책 1.0.1·compact successor·정확 source/build identity 고정
2. PostGIS·migration·FastAPI readiness·모델/파일 준비상태 통합
3. 전용 gateway route/auth/privacy와 loopback backend 통합
4. 사용자 앱·FP035 네 분기·movement cancellation·persistent queue gap 검증
5. TMAP mock에서 live provider로 제한 전환
6. 관리자 보안·복구와 FP-040 entry 정합화
7. 고정 bundle로 실제 기기·사용자·접근성·네트워크·현장·279개 정식시험과 5개 gate 수행

각 단계 실패 시 새 단계 진입을 막고 격리 계정·세션·ledger를 폐기한다. DB는 검증된 downgrade나 사전 snapshot이 없으면 rollback 완료를 주장하지 않는다. FP035 실패 시 업로드를 끄고 active 작업을 취소하며 보행 중 전송으로 fallback하지 않는다.

## 기술·지원·라이선스 경계

- Android: AGP 9.1.0, SDK 36/최소 26, JVM 21, ARCore 1.54.0, CameraX 1.6.1, LiteRT 1.4.0
- Gateway: Node `>=22 <23`, TypeScript 6.0.3
- Backend: FastAPI 0.128.8, Uvicorn 0.39.0, SQLAlchemy 2.0.49, psycopg 3.2.13, Ultralytics 8.4.48
- DB: PostGIS 16-3.5 digest pin
- Web: Next 16.2.6/React 19.2.6, gateway와 별개
- Voice: local prototype dependency set이며 제품 통합 미입증

버전 선언은 근거가 있지만 지원주기 검토, SBOM, transitive license, 모델 weight·dataset 권리, 배포 notice는 완료 증거가 없다. 따라서 지원·라이선스 clearance를 주장하지 않는다.

## Gap disposition

모든 DES-01~08은 정확히 하나의 `gap_disposition`을 가지며, 현재는 아래 `gap_ids`로 열린 작업을 참조한다. `NO_OPEN_GAP`으로 표시된 항목은 없다.

| gap_id | 대상 wave | 열린 항목 | 예상 증거 |
|---|---|---|---|
| `W2-ARCH-GAP-001` | `W2` | W2 DES-01~08 current-state supplement independent review and wave-level disposition | `docs/control/execution/artifact-remediation/20260726/w2/implementation-receipt.json`, `docs/control/execution/artifact-remediation/20260726/w2/validation-summary.json`, `docs/control/execution/artifact-remediation/20260726/w2/independent-review.md`, `docs/control/execution/artifact-remediation/20260726/w2/artifact-status-delta.json` |
| `W2-ARCH-GAP-002` | `W3` | Admin direct-backend path versus FP-040 protected single entry | `docs/control/execution/artifact-remediation/20260726/w3/implementation-receipt.json`, `docs/control/execution/artifact-remediation/20260726/w3/validation-summary.json`, `docs/control/execution/artifact-remediation/20260726/w3/independent-review.md`, `docs/control/execution/artifact-remediation/20260726/w3/artifact-status-delta.json` |
| `W2-ARCH-GAP-003` | `W3` | FP-035 persistent encrypted queue, chunk/resume, capacity-state and receipt-bound deletion implementation | `docs/control/execution/artifact-remediation/20260726/w3/implementation-receipt.json`, `docs/control/execution/artifact-remediation/20260726/w3/validation-summary.json`, `docs/control/execution/artifact-remediation/20260726/w3/independent-review.md`, `docs/control/execution/artifact-remediation/20260726/w3/artifact-status-delta.json` |
| `W2-ARCH-GAP-004` | `W4` | 279 formal tests and actual-device/user/TalkBack/network/socket/field/PostGIS end-to-end execution | `docs/control/execution/artifact-remediation/20260726/w4/implementation-receipt.json`, `docs/control/execution/artifact-remediation/20260726/w4/validation-summary.json`, `docs/control/execution/artifact-remediation/20260726/w4/independent-review.md`, `docs/control/execution/artifact-remediation/20260726/w4/artifact-status-delta.json` |
| `W2-ARCH-GAP-005` | `W4` | Repeatable integration hosts, device routing, accounts/fixtures and mock-to-live TMAP evidence | `docs/control/execution/artifact-remediation/20260726/w4/implementation-receipt.json`, `docs/control/execution/artifact-remediation/20260726/w4/validation-summary.json`, `docs/control/execution/artifact-remediation/20260726/w4/independent-review.md`, `docs/control/execution/artifact-remediation/20260726/w4/artifact-status-delta.json` |
| `W2-ARCH-GAP-006` | `W5` | Security/privacy trust-boundary controls and raw-collection independent review preparation | `docs/control/execution/artifact-remediation/20260726/w5/implementation-receipt.json`, `docs/control/execution/artifact-remediation/20260726/w5/validation-summary.json`, `docs/control/execution/artifact-remediation/20260726/w5/independent-review.md`, `docs/control/execution/artifact-remediation/20260726/w5/artifact-status-delta.json` |
| `W2-ARCH-GAP-007` | `W6` | AI/ML data, model-weight and dataset provenance/rights baseline | `docs/control/execution/artifact-remediation/20260726/w6/implementation-receipt.json`, `docs/control/execution/artifact-remediation/20260726/w6/validation-summary.json`, `docs/control/execution/artifact-remediation/20260726/w6/independent-review.md`, `docs/control/execution/artifact-remediation/20260726/w6/artifact-status-delta.json` |
| `W2-ARCH-GAP-008` | `W7` | Promoted model artifact/digest, PT-TFLite equivalence, device performance and safe rollback evidence | `docs/control/execution/artifact-remediation/20260726/w7/implementation-receipt.json`, `docs/control/execution/artifact-remediation/20260726/w7/validation-summary.json`, `docs/control/execution/artifact-remediation/20260726/w7/independent-review.md`, `docs/control/execution/artifact-remediation/20260726/w7/artifact-status-delta.json` |
| `W2-ARCH-GAP-009` | `W8` | Production domains/TLS/reverse proxy/firewall, service packaging, DB/object-store/KMS topology and third-party release inventory | `docs/control/execution/artifact-remediation/20260726/w8/implementation-receipt.json`, `docs/control/execution/artifact-remediation/20260726/w8/validation-summary.json`, `docs/control/execution/artifact-remediation/20260726/w8/independent-review.md`, `docs/control/execution/artifact-remediation/20260726/w8/artifact-status-delta.json` |
| `W2-ARCH-GAP-010` | `W8` | Backup/restore, data-safe deployment rollback and single-admin recovery drill evidence | `docs/control/execution/artifact-remediation/20260726/w8/implementation-receipt.json`, `docs/control/execution/artifact-remediation/20260726/w8/validation-summary.json`, `docs/control/execution/artifact-remediation/20260726/w8/independent-review.md`, `docs/control/execution/artifact-remediation/20260726/w8/artifact-status-delta.json` |
| `W2-ARCH-GAP-011` | `W9` | Operational structured logging, metrics/traces, correlation, alert ownership and retention thresholds | `docs/control/execution/artifact-remediation/20260726/w9/implementation-receipt.json`, `docs/control/execution/artifact-remediation/20260726/w9/validation-summary.json`, `docs/control/execution/artifact-remediation/20260726/w9/independent-review.md`, `docs/control/execution/artifact-remediation/20260726/w9/artifact-status-delta.json` |

- gap 수: `11`, unique: `11`
- orphan: `0`, duplicate: `0`, empty owner/closure/evidence: `0`
- 구현 gap: `W3`, 정식·통합시험: `W4`, 보안·개인정보: `W5`, AI/ML 권리·평가: `W6/W7`, 배포·복구: `W8`, 운영 관측: `W9`

## 검증·출시 경계

- FP035 내부 표적 단위시험: `22 PASS` (`17 + 5`, 실패·오류·skip 0)
- 정식시험: `279 NOT_RUN`, PASS 미주장
- release gate: `5 NOT_RUN`, `waived=false`
- 실제 기기·사용자·TalkBack·네트워크·socket·현장·PostGIS·live TMAP·production: `NOT_RUN_OR_OPEN`
- release: `NOT_ELIGIBLE`
