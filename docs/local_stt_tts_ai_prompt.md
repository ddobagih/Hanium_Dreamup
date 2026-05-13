# 로컬 STT/TTS 프로토타입 작업 프롬프트

아래 내용을 AI 코딩 에이전트에게 그대로 전달한다.

```text
프로젝트: Hanium_Dreamup / WalkSafe Assist

목표:
이미지 추론 모델 학습은 별도로 진행 중이므로 건드리지 말고, 그동안 로컬 GPU 기반 STT/TTS 프로토타입을 구축한다.

환경:
- GPU: RTX 5070 Ti 16GB
- CUDA 설치됨
- 외부 유료 API 사용 금지
- OpenAI Whisper API, Qwen API 같은 클라우드 API 사용 금지
- pretrained 모델 로컬 추론부터 진행
- 파인튜닝은 지금 하지 않는다. 테스트 결과가 나쁠 때만 후속 단계로 판단한다.

해야 할 일:

1. GPU 환경 확인
   - `nvidia-smi`
   - Python 버전 확인
   - CUDA/PyTorch GPU 사용 가능 여부 확인
   - 결과를 문서에 기록

2. STT 로컬 추론 테스트
   - 우선 `faster-whisper` 사용
   - 시작 모델은 `Whisper medium`
   - `medium`이 느리거나 부족하면 `large-v3-turbo`도 비교 후보로 기록
   - 테스트 문장:
     - 신고해
     - 현재 위험 신고해
     - 음성 꺼
     - 음성 켜
     - 다시 말해줘
     - 목적지 서울역으로 설정해
     - 길 안내 시작해
   - 목표는 원문 100% 일치가 아니라 intent 인식이다.
   - intent 예:
     - `create_report`
     - `voice_on`
     - `voice_off`
     - `repeat_last`
     - `set_destination`
     - `start_navigation`

3. TTS 로컬 추론 테스트
   - `Qwen3-TTS` 사용
   - 시작 모델은 `Qwen3-TTS 0.6B`
   - 가능하면 `Qwen3-TTS 1.7B`도 비교
   - 테스트 문장:
     - 전방에 점자블록 파손이 있습니다.
     - 오른쪽에 방치된 킥보드가 있습니다.
     - 공사 장애물이 감지되었습니다. 속도를 줄이세요.
     - 노면 파임이 감지되었습니다. 전방을 확인하세요.
     - 신고가 저장되었습니다.
     - 목적지를 다시 말씀해 주세요.
   - 생성된 음성 파일을 저장하고, 생성 시간과 음질을 기록한다.
   - 위험 경고문은 실시간 생성보다 캐싱 가능한 구조로 설계한다.

4. FastAPI 음성 추론 서버 초안 만들기
   - 이미지 추론 서버와 충돌하지 않게 음성 서버로 분리한다.
   - 추천 엔드포인트:
     - `GET /health`
     - `POST /speech/stt`
       - multipart audio 파일 입력
       - `transcript`, `intent`, `confidence` 또는 `score` 반환
     - `POST /speech/tts`
       - text 입력
       - wav 또는 mp3 파일 반환
     - `POST /speech/intent`
       - transcript 입력
       - intent JSON 반환
   - 서버 포트는 `9001` 권장

5. 테스트 스크립트 작성
   - `scripts/test_stt.py`
   - `scripts/test_tts.py`
   - 샘플 오디오 폴더와 출력 음성 폴더를 분리
   - 결과를 표로 기록:
     - 모델명
     - 입력
     - 출력
     - intent
     - 처리 시간
     - 성공/실패
     - 비고

6. 문서 작성
   - `docs/local_voice_server_plan.md` 생성
   - 포함 내용:
     - 설치 방법
     - 실행 명령
     - 사용 모델
     - GPU 메모리 사용량
     - STT 테스트 결과
     - TTS 테스트 결과
     - 파인튜닝 필요 여부 판단
     - PWA/백엔드와 연결할 다음 단계

중요한 판단 기준:
- STT가 명령어 intent를 90% 이상 맞추면 파인튜닝하지 않는다.
- TTS 한국어 발음이 시연 가능한 수준이면 파인튜닝하지 않는다.
- 파인튜닝은 지금 구현하지 말고, 필요 여부만 판단한다.
- 위험 경고 TTS는 매번 생성하지 말고, 자주 쓰는 문장은 미리 생성해서 캐싱하는 방향으로 설계한다.
- 기존 YOLO 이미지 학습 코드, 데이터셋, backend `/detect` placeholder는 수정하지 않는다.
- 기존 프로젝트 구조를 최대한 유지하고, 음성 관련 파일만 추가한다.

최종 산출물:
1. 로컬 STT/TTS 서버 코드
2. STT 테스트 스크립트
3. TTS 테스트 스크립트
4. 테스트 결과 문서
5. "파인튜닝 필요/불필요" 결론
```

## 짧은 버전

```text
RTX 5070 Ti CUDA 환경에서 Hanium_Dreamup 프로젝트용 로컬 STT/TTS 프로토타입을 만들어줘. 유료 API는 쓰지 말고 pretrained 모델 로컬 추론만 사용해. STT는 faster-whisper medium부터, TTS는 Qwen3-TTS 0.6B부터 테스트하고 가능하면 large-v3-turbo와 Qwen3-TTS 1.7B도 비교해줘. 파인튜닝은 지금 하지 말고, 명령어 인식률/지연시간/한국어 TTS 품질을 테스트해서 파인튜닝 필요 여부만 판단해줘. 이미지 YOLO 학습 쪽은 건드리지 말고 음성 서버, 테스트 스크립트, 결과 문서만 추가해줘.
```
