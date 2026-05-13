# Figma UI Handoff

작성 기준일: 2026-05-12

## 목적

Figma AI로 만든 WalkSafe Assist UI를 실제 구현 가능한 화면, 상태, API 계약으로 넘기기 위한 기준을 정리한다. 이 문서는 Figma 산출물 검수와 구현자 인계에 사용한다.

기준 문서:

- `DESIGN.md`: 색상, 타이포그래피, 접근성, 모바일 우선 원칙
- `docs/ui_feature_inventory.md`: 현재 구현/예정 기능 범위
- `docs/inference_contract.md`: 탐지 이벤트 계약
- `docs/frontend_api_examples.md`: API 요청 예시
- `docs/backend_error_contract.md`: 오류 처리 기준

## 현재 전제

- 모델은 학습 중이며 아직 UI에 연결하지 않는다.
- 실제 동작처럼 표현할 수 있는 탐지는 fake detector 또는 placeholder 상태뿐이다.
- 첫 화면은 랜딩 페이지가 아니라 보행 보조 화면이다.
- 사용자는 보행 중인 시각장애인/저시력 보행자이므로 장식보다 즉시 인지가 우선이다.
- 화면 문구는 한국어를 기본으로 한다.

## Figma 파일에 남길 메타 정보

Figma 파일 첫 페이지나 별도 메모에 아래 정보를 남긴다.

```text
Project: WalkSafe Assist
Design source: DESIGN.md
API contract: docs/inference_contract.md
Backend base URL: http://127.0.0.1:8000
Current detector state: fake / model_not_configured
Last updated:
Owner:
```

## 필수 화면

| 화면 | 목적 | 우선순위 |
| --- | --- | --- |
| 보행 보조 메인 | 카메라, 위험 상태, GPS, 음성, 신고를 한 화면에서 제공 | 필수 |
| 권한/센서 오류 | 카메라/GPS 권한 거부 또는 실패 안내 | 필수 |
| 신고 진행 상태 | 신고 전송 중, 성공, 실패 상태 표시 | 필수 |
| 모델 대기 상태 | `/detect/health`가 unavailable일 때 상태 표시 | 필수 |
| 관리자 신고 목록 | 신고 조회, 필터, 상세 확인, 상태 변경 | 선택 |

관리자 화면은 프로젝트 발표나 내부 검증에 필요하면 포함한다. 보행자용 MVP보다 우선하지 않는다.

## 보행 보조 메인 화면

필수 영역:

| 영역 | 내용 | 구현/API 연결 |
| --- | --- | --- |
| 카메라 프리뷰 | 화면 대부분을 차지하는 실시간 카메라 영역 | 브라우저 카메라 |
| 탐지 오버레이 | bbox, 위험 유형, confidence | `DetectionEvent.bbox`, `class_name`, `confidence` |
| 위험 상태 | 현재 위험 없음/탐지됨/대기 | fake detector 또는 `/detect` 결과 |
| GPS 상태 | 위치 대기, 정확도, 권한 실패 | `gps`, `accuracy_m` |
| 방향 상태 | 방향값 또는 대기 | `heading` |
| 음성 상태 | 음성 켜짐/꺼짐 | Web Speech API |
| 신고 버튼 | 현재 탐지 결과 신고 | `POST /reports` |
| 신고 상태 | 전송 중/성공/실패 | API 응답/오류 |

배치 기준:

- 카메라 프리뷰가 첫 viewport의 중심이어야 한다.
- 현재 위험, GPS, 음성, 신고 상태는 스크롤 없이 보여야 한다.
- 신고 버튼은 한 손 조작이 쉬운 하단 영역에 둔다.
- 위험 라벨은 카메라 중앙 시야를 가리지 않는다.

## 권한/센서 상태

| 상태 | 표시 문구 | 행동 |
| --- | --- | --- |
| 카메라 준비 중 | 카메라 준비 중 | 로딩 상태 |
| 카메라 권한 필요 | 카메라 권한을 허용해야 보행 화면을 사용할 수 있습니다. | 재시도 버튼 |
| 위치 대기 | 위치 대기 중 | 보조 상태 |
| 위치 권한 필요 | 위치 권한 필요 | 위치 없이 신고 가능 |
| 방향 대기 | 대기 중 | 보조 상태 |
| 모델 대기 | 모델 연결 대기 | fake 또는 로컬 탐지 모드 안내 |

권한 실패를 전체 차단 화면으로 만들지 않는다. 카메라가 없으면 보행 화면 핵심이 막히지만, GPS나 방향이 없어도 신고 흐름은 유지한다.

## 신고 상태

| 상태 | 표시 문구 | API 기준 |
| --- | --- | --- |
| 신고 대기 | 신고 대기 중 | 탐지 결과 없음 |
| 전송 중 | 신고 전송 중 | `POST /reports` pending |
| 성공 | 신고 저장 완료 | `201 Created` |
| 실패 | 신고 전송 실패 | 네트워크/API 오류 |
| 중복 가능 | 비슷한 신고가 있음 | `duplicate_report_ids` 존재 |

성공 후에는 신고 ID 전체를 크게 노출하지 않는다. 필요하면 앞 8자리만 보여준다.

## API 매핑

| UI 동작 | API | 화면 반영 |
| --- | --- | --- |
| 백엔드 연결 확인 | `GET /health` | 연결 가능/불가 |
| 모델 상태 확인 | `GET /detect/health` | 모델 대기/준비 상태 |
| 중복 확인 | `GET /reports/duplicate-check` | 비슷한 신고 안내 |
| 신고 생성 | `POST /reports` | 신고 전송 상태 |
| 신고 목록 조회 | `GET /reports` | 관리자 목록 |
| 신고 상세 조회 | `GET /reports/{report_id}` | 관리자 상세 |
| 상태 변경 | `PATCH /reports/{report_id}/status` | 관리자 상태 버튼 |

현재 `/detect/health`의 `model_not_configured`는 오류 팝업이 아니라 정상적인 모델 대기 상태로 표현한다.

## 상태별 UI Variant

Figma component variant로 최소한 아래 상태를 만든다.

| 컴포넌트 | variants |
| --- | --- |
| 위험 상태 배너 | `idle`, `detecting`, `risk`, `model_waiting` |
| GPS 상태 | `waiting`, `high`, `medium`, `low`, `missing` |
| 신고 버튼 | `disabled`, `ready`, `submitting`, `success`, `error` |
| 음성 토글 | `on`, `off` |
| 카메라 영역 | `loading`, `ready`, `permission_denied`, `error` |
| 관리자 신고 행 | `new`, `reviewed`, `resolved` |

Variant 이름은 영어로 두되, 화면에 보이는 텍스트는 한국어로 둔다.

## 시각/접근성 체크리스트

- 주요 터치 영역은 최소 48px 높이다.
- 색상만으로 위험/성공/오류를 구분하지 않는다.
- 텍스트는 카메라 배경 위에서도 읽힌다.
- 작은 보조 텍스트보다 상태 라벨과 버튼 문구가 우선이다.
- 한국어 텍스트에 음수 letter spacing을 쓰지 않는다.
- 장식용 gradient blob, orb, 마케팅 hero 구성을 쓰지 않는다.
- 카메라 화면과 신고 버튼은 첫 화면에서 바로 보인다.
- 위험 음성 안내는 반복 경고를 전제로 설계하지 않는다.
- 스크린리더가 읽을 수 있는 버튼 이름을 Figma annotation에 남긴다.

## 색상 사용

`DESIGN.md`의 토큰을 기준으로 한다.

| 용도 | 토큰 |
| --- | --- |
| 기본 텍스트 | `primary` |
| 보조 텍스트 | `secondary` |
| 주요 신고 CTA | `tertiary` |
| 정상/성공 상태 | `safety` |
| 위험/오류 상태 | `warning` |
| 권한/포커스 상태 | `focus` |
| 배경 | `neutral` |
| 패널 | `surface` |

한 화면을 초록/파랑/보라 계열 하나로만 만들지 않는다. 위험, 성공, 권한, 배경은 의미별로 분리한다.

## 관리자 화면 기준

관리자 화면을 Figma에 포함한다면 다음을 갖춘다.

필수 영역:

- 상태/위험 유형/소스/기간 필터
- 신고 목록
- 신고 이미지 미리보기
- 위치 품질과 검토 플래그
- 상태 변경 버튼: `new`, `reviewed`, `resolved`
- 빈 목록, 로딩, 오류 상태

관리자 화면은 업무형 화면이다. 큰 hero, 장식 카드, 제품 소개 섹션을 넣지 않는다.

## 미구현 기능 표현 금지

아래 기능은 실제 동작하는 것처럼 표현하지 않는다.

| 기능 | 허용 표현 |
| --- | --- |
| 실제 YOLO/ONNX 탐지 | 모델 연결 대기, 준비 중 |
| 서버 추론 결과 | 모델 대기 또는 서버 추론 준비 중 |
| 목적지 경로 안내 | 확장 후보 |
| 지자체 민원 자동 접수 | 추후 연동 |
| 모델 성능 대시보드 | 모델 학습 완료 후 |
| 지도 히트맵 | 관리자 확장 후보 |

## 구현자에게 넘길 산출물

Figma 화면이 정리되면 아래 정보를 함께 넘긴다.

```text
Figma link:
Main frames:
Mobile width:
Desktop/admin frames:
Component list:
State variants:
Changed text copy:
Open questions:
```

필수 첨부:

- 보행 보조 메인 화면
- 권한/오류 상태 화면
- 신고 전송 상태 화면
- 컴포넌트 variant 목록
- API 매핑 메모

## Figma AI에 줄 프롬프트

```text
WalkSafe Assist라는 시각장애인/저시력 보행자용 모바일 PWA UI를 만들어줘.
첫 화면은 랜딩 페이지가 아니라 보행 보조 카메라 화면이어야 해.
카메라 프리뷰, 탐지 bbox 오버레이, 현재 위험 상태, GPS 정확도, 방향, 음성 토글, 현재 위험 신고 버튼, 신고 전송 상태를 한 화면에 배치해줘.
DESIGN.md 기준으로 고대비, 모바일 우선, 낮은 인지부하, 48px 이상 터치 영역을 지켜줘.
색상은 위험, 성공, 권한, 배경을 의미별로 구분하고 장식용 gradient blob이나 hero 섹션은 쓰지 마.
모델은 아직 학습 중이므로 실제 탐지 완료처럼 과장하지 말고 Fake 탐지 또는 모델 연결 대기 상태로 표현해줘.
한국어 UI 문구를 사용해줘.
```

## 최종 검수 기준

Figma 화면을 구현으로 넘기기 전에 아래를 모두 확인한다.

1. 첫 화면이 보행 보조 화면이다.
2. 카메라, 위험, GPS, 음성, 신고 상태가 첫 viewport에 있다.
3. 모델 미연결 상태가 명확하다.
4. `POST /reports`에 필요한 metadata와 image 흐름이 보인다.
5. 권한 실패와 신고 실패 상태가 있다.
6. `DESIGN.md` 색상/타이포그래피/터치 영역 기준을 따른다.
7. 미구현 기능을 실제 기능처럼 표현하지 않는다.
