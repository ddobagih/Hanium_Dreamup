# 한국 기준 모델/데이터 전략

작성 기준일: 2026-05-11

## 결정 사항

실제 모델 v1의 기본 학습/검증 대상은 `datasets/walksafe_kr_v1`로 둔다.

`datasets/walksafe_v1`에 변환해 둔 해외 공개 데이터는 모델 파이프라인 smoke test와 pretrain 후보로만 사용한다. 최종 성능 판단, 데모, 보고서 수치에는 한국 보행 환경에서 수집하거나 한국 인도보행 데이터셋에서 확보한 validation/test set만 사용한다.

## 이유

- 점자블록은 국가별 형태, 설치 기준, 색상 대비, 주변 보도 재질이 다를 수 있다.
- 프로젝트 사용자는 한국 보행 환경에서 이동하는 시각장애인이다.
- 해외 도로/인도 이미지만으로 학습하면 한국 점자블록, 보도블록, 킥보드 주차 방식, 공사 안내물, 불법 적치물에 대한 오탐/미탐 가능성이 높다.

## 우선 확보할 한국 데이터

1. 직접 촬영 데이터
   - 실제 PWA 사용 방식과 가장 가까운 스마트폰 세로 1인칭 보행 시점
   - 지하철역 출입구, 버스정류장, 횡단보도 접근부, 복지관 주변, 학교 주변, 상가 밀집 보도, 오래된 도심 보도
   - 낮, 흐림, 야간, 역광, 우천 후 젖은 노면, 흔들림을 포함

2. AI Hub 또는 SideGuide 계열 한국 인도보행 데이터
   - 한국 인도/보도/진입로와 이동취약계층 보행 장애물을 다루는 데이터셋을 우선 검토
   - 1순위 후보: AI Hub `보행 안전을 위한 도로 시설물 데이터`
   - 2순위 후보: AI Hub `인도(人道) 보행 영상`
   - 3순위 후보: AI Hub `1인칭 시점 보행영상`
   - 계정, 이용약관, 원본 반출 제한을 확인한 뒤 `data_sources/raw/` 아래에 보관
   - 프로젝트 저장소에는 원본 이미지를 커밋하지 않음

3. 보조 공개 데이터
   - 포트홀, 안전콘, 자전거 등은 해외 공개 데이터로 warm-start 가능
   - 다만 validation/test에는 포함하지 않음

## v1 클래스별 한국 라벨링 기준

| class id | class | 한국 기준 포함 대상 | 제외 대상 |
| --- | --- | --- | --- |
| 0 | `damaged_tactile_block` | 파손, 깨짐, 탈락, 단절, 매립/오염으로 촉지 곤란, 잘못된 방향 연결, 횡단보도/계단/출입구 주변의 명백한 부적정 설치 의심 사례 | 정상 점형/선형 점자블록, 단순 색 바램, 멀리 있어 판단 불가한 점자블록 |
| 1 | `parked_kickboard_bicycle` | 보행 동선 또는 점자블록을 막는 방치 전동 킥보드, 자전거 | 정상 주행 중인 이동수단, 차도 위 이동수단 |
| 2 | `construction_obstacle` | 안전콘, 임시 펜스, 공사 표지판, 자재, 불법 적치물, 이동식 간판 등 보행 동선을 막는 물체 | 고정 건물, 일반 가로수, 보행에 영향 없는 배경 물체 |
| 3 | `pothole` | 한국 보도/이면도로의 꺼짐, 패임, 깨진 포장, 발 걸림 위험이 큰 파손 | 단순 얼룩, 얕은 색상 차이, 빗물 고임만 있는 경우 |

정상 점자블록은 positive label을 붙이지 않되, 빈 label 파일을 가진 negative 이미지로 충분히 모은다. 그래야 모델이 모든 점자블록을 `damaged_tactile_block`으로 오탐하지 않는다.

## 수집 최소 기준

실제 baseline 학습 전 최소 목표:

| class | positive 최소 수량 | 추가 negative |
| --- | ---: | ---: |
| `damaged_tactile_block` | 200장 | 정상 한국 점자블록 200장 이상 |
| `parked_kickboard_bicycle` | 200장 | 정상 보행로/차도 이동수단 배경 |
| `construction_obstacle` | 200장 | 공사 없는 보행로 배경 |
| `pothole` | 200장 | 정상 보도블록/아스팔트 배경 |

이미지 수량보다 중요한 것은 장소 분리다. 같은 장소의 연속 프레임을 train/val/test에 섞지 않는다.

## split 원칙

- train: 70%
- val: 20%
- test: 10%
- 같은 장소, 같은 촬영일, 같은 객체의 연속 프레임은 한 split에만 둔다.
- validation/test는 한국 데이터만 사용한다.

## seesun 참고사항

참고 프로젝트: https://github.com/Ahnyezi/seesun

직접 재사용할 코드는 많지 않다. 구형 YOLOv3/v4, Android/Flask, TFLite 변환 흐름은 현재 PWA/ONNX Runtime Web 방향과 맞지 않는다.

반영할 점:

- 원천 데이터 라벨을 그대로 믿지 말고, 실제 사용자에게 필요한 객체만 다시 필터링한다.
- 멀리 있거나 너무 작은 객체는 정확도만 낮추고 경보 품질에 도움이 안 될 수 있으므로 라벨링 기준에서 제외한다.
- 연속 촬영 프레임을 무작위로 섞으면 평가 점수가 과하게 좋아질 수 있으므로 장소 단위로 split한다.
- annotation 좌표 변환 시 이미지 width/height 순서를 반드시 검증한다.
- 모델 경량화는 필요하지만, 현재는 YOLO11n + ONNX Runtime Web 후보로 검토한다.

## 참고 링크

- SideGuide 한국 인도보행 데이터셋 개요: https://www.testworks.co.kr/pdf/blackolive/Datasheet_Testworks_MKT_Sidewalk_ko.pdf
- AI Hub `보행 안전을 위한 도로 시설물 데이터`: https://www.aihub.or.kr/aihubdata/data/view.do?aihubDataSe=data&currMenu=115&dataSetSn=513&topMenu=100
- AI Hub `인도(人道) 보행 영상`: https://aihub.or.kr/aihubdata/data/view.do?dataSetSn=189
- AI Hub `1인칭 시점 보행영상`: https://www.aihub.or.kr/aihubdata/data/view.do?aihubDataSe=&currMenu=115&dataSetSn=159&topMenu=100
- AI Hub 데이터 이용/다운로드 정책 확인: https://www.aihub.or.kr/
- AI Hub 인공지능 데이터셋 구축 가이드북: https://www.aihub.or.kr/web-nas/aihub21/files/sample/intro/%EC%9D%B8%EA%B3%B5%EC%A7%80%EB%8A%A5%20%EB%8D%B0%EC%9D%B4%ED%84%B0%EC%85%8B%20%EA%B5%AC%EC%B6%95%20%EA%B0%80%EC%9D%B4%EB%93%9C%EB%B6%81_2%EC%87%84.pdf
- 시각장애인 편의시설 설치 매뉴얼: https://www.nld.go.kr/upload/contents02/sigak_searchi_menual.pdf
