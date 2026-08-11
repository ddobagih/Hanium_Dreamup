# 저장소 전수 카탈로그

이 디렉터리의 JSON 3개는 Git tracked(삭제 제외), nonignored untracked, 그리고 세 JSON 자체의 가상 경로를 입력으로 삼는 결정적 산출물입니다.

- `repository-paths.json`: 모든 입력 경로의 단일 책임 분류와 이동 판정. checkpoint의 managed·canonical·Goal-bound·protected 경로는 항상 `KEEP_AT_PATH`입니다.
- `scripts.json`: `scripts/`, `data_sources/scripts/` 아래 모든 Python·shell 스크립트의 lifecycle과 가능한 최강 부작용입니다.
- `tests.json`: Python `test_*.py`, Android `*Test.kt`/`*Test.java`, Gateway/Web `*.test.ts`의 framework·area·lifecycle입니다. helper와 `conftest.py`는 저장소 카탈로그에만 남습니다.

생성은 `python3 -B scripts/generate_repository_catalogs.py`, 검증은 `python3 -B scripts/generate_repository_catalogs.py --check`로 수행합니다. `--check`는 파일이나 디렉터리를 만들거나 고치지 않고 byte-exact 일치만 검사합니다. `source_set_sha256`은 정렬한 상대경로와 NUL 구분자만 해시하며 절대경로, 시각, mtime은 기록하지 않습니다.
