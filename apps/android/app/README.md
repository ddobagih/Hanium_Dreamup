# Android app module

Gradle `:app` 모듈이다. Android manifest, build variant, model asset, production Kotlin과 JVM test를 함께 담는다.

| 경로 | 역할 |
|---|---|
| `src/main/` | 공통 manifest/resource/model asset과 제품 코드 |
| `src/debug/` | local/dev metadata 및 frame debug uploader 구현 |
| `src/release/` | debug uploader를 비활성화하는 no-op 구현 |
| `src/test/` | Android framework 없이 실행하는 JVM policy/contract test |

패키지 진입점은 `src/main/java/kr/co/hanium/dreamup/walksafe/README.md`, 현행 구조·빌드·검증 한계는 [Android 사용자 앱 코드 지도](../../../docs/guides/code/android-user.md)를 본다. 상위 `../README.md`는 hash로 결속된 과거 snapshot이므로 현행 절차로 사용하지 않는다.
