# 2026-05-15 PWA Production Server E2E

작성 시각: 2026-05-15 KST

## 범위

`next dev` 기준으로 통과한 PWA server detection E2E를 production build/start 기준으로 확장해 검증했다.

검증 목표:

1. `NEXT_PUBLIC_DETECTOR_MODE=server`와 `NEXT_PUBLIC_API_BASE_URL=http://127.0.0.1:8000`를 build time에 주입한다.
2. `npm run build` 후 `npm run start`로 PWA를 실행한다.
3. headless Chromium에서 known-positive camera stream을 주입한다.
4. `/detect` server detection이 발생하고, 신고 저장 결과의 `source`와 `metadata.source`가 모두 `server`인지 확인한다.

## 병렬 조사 요약

- `NEXT_PUBLIC_*` 값은 browser bundle에 build time에 반영되므로 `npm run build` 전에 지정해야 한다.
- `next start`는 `--hostname`, `--port` 옵션을 지원한다.
- `next build`는 `apps/web/next-env.d.ts`의 generated import를 `.next/dev/types`에서 `.next/types`로 바꿀 수 있다.

## 변경 사항

`check_pwa_server_e2e.py`에 production 실행 옵션을 추가했다.

- 새 옵션:
  - `--web-mode dev|start` default `dev`
  - `--skip-web-build`는 `--web-mode start`에서 기존 `.next` build를 재사용할 때만 사용
- `--web-mode start` 기본 동작:
  - `npm run build` 실행
  - `npm run start -- --hostname 127.0.0.1 --port <web-port>` 실행
  - 기존 backend/Chromium/CDP/report 검증 흐름은 동일

## 실행

사전 reset:

```bash
docker exec walksafe-postgis psql -U walksafe -d walksafe -c "TRUNCATE TABLE reports RESTART IDENTITY;"
rm -f backend/uploads/*.jpg backend/uploads/*.jpeg backend/uploads/*.png backend/uploads/*.webp backend/uploads/test/*
```

production E2E:

```bash
.venv/bin/python scripts/check_pwa_server_e2e.py --web-mode start
```

결과: PASS.

```text
PWA server-mode browser E2E passed.
web_mode=start
model_status=ready model_version=walksafe-kr-tactile-v2-full-20260514-best-02a6be87
browser_video={'width': 1118, 'height': 1987, 'paused': False, 'readyState': 4, 'hasSrcObject': True} report_text='현재 위험 신고\n신고 저장 완료: 6b9fac54'
report=id=6b9fac54-cad3-4f8f-91b4-0da423ebc04f source=server class=damaged_tactile_block confidence=0.9445814490318298
logs=/tmp/walksafe-pwa-e2e-61bm_9z_
```

로그 핵심:

- `web-build.log`: `npm run build` PASS, static routes `/`, `/_not-found`, `/admin` 생성.
- `web.log`: `npm run start -- --hostname 127.0.0.1 --port 3000`, ready.
- `backend.log`:
  - `GET /detect/health` HTTP `200`
  - `POST /detect` HTTP `200`
  - `POST /reports` HTTP `201`
  - `GET /reports?source=server&limit=5` HTTP `200`

DB 확인:

```text
id=6b9fac54-cad3-4f8f-91b4-0da423ebc04f
source=server
class_name=damaged_tactile_block
confidence=0.9445814490318298
metadata_source=server
image_path=/uploads/6b9fac54-cad3-4f8f-91b4-0da423ebc04f.jpg
```

## 후처리

- `next build`로 생긴 `apps/web/next-env.d.ts` generated churn은 실행 전 clean 상태였으므로 원복했다.
- E2E report와 업로드 이미지는 확인 후 reset했다.

최종 확인:

```text
reports count: 0
backend/uploads: .gitkeep only
3000/8000/9222 listener 없음
5432 PostGIS listener 유지
```

## 제한/미실행

- 실폰 재검증은 사용자 메모대로 이번 범위에서 하지 않았다.
- production E2E는 local `next start` 기준이며, 실제 배포 환경/도메인/HTTPS 검증은 하지 않았다.
