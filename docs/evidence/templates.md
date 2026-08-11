# Evidence Templates

기능 구현/검증 결과는 아래 템플릿 중 하나로 기록한다. `PASS`는 실제 실행 결과와 artifact 경로가 있을 때만 사용한다.

## 1. PASS 템플릿

```md
## [<등급> PASS] <Ref/기능명> - <검증명>

- 기준 항목: <문서/Ref>
- 검증 일시/환경: <YYYY-MM-DD, OS/브라우저/기기/서버/DB>
- 실행 명령 또는 절차: `<command>` 또는 수동 절차
- 기대 결과: <사전에 정한 성공 조건>
- 실제 결과: <로그/관찰 결과 요약>
- 증거 파일: `<docs/execution/...>` / `<log path>` / `<screenshot path>`
- 적용 범위: <Static/Unit/Integration/Headless/Android Device/Model/GIS-Ops/Release 중 실제 범위>
- 제외/주의: <fake, headless, class 제한, device 미검증 등>
```

## 2. Android Device PASS 추가 필드

```md
## [Android Device PASS] <기능명> - <검증명>

- APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256: `<sha256>`
- 기기/OS/ARCore: `<device model>`, `<Android version>`, `<Google Play Services for AR version if known>`
- 절차: <중앙/좌/우/상/하 bbox, 거리 0.5m/1m/2m 등>
- 관찰 결과: <bbox offset, depth median/sample count, detector age, TTS/haptic 등>
- 증거: <logcat excerpt/screenshot/video/manual note path>
- 과대 주장 방지: <이 결과로 주장할 수 없는 것>
```

## 3. BLOCKED 템플릿

```md
## [<등급> BLOCKED: <원인>] <Ref/기능명> - <검증명>

- 기준 항목: <문서/Ref>
- 시도한 범위: <실행 전 확인/명령/절차>
- blocker: <env/secret/account/device/cost/long-run/destructive/external-send/user-decision>
- 현재까지 확인한 사실: <확인된 것만>
- 완료로 주장하지 않는 범위: <Android Device/Release/Model 등>
- 재개 조건: <필요 승인/기기/fixture/env>
- 다음 안전 작업: <mock/static/dry-run 등 가능하면>
```

## 4. 미검증 템플릿

```md
## [미검증] <Ref/기능명> - <검증 예정명>

- 기준 항목: <문서/Ref>
- 필요한 evidence 등급: <Static/Unit/Integration/Headless/Android Device/Model/GIS-Ops/Release>
- 필요한 입력/환경: <fixture, disposable DB, Android device, secret 승인 등>
- PASS 조건: <정확한 기대 결과>
- 과대 주장 방지: <이 검증만으로 주장할 수 없는 것>
```

## 5. badge 표기 규칙

| Badge | 사용 조건 | 금지되는 표현 |
|---|---|---|
| `[Static PASS]` | 정적 grep/link/schema 확인 결과가 있음 | 런타임 동작 완료 |
| `[Unit PASS]` | 단위 테스트/fixture 통과 로그가 있음 | API/브라우저/E2E 완료 |
| `[Integration PASS]` | 서버/DB/API smoke 통과 로그가 있음 | 실폰 field 완료 |
| `[Headless PASS]` | 브라우저/ASGI/fixture E2E 통과 | Android Device/TalkBack/GPS field 완료 |
| `[Android Device PASS]` | 실폰/ARCore/GUI/센서/음성/진동/TalkBack 증거가 있음 | headless/build 결과로 대체 |
| `[Model PASS]` | dataset/split/metric/hash가 명시됨 | fake/demo 또는 단일 class를 전체 성능으로 확대 |
| `[GIS/Ops PASS]` | GeoJSON/export/status/cluster 운영 검증 증거가 있음 | 실제 기관 제출 완료 |
| `[Release PASS]` | 승인된 배포/도메인/secret/rollback evidence가 있음 | 승인 없는 외부 공개/비용 발생 |
| `[BLOCKED: ...]` | blocker와 재개 조건이 명확함 | 완료/통과로 표현 |
| `[미검증]` | 아직 실행하지 않음 | “완료”, “검증됨” |

## 6. fake/headless/device/model 분리 문구

- fake/mock payload는 정책·UI·export 검증에는 쓸 수 있지만 실제 보행 안전, 모델 정확도, 지연시간, field 검증 근거가 아니다.
- headless/ASGI PASS는 contract와 흐름 검증이다. Android 착용형 camera, ARCore depth, GPS/heading, TTS/진동, mic, TalkBack은 별도 `Android Device` evidence가 필요하다.
- Android build PASS는 APK 생성 근거이지 bbox/depth/`N보` 정확도 근거가 아니다.
- static RGB model evidence는 detector/threshold 근거이지 ARCore metric depth 근거가 아니다.
- model evidence는 class 범위, dataset split, metric 정의, checkpoint/hash를 함께 적는다.
- 현재 backend `reports.source` enum은 `fake`/`onnx`/`server`다. Android native upload는 gate 이후 `source_model`/`runtime_mode` metadata 또는 별도 source enum으로 분리하고, fake를 성능 리포트에 포함하지 않는다.
