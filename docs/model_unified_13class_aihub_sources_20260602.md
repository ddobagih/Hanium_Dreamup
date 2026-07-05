# Unified 13-class AIHub dataset source plan

- 기준일: 2026-06-02 KST
- 목적: YOLO26n COCO 기반 단일 모델에 WalkSafe 커스텀 클래스를 붙일 때 어떤 AIHub 원천을 쓸지 고정한다.
- 이 문서는 다운로드/학습 실행 지시가 아니다. 대용량 다운로드, 압축 해제, 학습은 별도 승인 후 실행한다.

## 1. 최종 class order

```text
0 person
1 bicycle
2 car
3 motorcycle
4 bus
5 truck
6 traffic light
7 normal_tactile_block
8 damaged_tactile_block
9 crosswalk
10 curb_step
11 uneven_sidewalk
12 e_scooter_obstruction
```

`bench`는 unified 단일 모델에서 제외한다. 단, legacy COCO fallback의 `bench` allowlist는 과거 APK/테스트 호환 때문에 남길 수 있다.

## 2. AIHub 우선순위

| 우선순위 | AIHub dataset | dataSetSn | 쓸 class | 판단 |
|---|---|---:|---|---|
| P0 | 베리어프리존(장애물 없는 생활공간) 주행영상 | 186 | `normal_tactile_block`, `damaged_tactile_block`, `crosswalk`, `curb_step`, `uneven_sidewalk` | 최우선 신규 후보. 교통약자 관점, 점자블록 정상/파손, 평탄성, 보도블록 상태, 연석/계단/횡단보도 계열 라벨이 13-class 목표와 가장 많이 겹친다. |
| P0 | 인도보행 영상 | 189 | `crosswalk`, `uneven_sidewalk`, `e_scooter_obstruction` 후보, tactile 후보 | 보행자/인도 도메인이 강하다. scooter, crosswalk, braille guide block, sidewalk damaged 라벨이 있으나 obstruction 여부와 surface polygon→bbox 변환은 재라벨/정책 필요. |
| P0-local | 보행 안전을 위한 도로 시설물 데이터 | 513 | `normal_tactile_block`, `damaged_tactile_block`, `crosswalk`, `curb_step`, `uneven_sidewalk` | 현재 로컬에 일부 받아둔 데이터이며 repo builder가 직접 지원한다. 시설물 관리/차량 관점 편향은 있지만 빠르게 materialize 가능한 1차 구현 원천이다. |
| P1 | 개인형 이동장치 안전 데이터 | 614 | `e_scooter_obstruction` 후보 | 킥보드/PM 데이터량이 크다. 단 주행·위반 데이터라 방치 장애물 라벨은 아니므로 보도 위 정지/방치/경로차단만 재라벨해야 한다. |
| P1 | 배송로봇 비도로 운행 데이터 | 71604 | `crosswalk`, `curb_step`, `uneven_sidewalk`, `e_scooter_obstruction` 후보 | 비도로/인도/연석/횡단보도/킥보드 후보가 있어 보강용으로 쓸 수 있다. 로봇/가상 데이터 관점 차이는 검수 필요. |
| P2 | 지자체 도로 정비 AI 학습용 데이터 | 557 | `curb_step`, `uneven_sidewalk` 보강 후보 | 경계석/도로홀/균열은 있으나 차량 주행 도로정비 관점이다. 직접 매핑보다 hard negative 또는 보조 실험 후보. |

현재 결론: **학습용 다운로드 우선순위는 186 → 189 → 513 보존/보완 → 614 → 71604 → 557**이다. 513은 이미 작업한 builder가 있어 유지하고, 186/189는 다음 builder adapter를 추가해야 한다.

## 3. source별 mapping 메모

### AIHub 186: 베리어프리존

| Unified class | 후보 label | 사용 원칙 |
|---|---|---|
| `normal_tactile_block` | `braileblock_dot`, `braileblock_line` | 점형/선형은 단일 정상 점자블록 class로 병합 |
| `damaged_tactile_block` | `braileblock_dot_broken`, `braileblock_line_broken` | 파손 점자블록 positive |
| `crosswalk` | `planecrosswalk_normal`, `planecrosswalk_broken` | 정상/파손 모두 횡단보도 class로 병합 |
| `curb_step` | `outcurb_rectangle`, `outcurb_slide`, broken variants, `sidegap_out`, `stair_*` | 연석/턱/계단 범위가 넓으므로 샘플 검수 후 좁힘 |
| `uneven_sidewalk` | `flatness_D`, `flatness_E`, `block_state_broken`, `paved_state_broken` | bbox/마스크 변환 정책 필요. 정상 보도는 hard negative로 유지 |

### AIHub 189: 인도보행 영상

| Unified class | 후보 label | 사용 원칙 |
|---|---|---|
| `crosswalk` | `crosswalk` | surface polygon이면 bbox 변환 필요 |
| `uneven_sidewalk` | `sidewalk damaged`, `surface damaged` 계열 | 파손/노면 상태만 positive. 정상 sidewalk는 hard negative |
| `e_scooter_obstruction` | `scooter` 후보 | 그대로 쓰지 말고 보도 위 정지/방치/경로차단만 재라벨 |
| `normal_tactile_block` | `braille_guide_blocks normal` 후보 | 샘플 검수 후 사용 |
| `damaged_tactile_block` | `braille_guide_blocks damaged` 후보 | 샘플 검수 후 사용 |

### AIHub 513: 보행 안전을 위한 도로 시설물

AIHub 513은 현재 builder가 직접 지원하는 주 데이터셋이다.

| Unified class | AIHub 513 label mapping | 사용 원칙 |
|---|---|---|
| `normal_tactile_block` | `점자블럭` + `is_defect=정상` | 기존 tactile 정상 class |
| `damaged_tactile_block` | `점자블럭` + `is_defect=불량전체` | 자동 신고 대상. `불량부분`은 기본 13-class에서는 제외하고 필요 시 `tactile_damage_area` 비교안 |
| `crosswalk` | `횡단보도`, `고원식횡단보도` | 횡단보도 안내 후보. 단 보행자 폰 시점에서 bbox 품질 확인 필요 |
| `curb_step` | `연석`, `턱낮추기`, `보행자 계단` | 운영상 보도 턱/단차 후보로 묶는다. 범위가 넓으므로 샘플 검수로 좁혀야 함 |
| `uneven_sidewalk` | `보도블록`, `보도(시멘트 콘크리트)` + `is_defect in {불량전체, 불량부분}` | 정상 보도는 class로 넣지 않고 hard negative로 두는 쪽이 안전 |
| `e_scooter_obstruction` | 없음 | AIHub 513 builder로 자동 생성하지 않음 |

현재 로컬 label zip 빠른 스캔 결과:

| zip | 확인된 관련 label | 의미 |
|---|---|---|
| `TL8.zip`, `TL9.zip` | `점자블럭`, 일부 `경사로` | 기존 tactile 학습 원천 중심 |
| `VL1.zip` | `턱낮추기`, `점자블럭` | `curb_step` 검증 후보. source `VS1.zip` 필요 |
| `VL2.zip` | `보도블록`, `보도(시멘트 콘크리트)`, `연석`, `점자블럭` | `uneven_sidewalk`/`curb_step` 검증 후보. source `VS2.zip` 필요 |
| 현재 로컬 scan | `횡단보도` 미확인 | AIHub 513에는 존재하므로 해당 label/source partition을 추가로 받아야 함 |

주의: source zip이 0 byte거나 없으면 builder가 해당 label을 materialize하지 못한다. label zip만으로는 YOLO 학습 dataset을 만들 수 없다.

## 4. e-scooter obstruction 처리

`e_scooter_obstruction`은 “킥보드가 있다”가 아니라 “보행 경로를 막는 방치/정지 PM 장애물”이다.

권장 순서:

1. AIHub 189의 `scooter`와 AIHub 614의 킥보드/PM 후보를 모은다.
2. AIHub 71604의 `twowheeler`/`obstacle`은 보강 후보로만 쓴다.
3. PM 후보를 그대로 `e_scooter_obstruction`으로 쓰지 않는다.
4. 보도 위, 정지/방치, 보행 경로 차단 상태인 bbox만 수동 검수 또는 별도 규칙으로 `e_scooter_obstruction` 재라벨한다.
5. 움직이는 킥보드, 차도 주행, 멀리 있는 PM은 hard negative 또는 별도 class 후보로 분리한다.
6. Android field capture에서 실제 보행자 카메라 흔들림 positive를 반드시 보강한다.

## 5. 코드 반영 상태

- `data_sources/scripts/build_walksafe_unified_coco_tactile.py`
  - 기본 class order를 13-class로 유지한다.
  - AIHub 513 label/source zip pair를 동적으로 찾는다.
  - AIHub 513에서 tactile/crosswalk/curb/defective sidewalk class를 materialize한다.
  - `e_scooter_obstruction`은 별도 source 필요 경고를 남긴다.
- `data_sources/manifests/walksafe_unified_13class_aihub_sources_2026-06-02.json`
  - 이 문서의 source mapping을 machine-readable 형태로 둔다.
  - 186/189/614/71604/557는 계획에 들어갔지만 아직 builder adapter는 없다.
- `scripts/check_walksafe_unified_training_plan_20260601.py`
  - 13-class order, source plan, unified training/export 경로를 static check한다.
- `data_sources/scripts/inspect_aihub_unified_sources.py`
  - 다운로드된 AIHub zip을 압축 해제 없이 읽어 label zip 존재 여부, partial download, 후보 label hit를 요약한다.
  - source/image zip은 기본적으로 열지 않는다. 큰 zip member count가 필요할 때만 `--open-source-zips`를 쓴다.

## 6. 다운로드/보존 판단

- AIHub 513 401G 폴더는 **아직 삭제하지 않는 쪽**이 맞다. 지금 builder가 직접 읽을 수 있고 tactile/curb/uneven sidewalk 후보를 이미 확인했다.
- 다만 현재 로컬의 `TS9.zip`, `VS1.zip`, `VS2.zip` source zip은 0 byte로 보여 실제 materialize에 쓸 수 없다. 해당 partition을 쓸 거면 재다운로드가 필요하다.
- 신규 다운로드는 186과 189를 먼저 판단한다. 둘 다 13-class 목표와 도메인 적합도가 513보다 좋다.
- `e_scooter_obstruction` 성능은 189/614 후보 + 현장 직접 촬영/수동 라벨이 핵심이다.
- 대용량 zip 삭제/재다운로드/학습은 비용과 시간 영향이 있으므로 별도 확인 없이 실행하지 않는다.

## 7. AIHub 공식 페이지 확인 링크

- AIHub 186: <https://aihub.or.kr/aihubdata/data/view.do?aihubDataSe=data&currMenu=115&dataSetSn=186&topMenu=>
- AIHub 189: <https://www.aihub.or.kr/aihubdata/data/view.do?aihubDataSe=realm&currMenu=115&dataSetSn=189&topMenu=100>
- AIHub 513: <https://www.aihub.or.kr/aihubdata/data/view.do?aihubDataSe=data&currMenu=11&dataSetSn=513&topMenu=>
- AIHub 614: <https://www.aihub.or.kr/aihubdata/data/view.do?aihubDataSe=data&currMenu=115&dataSetSn=614&topMenu=100>
- AIHub 71604: <https://aihub.or.kr/aihubdata/data/view.do?aihubDataSe=data&currMenu=120&dataSetSn=71604&topMenu=>
- AIHub 557: <https://aihub.or.kr/aihubdata/data/view.do?dataSetSn=557>
