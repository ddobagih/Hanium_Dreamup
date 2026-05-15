# 2026-05-15 Installed Program Usage Candidate Scan

## 기준과 한계

- 기준일: 2026-05-15 KST
- 기준: 최근 7일 안에 사용 흔적이 명확하지 않은, 사용자가 별도로 설치한 것으로 보이는 프로그램 후보.
- 기본 Ubuntu/GNOME/snap base/system 패키지는 제외했다.
- Linux는 앱 실행 이력을 완전하게 저장하지 않으므로 아래는 확정이 아니라 후보다.
- 사용 근거는 `apt history`, `apt-mark showmanual`, shell history, GNOME Shell `application_state`, 주요 홈 디렉터리 수정 시각, package size를 조합했다.

## 후보 요약

| 후보 | 근거 | 대략 크기/영향 |
| --- | --- | --- |
| Wine / `~/.wine` | `~/.wine` 최근 수정이 2026-03-18에 멈춰 있음. shell history에 `wine`/`winecfg`는 있으나 날짜 확인 불가 | `/opt/wine-stable` `1.5G`, `~/.wine` `1.8G` |
| OBS Studio | GNOME app state에 없음, shell history `obs` 0회 | package 약 `23M` + plugins |
| wf-recorder | shell history `wf-recorder` 0회 | 작음 |
| htop | shell history `htop` 0회 | 작음 |
| nvme-cli | shell history `nvme` 0회 | 작음 |
| gnome-sushi | GNOME app state에 없음. Nautilus quick preview는 간접 실행이라 확정 불가 | 작음 |
| nvidia-cuda-toolkit | `nvcc` shell history 1회만 확인, 최근 사용일은 확정 불가. PyTorch CUDA와 별개일 수 있음 | package 기준 약 `196M` |
| nvidia-container-toolkit | `nvidia-ctk`, `nvidia-container-cli` shell history 0회. NVIDIA Docker를 쓰면 보존 | 작음 |
| cmake | shell history `cmake` 0회. 개발 빌드에 필요할 수 있음 | package 약 `43M` |
| Unity Hub / Unity Editor | GNOME app state에 `unityhub.desktop` 없음. `~/Unity` 최근 파일은 2026-05-10 설치 시각 중심 | `/opt/unityhub` `442M`, `~/Unity` `9.1G` |
| VS Code | GNOME app state에 `code.desktop` 없음, shell history `code` 1회. 실제 GUI 사용 여부 확정 불가 | `/usr/share/code` 약 `508M` |

## 최근 사용 근거가 있는 항목

아래는 삭제 후보에서 제외하는 편이 안전하다.

| 항목 | 사용 근거 |
| --- | --- |
| Docker | 오늘 정리/검증에서 사용됨. `docker` shell history 있음 |
| GitHub CLI `gh` | 오늘/최근 사용 근거 있음 |
| Node.js/npm/npx | PWA 검증에서 사용됨 |
| Python/pip/venv | backend/voice/model 검증에서 사용됨 |
| tmux, socat, adb, emulator, sdkmanager | shell history 사용 흔적 있음 |
| MySQL server | `systemctl` 기준 active |
| xpad | GNOME app state last-seen `2026-05-11` |
| Discord | GNOME app state last-seen `2026-05-14` |
| Chromium | GNOME app state last-seen `2026-05-14` |
| LibreOffice Writer | GNOME app state last-seen `2026-05-14` |
| Android Studio | GNOME app state last-seen `2026-05-08 13:17`, `~/Android/Sdk/.knownPackages` 수정 `2026-05-08` |
| VMware | GNOME app state last-seen `2026-05-11`, `~/vmware` 최근 수정 `2026-05-11` |

## 추가 정리 후보

- Snap disabled old revisions가 약 `1.15G` 있다. 앱 삭제가 아니라 이전 revision 제거 후보라 비교적 안전하지만, 이번 요청에서 삭제하지 않았다.
- `~/Unity/AssetStoreCache`는 `2.1G`, `~/Unity/Hub/Editor`는 `7.1G`다. Unity를 안 쓰면 큰 후보지만 명시 승인 전 삭제하지 않는다.
- `~/.wine`은 `1.8G`다. Wine을 안 쓰면 큰 후보지만 명시 승인 전 삭제하지 않는다.
