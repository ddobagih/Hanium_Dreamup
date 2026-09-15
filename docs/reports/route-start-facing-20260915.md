# 안내 시작의 경로 준비·첫 방향·음성 일치 개선

## 요청
- 메인에는 후면 나침반 방향이 있는데 첫 안내가 경로 방향을 알 수 없다고 나오는 문제 해결.
- 시작 때 “저장된 TMAP 경로…” 안내 해결 및 실제 목적지→경로 수신 여부 검증.
- 현재 진행 구간을 선택하고 상대 시계 방향 안내를 적용. 단계별 개선·검증 수행.

## 계획과 인수 조건
1. 요청 체인 확인 → 목적지 선택/안내 시작 → Gateway → Backend → 실제 TMAP → Android 파서 → setRoute의 근거를 구분. 최근 서버 성공과 특정 사용자 사건은 구분.
2. 초기 상태 분리 → inactive/loading/ready/실제 invalid 구분. 요청 중 GPS가 들어와도 경로 부재 오류 발화·기존 발화 취소를 하지 않는다. 실제 파싱/경로 오류는 숨기지 않는다.
3. 현재 경로 구간 선택 → 요청 origin provenance와 신선한 현재 위치·경로 순서·이동 이력 활용. 주변 미래 구간 전체 합의만으로 첫 방향을 생략하지 않는다. 과거 origin·반대 중첩·비정상 좌표로 잘못된 branch를 고르지 않는다.
4. 상대 방향 음성 → 선택 구간의 앞으로 갈 방향과 현재 후면 나침반의 차이를 약 N시 방향으로 안내. 첫 코너/짧은 경로/시작점 offset/회전/후퇴/종점에서 의미와 거리·도착 판단을 분리한다.
5. 동일 자료 비교·독립 검수·APK → 전후 실패 재현, 기존 10초 주기·발화 보호·음성·세션 회귀 확인, 실제 TMAP 응답 파싱, 최종 빌드·다운로드 검증. 하드웨어 현장 성능은 USB 미연결로 별도 확인.

## 담당
- Root: Main 초기 GPS/요청 상태·경로 상태 표시·통합 검증·APK·기록.
- facing_guidance_policy: Navigator 구간 선택·clock 안내·해당 테스트.
- facing_heading_analysis: 서버 체인/공개 고정 좌표 provider 응답·Android 파싱 검증(제품 읽기 전용).
- navigation_voice_scenario_audit: 원인·설계·최종 독립 검수(읽기 전용).

## 착수 근거
- Main은 요청 중 isRouteActive=true인데 updateRouteGuidance가 routeRequestInFlight 선행 확인 없이 Navigator.update를 호출한다. Navigator는 요청 중 여부보다 route_missing을 먼저 반환한다. 같은 GPS 콜백에서 대기 중인 명시적 안내 시작을 요청한 직후에도 이어질 수 있다.
- “저장된 TMAP 경로…” 문구는 메모리 Navigator의 route/polyline 부재 분기다. 암호화 저장 오류는 별도 분기이며 이 문구가 캐시 사용/저장 실패를 증명하지 않는다.
- 현재 구간 선택은 나침반·GPS 오차 문제와 별도. 이전 후보 전체 합의/종점 오차 반경 일괄 차단의 정상 출발 누락 사례를 재현하고 보완한다.

## 경로 서비스 확인
- 최근 24시간 backend 보행 경로 요청 20/20, gateway 최근 15건 모두 HTTP 200. 특정 사용자의 해당 시도와 연결한 기록은 아니다. 목적지 검색은 성공 21건, timeout 504 1건으로 별도 집계.
- 공개 고정 좌표로 실제 TMAP 보행 API를 새로 요청: HTTP 200, 466m/368초, 원본 feature 10개. 정상화된 경로는 좌표 24개·step 5개·안내 지점 5개. 실제 Android BackendWalkingRouteClient 파서로 이를 읽어 거리/좌표/개수 보존을 확인했다. 좌표 순서 바꿈·비정상 provider code·mock provider 거부도 확인했다.
- 요청 출발점과 TMAP 첫 경로점의 차이는 15.313m. 출발점이 경로에 투영되는 차이가 실제로 존재하므로 출발 연결/현재 구간 시험에 활용한다.
- 목적지 선택과 경로 요청은 별도 상태이며, 안내 시작 시 요청 → 응답 검증 → setRoute → 첫 안내 순서다. 서비스 코드에 대체 모의 경로나 응답 캐시는 없다. 인증된 휴대폰 전체 흐름과 실제 현장 음성은 이번에 실행하지 않았다.
- 근거: `work/route-start-facing-20260915/provider/result.md`, `live-route-result.json`, `android-parser-result.json`, `recent-service-route-summary.json`.

## Main 구현
- 안내 미활성/경로 요청 중 위치 갱신은 Navigator 진행 처리를 하지 않는다. 경로가 없는데도 route_invalid로 들어가던 초기 요청 경쟁을 차단한다. 실제 route/polyline 손실은 별도 오류 안내를 유지한다.
- 응답에 세션 epoch와 요청 세대 검사를 적용한 뒤 요청 당시 origin을 그대로 Navigator에 전달한다. 위치의 원시 관측 시각을 현재 시각으로 바꾸지 않는다.
- 메인 화면에 목적지 선택·경로 찾는 중·경로 설정 완료·실제 경로 누락을 구분해 표시한다. 접근성 live region은 NONE으로 두고 UI 스레드에서 최신 상태를 다시 계산한다.
- 첫 경로 설치와 상대방향 선택 사유/구간/경로 방위/후면 방위/센서 출처/관측 나이를 진단 기록한다. 좌표나 API 비밀키를 새 로그에 추가하지 않는다.

## 독립 검수 및 검증 경계
- Main 독립 검수에서 새 상태 TextView의 UI 스레드 경계 지적을 수정하고 재검수했다. 요청 응답 설치·원래 origin·플래그 해제·첫 안내 순서와 취소/계정 전환의 기존 요청 세대 보호를 소스로 확인했다. 현재 미해결 P1/P2 지적 없음.
- Main 신규 행위 시험은 요청 중 GPS·안내 미활성 GPS·재탐색 중 GPS 및 화면 상태를 검사한다. 실제 Activity의 네트워크 응답→TTS 엔진 전체 동작 시험은 아니다. 이전 Main bytecode 직접 시험은 Android JVM mock 제한에 걸려 이를 수정 전 제품 실패 건수로 집계하지 않는다.

## 독립 검수에서 추가로 재현한 문제
- 공개 경로의 시작점 스냅을 단순 직선 뒤쪽 offset뿐 아니라 측면 offset에도 적용해야 한다. 동쪽 첫 선분, 시작점보다 동5m/북15m인 현재점, accuracy3m, 남쪽 시선에서 출발 leg9시를 말하던 반례를 실제 Kotlin 클래스에 실행해 확인. 시작점 연결 방향으로 수정하고 재검증한다.
- 첫 선분이7m인 경로에서 응답이30초 늦고 사용자가 첫 코너에 이미 도달한 경우, 요청 origin의8m허용 안이라는 이유로 이전 동쪽9시를 말하는 반례를 재현. 신선한 실제 진행 근거가 첫 선분 끝을 지났다면 departure authority를 종료하고 현재 구간으로 전환한다. 위치 오차 내 작은 흔들림으로 조기전환하지 않는 보호도 검사한다.
- Root 검토에서 selector가 방위를 선택했어도 위치불확실+compass누락이면 경로방위없음으로 문구를 바꾸는 문제 확인. routeBearingAvailable는 선택된 방위 존재 여부만 따르고, 위치불확실/나침반상태는 각각 전달하도록 수정한다.

## 최종 구현과 회귀 시험
- RouteAlignmentSelector가 원래 요청 origin, 신선한 현재 위치, 수락된 경로 projection/구간과 실제 이동을 사용해 출발/연결/현재 구간을 구분한다. 첫 코너 주변의 모든 미래 선분이 같은 방향일 필요는 없다. 다만 실제 반대 왕복 중첩/분기 전환 대기/비정상 위치는 임의로 선택하지 않는다.
- 상대방향은 선택된 경로 진북 방위와 후면 나침반 차이를 계산해 약 N시로 읽는다. 출발 순서 근거는 “출발 경로는”, 명백한 provider snap 연결은 “경로 시작점은”이라고 말해 현재 도로 위치를 단정하지 않는다. 이미 시작한 발화는 유지하고 정상 완료 후10초 재안내 및 수동 요청 때 새 시선으로 계산한다.
- 20/50/90m 짧은 경로, 첫 코너, 늦은 응답과 대기 중 코너 도달/통과, 측면 시작점 offset, 정지 회전, 후퇴와 재접근, 실제 중첩과 종점, 불량/오래된 나침반 등을 검사했다. 위치불확실 문구는 별도로 유지한다.
- 직접 Kotlin 관련177개 통과. 독립 지적 수정 전 같은177개 중3실패 → 수정 후177개 모두 통과. 이 숫자는 최종 Gradle 범위와 겹치므로 합산하지 않는다.
- 최종 Android 선택 회귀165 suites/1582 tests, failures0/errors0/skipped0. APK assembleDebug 통과(16.41초). 기존 무관한 정적불일치12개 제외목록 그대로이며 신규 제외 없음. 전체 저장소 시험 통과 주장은 아니다.
- Main 신규5개 모두 통과. 특정 파일의 공백 검사 통과. build후 Main9a389bcb/Selector a9bcaf8e/Navigator16d0eb21/Facing a7e2bc40으로 담당 동결 버전과 일치.
- 공개 TMAP fixture의 고정 입력에서 accuracy3m/서쪽 시선은 “경로 시작점은 약9시”, accuracy20m/서쪽 시선은 “출발 경로는 약12시”로 구분된다. 둘 다 전체466m 및 다음51m 후우회전 안내까지 생성된다. 실제 사용자 현장/물리 음성의 재현이 아니다.
- 근거: `work/route-start-facing-20260915/integration/{command.json,tests.json,gradle.log,apk.json,review-source.json}`, `ordered-alignment/after-review-fixes/`.

## 최종 독립 재검수와 APK
- 동결된 동일 SHA에 대해 독립 검수 완료. 실제 컴파일 클래스에 같은 입력으로 측면 offset→198.43도/1시, 지연7m코너→현재구간1/180도/12시, 코너통과20m→12시를 재실행해 확인했다. 관련6개 클래스55건 별도통과(최종Gradle범위와중복). 검토 범위 내 잔여 P1/P2 없음.
- 원본 패키지 `kr.co.hanium.dreamup.walksafe`, 이전APK와 같은 서명 확인. 외부HEAD200/Content-Length일치 및 GET APK ZIP헤더 확인. SHA-256 `aa4bc71a2c46fff4250c2479e295e4030f5c58e7ebcf5e5ba90d623941812d6f`, 453805096bytes.
- [새 APK 다운로드](https://advisors-skirt-editing-assessing.trycloudflare.com/WalkSafe-route-start-20260915.apk). 이전 APK/링크 보존. 임시 다운로드 링크는 현재 PC의 서버·터널 실행 중 유효하다. API origin은 기존 외부 Gateway로 유지했다.
- USB연결기기없어 설치하지 않았다. 사용자 계정/DB/모델은 변경하지 않았다. 목적지선택→안내시작→메인경로설정완료→현재후면기준첫음성→정지회전/보행중주기안내의 실제 휴대폰 확인은 사용자 현장 시험 범위다.
- 기록: `work/route-start-facing-20260915/independent-review-summary.json`, `published-apk.json`.

### 기존 기대 변경의 근거
- 기존156개 중4개 문구/정책 기대를 이번 요구에 맞게 갱신했다. BearingCandidates3개는 인접코너 전체방위 합의 대신 수락된 현재구간과 실제 전달된 filtered/unsnapped위치로 선택하고, retry에서도 현재구간을 새 시선으로 표현하도록 변경했다.
- UncertainGuidance1개는 실제 선택된 경로방위가 있으면 나침반 누락을 바라보는방향 확인불가로 표현한다. 위치추정 문구·토큰무효화·진행중발화보호 검사는 유지했다. 테스트를 제외해서 숨긴 변경이 아니다.
