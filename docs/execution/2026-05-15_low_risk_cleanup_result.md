# 2026-05-15 Low-risk Disk Cleanup Result

## 실행 범위

사용자가 승인한 낮은 위험 후보만 정리했다.

- `~/.local/share/Trash`
- `~/.cache/pip`
- `~/.npm`
- `/tmp` 일부 후보: `/tmp/vello-jdk21`, `/tmp/runapp-remote-check`, `/tmp/node-compile-cache`
- unused Docker images: `eclipse-temurin:21-jdk`, `nvidia/cuda:12.0.1-base-ubuntu22.04`, `hello-world:latest`
- stopped hello-world container: `stoic_robinson`

AI Hub 원본 zip, `datasets/**`, `.venv`, `.venv-voice`, Hugging Face cache, Docker DB volumes는 삭제하지 않았다.

## 정리 전후

### Before

```text
/ : 915G total, 863G used, 5.9G avail, 100% used
~/.local/share/Trash : 2.4G
~/.cache/pip : 3.2G
~/.npm : 1.1G
/tmp : 603M
Docker images : 3.445GB, reclaimable 1.017GB
```

### After

```text
/ : 915G total, 855G used, 14G avail, 99% used
~/.local/share/Trash : 24K
/tmp : 5.6M
Docker images : 2.428GB, reclaimable 0B
```

`~/.cache/pip`와 `~/.npm`은 삭제되어 after `du -sh`에는 표시되지 않았다.

## 실행한 명령 요약

```bash
rm -rf ~/.local/share/Trash/files/* ~/.local/share/Trash/info/*
rm -rf ~/.cache/pip
rm -rf ~/.npm
rm -rf /tmp/vello-jdk21 /tmp/runapp-remote-check /tmp/node-compile-cache
docker container rm stoic_robinson
docker image rm eclipse-temurin:21-jdk nvidia/cuda:12.0.1-base-ubuntu22.04 hello-world:latest
```

## 결과

약 `8G` 정도 확보했다. 루트 파티션은 `100%`에서 `99%`로 내려갔고 가용 공간은 `5.9G`에서 `14G`가 됐다.
