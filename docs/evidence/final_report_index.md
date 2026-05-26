# Final Report Evidence Index

- 기준: `plans/features/2026-05-26_feature_followup_implementation_list.md` 240개 항목
- 작성일: 2026-05-26 KST
- 범위: local/mock/static/headless/integration 검증 근거 색인
- 제외: 실제 전화/SMS, 실제 LLM/Cloud NLU, 운영 배포, 외부 기관 제출, destructive retention, 실기기 field 완료 주장

## 핵심 evidence

| Evidence | 경로 | 범위 |
|---|---|---|
| Feature matrix | `docs/evidence/feature_matrix.md` | 240개 항목별 evidence 등급/상태 |
| Evidence templates | `docs/evidence/templates.md` | PASS/BLOCKED/미검증 표준 |
| Privacy checklist | `docs/evidence/privacy_checklist.md` | local-only/보존/no-store/export/privacy 검증 |
| Safe slice 통합 실행 기록 | `docs/execution/2026-05-26_feature_followup_safe_slice.md` | 1차 safe slice |
| 최종 통합 검증 기록 | `docs/execution/2026-05-26_feature_followup_final_verification.md` | 부모 통합 재검증 |
| 커밋 준비 manifest | `docs/execution/2026-05-26_commit_prep_manifest.md` | 커밋 묶음/제외 패턴 |
| PR 요약 draft | `docs/execution/2026-05-26_pr_summary_240_followup.md` | PR/보고용 변경 요약 |
| Navigation/Reroute 정책 | `docs/walksafe-v2/navigation_integration_policy.md` | live TMAP 자동 재탐색 gate/쿨다운/GPS 튐 제한 |
| Admin/Reports/Privacy 실행 기록 | `docs/execution/2026-05-26_admin_reports_privacy_evidence_worker.md` | report/export/summary/retention |
| DeviceMotion/PWA 실행 기록 | `docs/execution/2026-05-26_devicemotion_settings_pwa_worker.md` | 보폭/DeviceMotion/PWA/settings |
| Data retention policy | `docs/data_retention_policy.md` | 보존 기간/삭제 승인 gate/dry-run |
| API reference | `docs/api_reference.md` | 현재 API 계약 |
| Backend v2 contract | `docs/walksafe-v2/backend_api_contract.md` | v2 backend 계약 |

## 대표 검증 명령

| 명령 | 결과 | 주의 |
|---|---|---|
| `PYTHONPATH=. .venv/bin/python -m pytest backend/tests -q -rs` | 88 passed | local test DB/ASGI 기준 |
| `PYTHONPATH=. .venv/bin/python -m pytest tests model -q` | 85 passed | voice/model unit 기준 |
| frontend policy scripts 전체 | PASS | TypeScript fixture/static 기준, motion ROI/PWA/offline queue 포함 |
| `cd apps/web && npm run typecheck && npm run lint && npm run build` | PASS | local production build, 배포 아님 |
| `scripts/check_voice_contract.py` against local uvicorn | PASS | STT/TTS 모델 추론 없음, validation/contract smoke |
| `scripts/check_detect_report_export_trace_20260524.py` | PASS | local ASGI/PostGIS fixture 기준 |
| `scripts/check_report_retention_dry_run.py` | PASS | dry-run only, 삭제 안 함 |

## 완료 주장 제한

- `[Device PASS]`는 아직 없다. Android 실폰 카메라/GPS/DeviceMotion/TTS/진동/mic/TalkBack field 기록이 필요하다.
- `[Release PASS]`는 아직 없다. 배포 URL, secret/domain/storage/rollback, 외부 공개 승인이 필요하다.
- 실제 전화/SMS와 실제 LLM/Cloud NLU는 이번 구현 범위에서 제외했다.
- live TMAP 자동 재탐색은 코드 경로와 safety gate를 구현했지만, 이번 검증에서 live provider 호출은 dry-run만 수행했다.
