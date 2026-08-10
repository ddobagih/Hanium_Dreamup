# WalkSafe Android Gateway

Android가 사용하는 네 개의 API와 앱 밖 개인정보 권리요청·통합 동의 제어면을 제공하는 독립 Node.js 22 + TypeScript 서비스입니다. Next.js, React, Web/PWA 런타임을 사용하지 않습니다.

## 현재 권한 경계

- 상태: `NOT_DEPLOYED` — Phase E 구현과 로컬 계약 검증만 완료 대상으로 합니다.
- 이 패키지의 테스트 통과는 운영 배포, 실제 휴대전화 연결, 정식 시험 또는 출시 승인을 뜻하지 않습니다.
- 실제 운영 URL은 이 저장소에 고정하지 않습니다.
- 백엔드는 정확히 `http://127.0.0.1:8000`만 허용하고 외부에 직접 노출하지 않습니다.

## 공개 계약

정확히 다음 경로와 메서드만 제공합니다. 후행 슬래시, health 경로 및 그 밖의 경로는 404입니다. 알려진 경로의 다른 메서드는 405입니다.

| 경로 | 메서드 | 요청 제한 | 게이트웨이 제한 |
| --- | --- | ---: | ---: |
| `/api/field-session` | GET, POST, DELETE | POST JSON 4 KiB | 본문 읽기 10초 |
| `/api/navigation/walking` | POST | 16 KiB | 본문 읽기 10초, upstream 15초 |
| `/api/navigation/destinations/search` | GET | query string | upstream 15초 |
| `/api/reports/v2` | POST | multipart 전체 9 MiB | 본문/upstream 각 15초, 동시 업로드 1건 |

모든 응답에는 `Cache-Control: no-store`를 설정합니다. 백엔드의 401/403은 내부 서비스 자격 증명 실패이므로 Android 사용자 세션 오류로 노출하지 않고 502 `gateway_upstream_auth_failed`로 변환합니다.

신고 multipart는 `metadata` 문자열 1개(최대 64 KiB)와 `image` JPEG 1개(1 byte~8 MiB)만 허용합니다. 중복 part, 추가 필드, 다른 이미지 형식은 Backend로 전달하지 않습니다.

세션 쿠키 이름은 `walksafe_field_session`입니다. 쿠키는 `Path=/`, `HttpOnly`, `SameSite=Strict`이며 HTTPS 요청에는 `Secure`를 붙입니다. 기본 단기 세션은 최대 12시간입니다. 로그인 token은 세션 생성에만 사용하며 Android의 이후 API 요청이나 백엔드 응답으로 전달하지 않습니다. 단기 로그인 body의 선택 필드 `purpose`는 `general|account_deletion_recovery`이며 생략하면 legacy 호환으로 `general`입니다. 성공은 200 JSON의 정확한 `session_scope`와 cookie를 반환하고 기본 GET 상태도 같은 scope를 확인합니다. recovery scope는 계정 삭제 v2 세 경로, 세션 상태 조회, query/body 없는 logout에만 쓸 수 있고 보행·길찾기·신고·동의·기기·refresh 작업은 403으로 거부합니다. scope는 cookie MAC과 암호화된 active-session 상태에 함께 결속됩니다. rollout 전에 남은 exact v3 3-key 상태는 startup 검사는 통과시키되 인증에는 사용하지 않으며, 사용자는 새 scope-bound 로그인이 필요합니다.

세부 공개 형식은 `openapi.json`이 기준입니다. 내부 `x-walksafe-*` 서비스 token과 actor assertion은 공개 계약이 아닙니다.

`GET|HEAD /privacy/rights`는 위 네 API와 분리된 HTML 안내 화면입니다. 앱 설치와 로그인 cookie 없이 열 수 있고, 서버 자료 열람·동의 철회·삭제 요청을 승인된 외부 접수 URL로 연결합니다. 이 경로는 API OpenAPI에 추가하지 않으며 POST는 405, 설정이 없으면 503으로 닫힙니다.

같은 정확 경로의 `GET|PUT /privacy/rights?control=integrated-consent`는 FP013 통합 동의 제어면입니다. 로그인 전 첫 실행에서 쓰므로 field cookie 대신 설치별 256-bit 제어 비밀을 요구하며, 서버에는 그 SHA-256만 저장합니다. 원본 수집, 자동신고, 이동통신 전송, 학습 재사용은 독립 boolean과 독립 항목 버전을 가지며, 영구 증가 client revision이 늦게 도착한 이전 선택의 역행을 막습니다. 설치 ID별 원장은 hash chain과 PID·token·inode 소유권을 확인하는 프로세스 간 0600 잠금 파일을 사용하고, 0600 임시 파일 fsync, 원자 rename, 디렉터리 fsync 순서로 커밋합니다. 로그인 전 동의는 첫 인증 신고 시 field actor에 결속되고, 다른 actor는 새 동의 revision을 명시적으로 저장하기 전까지 그 영수증을 사용할 수 없습니다. 신고는 현재 항목 버전, 원본 수집, 자동신고, 제어 비밀과 actor 결속이 모두 일치해야 합니다. 현재 계약은 인증된 first-party Android의 `x-walksafe-report-purpose: explicit|automatic` 선언을 multipart `metadata.auto_reported`와 교차 확인해 직접·자동 동의 요구를 분리합니다. 이 선언은 암호학적 client attestation이 아니며, 두 값을 함께 위조하는 인증된 변조 client는 `NOT_COVERED` 신뢰 경계입니다. Android는 관측 문자열이 아니라 선택한 실제 `Network`로 소켓을 열며, `cellular`이면 이동통신 선택도 Backend 전달 직전에 다시 확인합니다.

## FP011 장기 현장 세션

장기 세션은 기본 비활성입니다. 명시적인 활성화 값과 access·refresh idle·refresh absolute TTL 세 값이 모두 존재하고 보안 범위 안에 있을 때만 열립니다. 하나라도 없거나 잘못되면 장기 세션을 만들지 않으며 기존 단기 로그인은 계속 200 `session_scope=general`과 단기 cookie를 반환합니다. recovery purpose에는 `device_id`를 허용하지 않으며 장기 family를 만들 수 없습니다.

- 설치 로그인: `POST /api/field-session`에 `actor_id`, `token`, `device_id`를 보냅니다. 장기 모드에서는 200 JSON으로 `session_scope=general`, `actor_id`, `device_id`, `family_id`, `rotation`, `refresh_token`, 세 만료시각을 받고 짧은 access cookie를 함께 받습니다.
- 갱신: 같은 POST에 `grant_type=refresh_token`과 actor/device/family/rotation/refresh token을 JSON으로 보냅니다. 성공하면 정확히 한 단계 회전한 새 refresh token과 access cookie를 받습니다.
- 상태·기기: 기본 GET은 기존 상태 응답을 유지합니다. 유효한 장기 access cookie의 상태에는 장기 세션 메타데이터가 추가됩니다. `GET ?devices=true`는 현재 actor의 활성 기기만 반환합니다.
- 철회: 기본 DELETE는 현재 cookie family를 철회합니다. access cookie가 없거나 만료된 복원 후보는 DELETE JSON body에 refresh proof를 보내 같은 family를 철회할 수 있습니다. refresh token은 URL이나 header에 넣지 않습니다. 올바른 형식의 유효·불일치·이미 철회된 proof는 token oracle을 만들지 않도록 모두 멱등 204입니다.
- 선택 철회: `DELETE ?device_id=...`는 유효한 access cookie의 actor에만 묶이며 다른 actor의 같은 device ID에는 영향을 주지 않습니다.

서버는 raw refresh token을 성공 JSON 응답에 한 번 반환할 뿐 파일이나 로그에 저장하지 않고 SHA-256 digest만 보관합니다. 갱신은 actor별 단일 원자 구간에서 파일 교체로 커밋합니다. 이미 소비한 digest가 다시 오면 그 family의 refresh와 access를 모두 철회하지만 다른 기기 family는 유지합니다. 무작위 불일치는 family를 철회하지 않습니다. idle 또는 absolute 만료 시에는 재로그인이 필요합니다.

저장 오류로 반환하는 503은 회전 커밋 전에 발생하므로 같은 요청을 재시도할 수 있습니다. 반면 Android가 응답을 전혀 받지 못한 연결 중단은 서버 커밋 여부를 확정할 수 없으므로 자동 rotation 증가나 무한 재시도를 하지 않고 재로그인으로 닫아야 합니다. account lock과 security incident는 내부 호출 `revokeFieldSessionsForSecurityEvent()`로 해당 actor의 단기·장기 family 전체를 철회합니다.

## 실행 계약

```bash
npm ci
npm run typecheck
npm run build
npm test
npm start
```

`npm run build`는 이 패키지의 `dist`만 먼저 비워 삭제된 소스의 오래된 출력이 남지 않게 합니다. `npm start`는 `node dist/server.js`를 실행하며 기본 bind는 `127.0.0.1:8081`입니다.

필수 운영 설정:

- `NODE_ENV=production`
- `BACKEND_API_BASE_URL=http://127.0.0.1:8000`
- `WALKSAFE_FIELD_TEST_TOKEN`: 백엔드 전용 field service token, 24자 이상
- `WALKSAFE_FIELD_ACCOUNTS_JSON`: `[{"actor_id":"...","token":"..."}]` 형식의 사용자별 로그인 계정
- `WALKSAFE_GATEWAY_SESSION_SECRET`: 다른 자격 증명과 분리된 32자 이상 secret
- `WALKSAFE_GATEWAY_TRUSTED_IP_HEADER`: `cf-connecting-ip` 또는 `x-real-ip`
- `WALKSAFE_GATEWAY_RATE_LIMIT_DIR`: 절대 경로, 서비스 계정 소유, 권한 0700. 로그인 제한, 장기 세션, 통합 동의 원장의 공통 상위 디렉터리입니다.
- `WALKSAFE_GATEWAY_STATE_KEYRING_FILE`: writable 상태 디렉터리 밖의 canonical 절대 경로. production에서는 `root:<service-group>` 소유 0640, 정확히 하나의 active AES-256 키가 필요합니다.
- `WALKSAFE_FIELD_WALK_LEDGER_PATH`: 암호화된 현장 보행 원장의 canonical 절대 경로. production에는 임시 경로 기본값이 없습니다.
- `WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL`: 앱 밖에서도 접근 가능한 승인된 HTTPS 권리요청 접수 URL

선택 설정:

- `WALKSAFE_ANDROID_GATEWAY_HOST`: 생략 시 `127.0.0.1`; 보안 경계상 다른 값은 거부
- `WALKSAFE_ANDROID_GATEWAY_PORT`: 생략 시 `8081`

장기 세션을 별도로 승인해 켤 때만 함께 설정:

- `WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED=true`
- `WALKSAFE_FIELD_ACCESS_TTL_SECONDS`: 60~3600초
- `WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS`: 300~7776000초이며 access TTL 이상
- `WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS`: 3600~31536000초이며 idle TTL 이상

TTL에는 암묵적인 기본값이 없습니다. 활성화 flag가 없거나 세 값 중 하나라도 누락·범위 초과·순서 위반이면 장기 모드는 닫힙니다.

## 상태 열쇠 회전·마이그레이션

Gateway는 시작할 때 검증한 keyring을 프로세스 수명 동안 고정합니다. 실행 중 파일을 바꿔도 새 active/compromised 상태를 다시 읽지 않으므로, keyring 변경은 반드시 서비스 중지 상태에서 수행하고 재시작해야 합니다.

1. Gateway 서비스를 중지하고 실행 중 프로세스가 없음을 확인합니다.
2. 기존 active를 `decrypt-only` 또는 `compromised`로 전이하고 정확히 하나의 새 active를 둔 keyring을 별도 열쇠 관리 경계에서 원자적으로 발행합니다. compromised 항목에는 열쇠 재료 대신 과거 32-byte 재료의 소문자 SHA-256을 `key_sha256`으로 남깁니다. Gateway는 이 fingerprint를 모든 active/decrypt-only 재료와 대조해 손상 재료의 새 key ID 재사용을 거부합니다.
3. 서비스 유닛이 아닌 일회성 유지보수 명령에만 `WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED=true`를 주고 `npm run state-encryption-maintenance -- inspect` 후 필요에 따라 `rotate` 또는 승인된 `migrate-plaintext`를 실행합니다.
4. `inspect`에서 plaintext, unknown key, compromised key가 0인지 확인한 뒤 acknowledgement를 제거하고 서비스를 새로 시작합니다.

keyring과 암호화 원장에는 symlink·hardlink를 사용하지 않습니다. 운영 keyring은 root:service-group 소유의 exact `0640` 파일이어야 하고 모든 상위 디렉터리는 root 소유이며 group/other 쓰기가 금지됩니다. keyring은 writable 상태 경계와 분리하고, 열쇠 파일을 실행 중 직접 편집하거나 maintenance acknowledgement를 서비스 환경에 영구 설정하지 않습니다. Gateway는 listen 전에 관리 대상 상태 전체를 복호화·schema 검증하며 plaintext, unknown key, compromised key가 하나라도 있으면 시작하지 않습니다. 실제 KMS·운영 회전·복구훈련은 이 저장소 내부 절차로 수행됐다고 간주하지 않습니다.

서비스가 loopback에 직접 연결된 Android 요청을 받을 때 신뢰 IP 헤더가 없고 실제 socket peer도 loopback이면, 설정된 신뢰 헤더에 그 peer 주소를 보충합니다. 이미 Nginx가 설정한 신뢰 헤더는 덮어쓰지 않습니다.

## 단일 프로세스 제약

세션 상태와 로그인 제한은 소유자 전용 파일에, actor별 회전 잠금과 업로드 동시성·속도 제한은 프로세스 메모리에 저장됩니다. 장기 세션 파일에는 refresh/access의 digest와 회전·만료·철회 상태만 들어갑니다. 따라서 이 버전은 반드시 한 프로세스·한 worker·한 replica로 실행해야 합니다. Node cluster worker 실행은 거부합니다. 수평 확장은 회전과 재사용 탐지를 원자적으로 수행하는 공유 상태 저장소로 이전한 후 별도 변경으로 진행해야 합니다.

## 계정 삭제 제어 (FP-046)

- `POST /privacy/account-deletions`은 `walksafe.account-deletion-request.v2`, 요청 ID, 단조 증가 client revision, `DELETE_MY_ACCOUNT` 확인값을 사용합니다. 새 요청에는 `general` 또는 `account_deletion_recovery` field session과 32-byte client capability가 모두 필요하고, 정확한 replay에는 capability만 필요합니다.
- `GET /privacy/account-deletions/{request_id}/status`와 `POST /privacy/account-deletions/{request_id}/device-evidence`는 field session에 의존하지 않고 요청에 결속된 capability만 요구합니다. capability는 `x-walksafe-deletion-access-secret`에 43자리 unpadded base64url로 전달하며 원문은 저장하거나 backend로 전달하지 않습니다.
- 잘못된 capability와 알 수 없는 요청은 동일한 404/no-store로 숨깁니다. 세 경로 모두 recovery cookie 동반을 허용하지만 status/evidence 권한은 capability에서만 나옵니다.
- 새 요청 접수는 actor generation을 먼저 durable fence한 뒤 기존 단기·장기 field session을 폐기합니다. gateway는 서버·학습·백업 삭제 완료를 추정하지 않고 backend의 canonical v2 상태와 durable evidence만 반영합니다.

### 보고 목적 신뢰 경계 (FP-015)

- 개인정보 ledger의 프로세스 간 배타 잠금은 Linux `/usr/bin/flock`과 fd 3 상속을 runtime prerequisite로 요구한다. helper 부재, timeout, 비정상 종료는 fail-closed하며 stable `ledger.lock` path는 삭제하지 않고 process crash 시 kernel fd close로 자동 해제된다.

- `x-walksafe-report-purpose`는 gateway가 인증한 first-party Android 앱의 선언이며 암호학적 client attestation이 아니다.
- gateway는 이 header와 multipart `metadata.auto_reported`를 교차 확인하고 불일치하면 backend fetch 전에 fail-closed한다.
- 변조됐지만 유효한 자격 증명을 가진 Android client가 두 값을 함께 위조하는 경우는 이 gateway 계약의 `NOT_COVERED` 잔여 신뢰 경계다.
- report 요청에는 installation, consent control secret, policy, revision, receipt, current network transport, report purpose header가 모두 필요하다. transport/동의 precondition 누락은 428이며 선택 거부, stale receipt·generation, metadata 불일치는 각각 명시적 오류로 거부한다.
- 삭제 요청 replay는 암호화된 actor recovery envelope로 field 세션 폐기를 재시도한다. tombstone은 폐기 작업 성공 여부와 무관하게 기존 access·refresh를 즉시 거부한다.
- 로그인 없는 account-deletion POST에서 알 수 없는 request ID와 기존 request의 틀린 secret은 동일한 404/no-store 응답을 사용한다.
