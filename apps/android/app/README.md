# Android app module

Gradle `:app` 모듈이다. Android manifest, build variant, model asset, production Kotlin과 JVM test를 함께 담는다.

| 경로 | 역할 |
|---|---|
| `src/main/` | 공통 manifest/resource/model asset과 제품 코드 |
| `src/debug/` | local/dev metadata 및 frame debug uploader 구현 |
| `src/release/` | debug uploader를 비활성화하는 no-op 구현 |
| `src/test/` | Android framework 없이 실행하는 JVM policy/contract test |

제품 코드 지도는 `src/main/java/kr/co/hanium/dreamup/walksafe/README.md`, 빌드와 실기기 한계는 상위 `../README.md`를 본다.
