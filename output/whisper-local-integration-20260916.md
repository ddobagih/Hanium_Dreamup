# Whisper 로컬 연동 — 2026-09-16

작업 폴더: `merge-review-team-20260914`

## 적용
- Mac Python 3.12 가상환경 `.venv`에 faster-whisper 1.2.1 및 실행 의존성 설치.
- 모델: `Systran/faster-whisper-medium`, CPU/int8, 한국어 고정.
- revision: `08e178d48790749d25932bbc082711ddcfdfbc4f`. `weights/voice/stt-manifest.json`으로 파일 해시 검증.
- ffmpeg/ffprobe 설치: Android AAC/M4A 파일의 길이와 오디오 스트림 검증에 필요.
- 비밀 설정은 `.env.voice-local`에만 저장(권한 0600, Git 제외). 모델과 가상환경도 Git 제외.
- 일반 명령: 앱 녹음 → 기존 인증된 `/api/speech/stt` → 게이트웨이 → Mac Whisper → 앱의 기존 명령/확인 규칙.
- 말이 끝난 뒤 약 1.4초 침묵이면 전송. 무음은 7초, 전체 녹음은 최대 12초. 야외 잡음에서는 이 종료 기준을 추가 검증해야 함.
- 호출어는 Vosk 유지. 홈/보행 호출어 뒤의 새 명령 녹음은 Whisper로 전달. 호출어와 명령을 한 번에 말한 경우에는 안내 뒤 명령을 다시 말하는 방식.
- 재개/다기기 전환의 별도 확인 입력은 기존 단말 인식 유지.
- TTS 출력은 기존 앱 경로 유지. Qwen 서버 TTS 모델은 설치/활성화하지 않음.
- 화면 전환·백그라운드·로그인 변경·위험 경고 시 기존 취소/응답 무효화 규칙 유지.

## 현재 테스트 환경 실행
이 프로젝트 폴더에서 다음 명령으로 음성 서버를 다시 시작할 수 있음(이미 실행 중이면 중복 실행하지 않음).

```sh
.venv/bin/python scripts/run_local_voice.py
```

서버 바인딩은 `127.0.0.1:9001`. Mac, Docker의 backend/gateway, 음성 서버, 기존 Cloudflare 터널을 모두 켜 두어야 함.
외부 주소: https://sec-recommendation-employees-searched.trycloudflare.com
Quick Tunnel 재실행으로 주소가 바뀌면 앱의 서버 주소도 다시 맞춰야 함.

게이트웨이 `walksafe-gateway`에는 음성 서비스 활성화와 전용 인증 토큰을 적용하고, 컨테이너 내부 9001을 Mac의 9001로 전달함.
기존 로그인 암호화 키와 세션 저장소는 보존. 변경 전 컨테이너 `walksafe-gateway-before-whisper-20260916` 및 로컬 복구 이미지 `walksafe-gateway-local:before-whisper-20260916`은 외부에 배포하지 말 것(로컬 상태 포함).

Android 빌드 시 `-Pwalksafe.serverSttEnabled=true` 필요. 이 속성을 생략하면 기존 단말 명령 인식 모드.
검증한 Python 의존성은 `voice/requirements-local-stt.txt`에 기록. 새 장비는 의존성 외에 동일 revision 모델/해시 manifest와 별도 인증 설정이 필요함.

## 검증
- STT worker 실제 모델 로딩 성공.
- 한국어 합성 음성 “서울역으로 안내해줘” WAV 및 Android와 같은 AAC 형식: HTTP 200, “서울역으로 안내해줘.”, 목적지 서울역 확인. 예열 후 Mac 추론 약 3~4초(네트워크·녹음 시간 제외).
- Docker 게이트웨이 → Mac 음성 서버 접근 성공, 외부 Cloudflare health HTTP 200.
- 기기 SM_S948N에 설치. 사용자 실제 발화 성공 확인.
- 기기 로그 `SERVER_STT_RECORDING backend=WHISPER` → `SERVER_STT_RESULT ... allowed=true` → `COMMAND_SELECTED matched=true` → `COMMAND_EXECUTION_REQUESTED` 및 목적지 후보 대화 열림 확인.
- 품질 미달 발화 `allowed=false`는 실행하지 않고 다시 요청하는 동작도 관측. 모든 야외 발화가 정확하다는 의미는 아님.
- Python 관련 시험 28개 통과, 기존 Linux abstract Unix socket 기반 배포 프로세스 잠금 시험 3개는 macOS에서 실패. 해당 서버 코드는 변경하지 않음. 현재는 로컬 development 단일 프로세스로 실행하며 운영 배포 검증 완료를 뜻하지 않음.
- `/ready` 전체 상태는 TTS 모델 미준비로 503이 정상이며 STT 검사만 ready=true. 전체 음성 서비스 준비 완료로 해석하면 안 됨.
- 최종 Android 관련 테스트 54개 통과, APK 빌드 성공, `git diff --check` 통과.
