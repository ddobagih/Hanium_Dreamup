# Local model registry

This directory records immutable artifact and dataset hashes. `candidate` does not mean deployed.

```bash
python scripts/manage_local_model_registry.py verify
python scripts/manage_local_model_registry.py retention-plan
```

Promotion and rollback only update `model/deployments/local-deployment.json`; they never edit Android,
backend or Next.js runtime configuration. Promotion requires a registry entry with
`deployment_eligible=true` and an exact `--approve <model-id>` argument.

Retention is non-destructive by default. `quarantine` is available only for an archived, unreferenced
entry whose registry policy has `retain=false`, and requires an exact approval argument.

## 현재 Android 구성 검증

`python scripts/manage_local_model_registry.py verify-runtime`은 과거 deployment manifest의
참조 파일 검증과 현재 Android asset JSON에서 관측한 모델 검증을 별도로 보고합니다.
`historical_manifest`의 Android 13종 기록은 보존하며, 현재 `observed_runtime.android`는
기존 앱 설정이 선택한 WalkMate 21종 후보입니다. 두 값의 차이는
`android_matches_observed_runtime=false`로 명시합니다. 이 명령은 backend 실행 환경을
읽지 않으므로 backend 프로세스 모델을 검증했다는 의미가 아닙니다.

현재 Android 검증은 등록된 경로·해시와 실제 TFLite/PT 파일, TFLite의 입출력 텐서,
내장 metadata의 클래스 순서, 앱 설정의 출력 계약을 대조합니다. 21종 항목의 추가는
기존 runtime의 관측 사실 등록이며 모델 선택·배포 승인·평가 완료를 바꾸지 않습니다.
학습·데이터 증거가 없는 후보는 계속 `deployment_eligible=false`입니다. 전체 `verify`는
별도로 학습·데이터 파일까지 요구하며, `verify-runtime` 성공으로 대체되지 않습니다.
