# 집 컴퓨터에서 점자블럭 모델 학습 준비 순서

작성 기준일: 2026-05-11

## 목표

AI Hub `보행 안전을 위한 도로 시설물 데이터`에서 점자블럭 데이터만 받아서 `damaged_tactile_block` 모델 학습을 시작한다.

## 0. 결론

먼저 받을 파일:

```text
TL8.zip
TS8.zip
```

여유가 있으면 추가로 받을 파일:

```text
TL9.zip
TS9.zip
VL1.zip
VL2.zip
VS1.zip
VS2.zip
```

받지 않아도 되는 파일:

```text
TL3.zip, TS3.zip
TL11.zip, TS11.zip
```

## 1. 디스크 용량 확인

`TS8.zip`, `TS9.zip`은 각각 100GB급 원천 이미지 파일이다.

최소 권장:

| 상황 | 필요 용량 |
| --- | ---: |
| `TL8 + TS8`만 처리 | 250GB 이상 여유 |
| `TL8 + TL9 + TS8 + TS9` 처리 | 500GB 이상 여유 |

용량이 부족하면 `TS8.zip`만 먼저 받는다.

## 2. AI Hub에서 다운로드

AI Hub 페이지:

https://www.aihub.or.kr/aihubdata/data/view.do?aihubDataSe=data&currMenu=115&dataSetSn=513&topMenu=100

다운로드 위치:

```text
01.데이터
  1.Training
    라벨링데이터
      TL8.zip
      TL9.zip
    원천데이터
      TS8.zip
      TS9.zip
  2.Validation
    라벨링데이터
      VL1.zip
      VL2.zip
    원천데이터
      VS1.zip
      VS2.zip
```

`TS8.zip`을 먼저 받고, 용량/시간이 괜찮으면 `TS9.zip`도 받는다. AI Hub 공식 validation set까지 쓰려면 `VS1.zip`, `VS2.zip`도 받는다.

## 3. 받은 파일 위치 유지

AI Hub 기본 다운로드 구조를 유지한다.

예상 경로:

```text
~/Downloads/119.보행 안전을 위한 도로 시설물 데이터/
  01.데이터/
    1.Training/
      라벨링데이터/
        TL8.zip
        TL9.zip
      원천데이터/
        TS8.zip
        TS9.zip
```

폴더 이름을 바꾸지 않는 것이 좋다. 이름이 깨져 보여도 괜찮다.

## 4. 프로젝트 폴더 준비

프로젝트 위치:

```text
/Users/ddobagi/Code/Hanium_Dreamup
```

터미널에서 이동:

```bash
cd /Users/ddobagi/Code/Hanium_Dreamup
```

가상환경이 없으면 만든다.

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements-model.txt
```

이미 `.venv`가 있으면 활성화만 한다.

```bash
source .venv/bin/activate
```

## 5. 다운로드 확인

다음 명령으로 파일이 있는지 확인한다.

```bash
find ~/Downloads -maxdepth 8 -type f \( -name 'TL8.zip' -o -name 'TL9.zip' -o -name 'TS8.zip' -o -name 'TS9.zip' \) -print
```

최소 성공 상태:

```text
TL8.zip
TS8.zip
```

권장 성공 상태:

```text
TL8.zip
TL9.zip
TS8.zip
TS9.zip
VL1.zip
VL2.zip
VS1.zip
VS2.zip
```

## 6. 나한테 알려줄 것

파일을 받은 뒤 아래처럼 말하면 된다.

```text
TS8까지 받았어
```

또는:

```text
TS8, TS9 둘 다 받았어
```

그다음 내가 할 일:

1. AI Hub 점자블럭 JSON 변환 스크립트 작성
2. `TL8/TL9` 라벨과 `TS8/TS9` 이미지를 매칭
3. `datasets/walksafe_kr_v1` YOLO 구조 생성
4. 데이터셋 검증
5. 1 epoch smoke training
6. 본 학습 실행

변환 스크립트:

```text
data_sources/scripts/build_walksafe_kr_tactile.py
```

## 7. 학습 흐름

변환 후 데이터 구조는 이렇게 만든다.

```text
datasets/walksafe_kr_v1/
  images/
    train/
    val/
    test/
  labels/
    train/
    val/
    test/
  data.yaml
```

AI Hub 점자블럭 매핑:

| AI Hub 데이터 | YOLO 처리 |
| --- | --- |
| 점자블럭 정상 | negative 이미지, 빈 `.txt` 라벨 |
| 점자블럭 불량부분 | `damaged_tactile_block` |
| 점자블럭 불량전체 | `damaged_tactile_block` |

## 8. 변환 명령

먼저 파일을 찾고 라벨/이미지 매칭만 확인한다.

```bash
python data_sources/scripts/build_walksafe_kr_tactile.py --dry-run
```

기본 변환은 정상 3,000장 + 불량 3,000장만 균형 샘플링한다. 전체를 다 복사하지 않아서 디스크를 덜 쓴다.

```bash
python data_sources/scripts/build_walksafe_kr_tactile.py
```

전체 점자블럭 데이터를 모두 쓰고 싶으면 다음처럼 실행한다.

```bash
python data_sources/scripts/build_walksafe_kr_tactile.py --max-positive 0 --max-negative 0
```

다운로드 폴더가 기본 `~/Downloads`가 아니면 직접 지정한다.

```bash
python data_sources/scripts/build_walksafe_kr_tactile.py --download-root "/path/to/download/root"
```

## 9. 학습 명령

변환이 끝나면 먼저 검증한다.

```bash
python model/validate_yolo_dataset.py
```

1 epoch만 먼저 실행한다.

```bash
python model/train_yolo.py --epochs 1 --batch 4 --name walksafe_kr_tactile_smoke
```

문제가 없으면 본 학습을 실행한다.

```bash
python model/train_yolo.py --epochs 50 --batch 8 --name walksafe_kr_tactile_v1
```

학습 결과 모델:

```text
runs/detect/walksafe_kr_tactile_v1/weights/best.pt
```

## 10. 삭제 기준

변환과 학습 검증 전에는 삭제하지 않는다.

보관 추천:

```text
TL8.zip
TL9.zip
datasets/walksafe_kr_v1/
runs/detect/.../weights/best.pt
```

삭제 가능:

```text
TS8.zip
TS9.zip
압축 해제 중간 폴더
```

단, `TS8.zip`, `TS9.zip`을 삭제하면 나중에 다시 변환할 때 재다운로드가 필요하다. 디스크 여유가 있으면 외장 SSD에 보관한다.

## 11. 주의사항

- AI Hub API 키나 토큰은 채팅에 붙여넣지 않는다.
- `전체 다운로드`는 누르지 않는다.
- `TS3.zip`, `TS11.zip`은 점자블럭이 아니므로 받지 않는다.
- 최종 성능 검증에는 직접 촬영한 한국 보행 시점 이미지/영상이 추가로 필요하다.
