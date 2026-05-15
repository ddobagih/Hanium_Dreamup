# 2026-05-15 PWA Server Detection E2E

작성 시각: 2026-05-15 KST

## 범위

사용자 요청에 따라 병렬 에이전트로 조사한 뒤, 실폰 없이 PWA server mode에서 실제 검출이 발생하는 브라우저 E2E를 수행했다.

검증 목표:

1. PWA가 server mode로 실행된다.
2. 브라우저 runtime에서 known-positive frame이 카메라 프레임처럼 캡처된다.
3. PWA가 backend `/detect`를 호출해 server detection을 받는다.
4. 신고 버튼 클릭으로 `/reports`가 저장된다.
5. 저장된 report와 metadata의 `source`가 모두 `server`다.

## 병렬 조사 요약

- PWA 흐름 조사:
  - `apps/web/app/page.tsx`의 server detector effect는 `fetchDetectHealth()` -> `captureFrame()` -> `detectFrame()` 순서로 동작한다.
  - `handleReport()`는 현재 `detection`을 snapshot으로 만들고 `submitReport(reportSnapshot, image)`를 호출한다.
  - `apps/web/lib/detect-api.ts`는 backend payload를 `DetectionEvent`로 변환하며 `source: "server"`를 부여한다.
- 자동화 조사:
  - repo에는 Playwright/Puppeteer E2E 설정이 없다.
  - Chromium은 `/snap/bin/chromium` 사용 가능.
  - `ffmpeg`는 현재 shell 기준 설치되어 있지 않았다.

## 변경 사항

새 스크립트 추가:

- `scripts/check_pwa_server_e2e.py`

스크립트 동작:

- backend uvicorn을 실제 모델 env로 임시 기동한다.
- PWA dev server를 `NEXT_PUBLIC_DETECTOR_MODE=server`로 임시 기동한다.
- headless Chromium을 CDP remote debugging으로 기동한다.
- `Page.addScriptToEvaluateOnNewDocument`로 `navigator.mediaDevices.getUserMedia`를 덮어써 known-positive 이미지를 그린 `canvas.captureStream()`을 반환하게 한다.
- PWA가 detection을 받은 뒤 `현재 위험 신고` 버튼을 클릭한다.
- backend `/reports?source=server` 응답으로 저장 결과를 확인한다.
- 종료 시 backend/PWA/Chromium 임시 프로세스를 정리한다.

기본 fixture:

- `datasets/walksafe_kr_v2/images/val/aihub513_tactile_2_09_1_1_1_2_20210820_0000315742.jpg`

기본 모델:

- `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt`

## 실행

사전 정리:

```bash
docker exec walksafe-postgis psql -U walksafe -d walksafe -c "TRUNCATE TABLE reports RESTART IDENTITY;"
rm -f backend/uploads/*.jpg backend/uploads/*.jpeg backend/uploads/*.png backend/uploads/*.webp backend/uploads/test/*
```

스크립트 문법 확인:

```bash
.venv/bin/python -m py_compile scripts/check_pwa_server_e2e.py
```

E2E 실행:

```bash
.venv/bin/python scripts/check_pwa_server_e2e.py
```

결과: PASS.

```text
PWA server-mode browser E2E passed.
model_status=ready model_version=walksafe-kr-tactile-v2-full-20260514-best-02a6be87
browser_video={'width': 1118, 'height': 1987, 'paused': False, 'readyState': 4, 'hasSrcObject': True} report_text='현재 위험 신고\n신고 저장 완료: 339ef3d0'
report=id=339ef3d0-1246-4839-9b98-8b2cd27d6ad9 source=server class=damaged_tactile_block confidence=0.9445814490318298
logs=/tmp/walksafe-pwa-e2e-6eeo2bs7
```

DB 검증:

```sql
SELECT id, source, class_name, confidence, metadata->>'source' AS metadata_source, image_path
FROM reports
ORDER BY created_at DESC
LIMIT 1;
```

결과:

```text
id=339ef3d0-1246-4839-9b98-8b2cd27d6ad9
source=server
class_name=damaged_tactile_block
confidence=0.9445814490318298
metadata_source=server
image_path=/uploads/339ef3d0-1246-4839-9b98-8b2cd27d6ad9.jpg
```

참고: 위 결과의 스크립트 stdout에는 전체 UUID `339ef3d0-1246-4839-9b98-8b2cd27d6ad9`가 기록되었다.

## 후처리

E2E 확인 후, 사용자 요청의 test row reset 정책을 유지하기 위해 검증 report와 업로드 이미지를 다시 정리했다.

```bash
docker exec walksafe-postgis psql -U walksafe -d walksafe -c "TRUNCATE TABLE reports RESTART IDENTITY;"
rm -f backend/uploads/*.jpg backend/uploads/*.jpeg backend/uploads/*.png backend/uploads/*.webp backend/uploads/test/*
```

최종 확인:

```text
reports count: 0
backend/uploads: .gitkeep only
```

포트 상태:

- `3000`: listener 없음.
- `8000`: listener 없음.
- `9222`: listener 없음.
- `5432`: PostGIS listener 유지.

## 제한/미실행

- 실폰 카메라 재검증은 이번 범위에서 하지 않았다.
- PWA production build E2E는 실행하지 않았다. 이번 검증은 `next dev` server mode runtime 기준이다.
- Playwright/Puppeteer 의존성은 추가하지 않았다.
