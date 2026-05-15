# 2026-05-15 Wine / OBS / Snap Cleanup Result

## 사용자 승인 범위

사용자가 다음 삭제를 승인했다.

- OBS Studio 삭제
- Wine 삭제
- Snap old disabled revisions 삭제

비밀번호 값은 문서에 기록하지 않는다.

## 실행 결과

### OBS/Wine 패키지 삭제 완료

실행:

```bash
sudo apt-get purge -y obs-studio winehq-stable wine-stable wine-stable-amd64 wine-stable-i386
```

결과 요약:

- `obs-studio` 제거됨
- `winehq-stable` 제거됨
- `wine-stable` 제거됨
- `wine-stable-amd64` 제거됨
- `wine-stable-i386:i386` 제거됨
- apt 출력 기준 추가 확보 예상: `1582 MB`

검증:

```bash
dpkg-query -W obs-studio winehq-stable wine-stable wine-stable-amd64 wine-stable-i386
```

결과: 해당 패키지 조회 결과 없음.

### Wine 사용자 prefix 삭제 완료

이전 단계에서 사용자 소유 Wine prefix도 삭제했다.

```bash
rm -rf ~/.wine
```

검증:

```text
/home/ddobagi/.wine missing
/opt/wine-stable missing
```

### Snap old disabled revisions 삭제 완료

실행 대상:

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

검증:

```bash
snap list --all | awk 'NR==1 || /사용 불가|disabled/'
```

결과: header만 출력되어 disabled revision이 남아 있지 않음.

## 용량 변화

| 시점 | `/` 가용 공간 |
| --- | ---: |
| Wine 사용자 prefix 삭제 후, root-level 삭제 전 | `16G` |
| OBS/Wine package + Snap old revisions 삭제 후 | `18G` |

최종:

```text
/ : 915G total, 851G used, 18G avail, 99% used
```

## 실행하지 않은 것

`apt autoremove --purge`는 실행하지 않았다. dry-run 결과 자동 제거 후보가 `243`개로 많고, `vlc`, Qt, OBS plugin, Wine i386 dependency 등이 포함되어 있어 사용자 확인이 필요하다.

확인 명령:

```bash
sudo apt autoremove --purge --dry-run
```

실제 삭제는 후보 목록을 보고 괜찮을 때만 실행한다.
