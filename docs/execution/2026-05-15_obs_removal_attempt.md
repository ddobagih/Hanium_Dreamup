# 2026-05-15 OBS Studio Removal Attempt

## 요청

사용자가 OBS Studio 삭제를 요청했다.

## 실행 전 확인

```text
obs-studio install ok installed, Installed-Size 23549 KiB
```

`apt-get -s remove obs-studio` 시뮬레이션 결과, 직접 제거 대상은 `obs-studio` 1개였다. 다만 `apt autoremove` 후보로 VLC/Qt/OBS plugin 관련 패키지가 다수 표시되므로, 자동 제거는 별도 확인이 필요하다.

## 실행 결과

```bash
sudo -n apt-get remove -y obs-studio
```

결과:

```text
sudo: interactive authentication is required
exit_status=1
```

현재 세션에서는 sudo 대화형 인증을 사용할 수 없어 OBS Studio를 실제 삭제하지 못했다.

## 현재 상태

```text
obs-studio install ok installed
```

## 사용자가 로컬 터미널에서 실행할 명령

OBS 앱만 제거:

```bash
sudo apt remove -y obs-studio
```

추가 의존성 정리는 먼저 dry-run 확인 권장:

```bash
sudo apt autoremove --dry-run
```

`vlc` 등 다른 앱을 쓰지 않는다고 확인한 뒤에만 실제 autoremove 실행:

```bash
sudo apt autoremove
```
