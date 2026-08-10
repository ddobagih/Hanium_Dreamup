# 한국 기준 데이터 후보 매니페스트

작성 기준일: 2026-05-11

## 우선순위

| 우선순위 | 데이터 | 용도 | 상태 |
| --- | --- | --- | --- |
| 1 | 직접 촬영 한국 보행 데이터 | 최종 train/val/test 핵심 데이터 | 수집 필요 |
| 2 | AI Hub `보행 안전을 위한 도로 시설물 데이터` | 점자블록 양호/불량, 보도/보도블록 파손, 도로 시설물 상태 보강 | 다운로드 승인/API 필요 |
| 3 | AI Hub `인도(人道) 보행 영상` | 한국 보도/인도 객체, 점자블록/노면, 장애물 보강 | 다운로드 승인/API 필요 |
| 4 | AI Hub `1인칭 시점 보행영상` | 스마트폰/PWA 사용 시점과 가까운 보행 장애물 보강 | 다운로드 승인/API 필요 |
| 5 | SideGuide 자료 | AI Hub 인도보행 데이터 구조와 수집 기준 참고 | 설명자료 확인 완료 |
| 6 | 지자체/공공기관 보행환경 점검 사진 | 파손 점자블록, 보도 파손 사례 보강 | 공개 여부 확인 필요 |
| 7 | 기존 해외 공개 데이터 | smoke test, pretrain 후보 | 일부 확보 완료 |

## 반드시 확보해야 하는 한국 이미지

| class | 필요한 한국 데이터 |
| --- | --- |
| `damaged_tactile_block` | 파손, 깨짐, 탈락, 단절, 매립/오염, 잘못 연결된 한국 점자블록과 정상 점자블록 negative |
| `parked_kickboard_bicycle` | 한국 공유 킥보드/자전거가 점자블록 또는 보행 동선을 막는 장면 |
| `construction_obstacle` | 한국 보도 공사 안전콘, 임시 펜스, 공사 표지판, 자재, 이동식 간판 |
| `pothole` | 한국 보도블록 파손, 이면도로 꺼짐, 발 걸림 위험이 큰 노면 파손 |

## v4 class 1~3 한국 데이터 확보 계획

v2/v3 점자블록 성능은 class `0` baseline으로만 사용한다. class `1..3`은 한국 GT가 확보되기 전까지 4-class 서비스 metric으로 보고하지 않는다.

| class | 1차 positive 목표 | 1차 negative 목표 | 우선 수집 경로 | 제외 기준 |
| --- | ---: | ---: | --- | --- |
| `parked_kickboard_bicycle` | 500장 | 300장 | 직접 촬영, AI Hub 인도/1인칭 보행 영상, 지자체 보행환경 사진 | 주행 중 자전거/킥보드, 보행 동선과 무관한 원거리 객체, 번호판/얼굴 비식별 불가 이미지 |
| `construction_obstacle` | 500장 | 300장 | 직접 촬영, AI Hub 인도 보행 영상, 공사 안전 점검 공개 사진 | 차량도로 중심 공사, 안전콘 단독 원거리 장면, 보행자 통행 위험과 무관한 배경 객체 |
| `pothole` | 500장 | 300장 | 직접 촬영, AI Hub 513 보도/보도블록, 지자체 보도 파손 민원 사진 | 차량도로 대형 포트홀만 있는 이미지, 물웅덩이/그림자 오인 장면, 발 걸림 위험이 낮은 미세 흠집 |
| 공통 hard negative | 600장 | - | 정상 보도, 정상 점자블록, 타일 패턴, 배수구, 맨홀, 그림자 | 같은 장소 연속 프레임 split 혼합, 개인정보/민감 위치 비식별 미완료 |

분할 기준은 장소/촬영 시퀀스 단위로 고정한다. 동일 장소의 연속 프레임은 train/val/test에 섞지 않는다.

## 2026-05-19 보유 파일 확인

현재 repo와 `/home/ddobagi/Downloads` 검색 기준으로 AI Hub 159 `1인칭 시점 보행영상`의 `Average_stature/out` zip과 직접 촬영 후보 파일은 확인되지 않았다.

디스크가 `/` 기준 99% 사용 중이므로 AI Hub 159는 전체 다운로드를 하지 않는다. 승인/공간 확보 후 1차로 `School` label+image 소형 subset을 받고, 그 다음 `Building_area`, `Bridge` 순서로 확장한다. 직접 촬영 데이터는 Android 목걸이 field smoke 이후 파일명, 장소 단위 split key, 비식별 상태를 별도 manifest에 기록한 뒤 v3/v4 후보로 편입한다.

## AI Hub 513 점자블럭 파일 매핑

로컬 다운로드한 `TL*.zip` 전체를 JSON 라벨 기준으로 스캔한 결과, 점자블럭은 다음 파일에 들어 있다.

| 구분 | 라벨 파일 | 원천 이미지 파일 | 내용 |
| --- | --- | --- | --- |
| Training | `TL8.zip` | `TS8.zip` | `09. 점자블럭/0. 양호`, `09. 점자블럭/1. 불량` 일부 |
| Training | `TL9.zip` | `TS9.zip` | `09. 점자블럭/1. 불량` 일부 |
| Validation | `VL1.zip` | `VS1.zip` | `09. 점자블럭/0. 양호`, 점자블럭 일부 |
| Validation | `VL2.zip` | `VS2.zip` | `09. 점자블럭/0. 양호`, `09. 점자블럭/1. 불량` 일부 |

확인 결과:

- `TL8.zip`: `점자블럭` 정상 11,560개, 불량 5,838개
- `TL9.zip`: `점자블럭` 불량 6,077개
- `VL1.zip`: `09. 점자블럭` 1,038개
- `VL2.zip`: `09. 점자블럭` 2,082개
- `TL3.zip`: 점자블럭 아님. Bollard/시선유도봉 데이터
- `TL11.zip`: 점자블럭 아님. 보도/보도블록 데이터

따라서 점자블럭 학습용 원천 이미지는 `TS8.zip`, `TS9.zip`이고, AI Hub 공식 validation까지 쓰려면 `VS1.zip`, `VS2.zip`을 추가로 받는다.

## 검토할 링크

- AI Hub `보행 안전을 위한 도로 시설물 데이터`: https://www.aihub.or.kr/aihubdata/data/view.do?aihubDataSe=data&currMenu=115&dataSetSn=513&topMenu=100
- SideGuide 한국 인도보행 데이터셋 개요: https://www.testworks.co.kr/pdf/blackolive/Datasheet_Testworks_MKT_Sidewalk_ko.pdf
- AI Hub `인도(人道) 보행 영상`: https://aihub.or.kr/aihubdata/data/view.do?dataSetSn=189
- AI Hub `1인칭 시점 보행영상`: https://www.aihub.or.kr/aihubdata/data/view.do?aihubDataSe=&currMenu=115&dataSetSn=159&topMenu=100
- AI Hub: https://www.aihub.or.kr/
- seesun 참고 프로젝트: https://github.com/Ahnyezi/seesun

## 수집 후 처리 원칙

- 원본 이미지는 `data_sources/raw/`에 보관하고 Git에 커밋하지 않는다.
- 프로젝트 기본 데이터셋은 `datasets/walksafe_kr_v1`로 변환한다.
- validation/test에는 한국 데이터만 넣는다.
- 같은 장소의 연속 프레임은 split을 섞지 않는다.
- 얼굴, 차량번호, 민감 위치 정보는 비식별 처리한다.
