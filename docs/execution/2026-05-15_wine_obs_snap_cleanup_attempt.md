# 2026-05-15 Wine / OBS / Snap Cleanup Attempt

## 사용자 승인 범위

사용자가 다음 정리를 승인했다.

- Wine 삭제
- OBS Studio 삭제
- Snap old disabled revisions 삭제

## 실제 수행 결과

### 수행 완료

사용자 소유 Wine prefix를 삭제했다.

```bash
rm -rf ~/.wine
```

결과:

```text
~/.wine removed
/ : 915G total, 854G used, 16G avail, 99% used
```

정리 전 `~/.wine`은 약 `1.8G`였다.

### 수행 실패: root 권한 필요

다음 root-level 삭제는 현재 세션에서 `sudo` 대화형 인증이 필요해 실패했다.

```bash
sudo -n apt-get purge -y obs-studio winehq-stable wine-stable wine-stable-amd64 wine-stable-i386
```

결과:

```text
sudo: interactive authentication is required
apt_purge_exit=1
```

Snap disabled revisions 삭제도 모두 같은 이유로 실패했다.

```text
sudo: interactive authentication is required
```

## 현재 남은 항목

아래 항목은 아직 설치/잔존 상태다.

- `obs-studio`
- `winehq-stable`
- `wine-stable`
- `wine-stable-amd64`
- `wine-stable-i386`
- Snap disabled revisions:
  - `canonical-livepatch` revision `393`
  - `chromium` revision `3416`
  - `core20` revision `2769`
  - `core22` revision `2339`
  - `core24` revision `1499`
  - `cups` revision `1170`
  - `discord` revision `278`
  - `firefox` revision `8247`
  - `firmware-updater` revision `224`
  - `prompting-client` revision `204`
  - `snap-store` revision `1338`
  - `snapd` revision `26382`
  - `snapd-desktop-integration` revision `357`
  - `thunderbird` revision `1093`

## 사용자가 로컬 터미널에서 실행할 명령

OBS와 Wine 패키지 제거:

```bash
sudo apt purge -y obs-studio winehq-stable wine-stable wine-stable-amd64 wine-stable-i386
```

Snap old disabled revisions 제거:

```bash
snap list --all | awk '/disabled|사용 불가/ {print $1, $3}' | while read -r snapname revision; do
  sudo snap remove "$snapname" --revision="$revision"
done
```

이후 의존성 자동 제거는 먼저 dry-run 확인 권장:

```bash
sudo apt autoremove --purge --dry-run
```

문제가 없어 보이면 실제 실행:

```bash
sudo apt autoremove --purge
```
