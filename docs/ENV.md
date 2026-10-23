# 개발 환경 기록

개발 루틴(클라우드)이 매 실행마다 갱신한다. 팀 PC 환경은 아래 '팀 PC' 칸에 직접 적는다.

## 2026-10-22 (클라우드 개발 루틴 1회차)

| 항목 | 결과 |
| --- | --- |
| OS | Ubuntu 24.04, x86_64 |
| JDK | OpenJDK 17(apt `openjdk-17-jdk-headless` 설치) + 21(기본). Gradle 툴체인이 17을 자동 선택 |
| Gradle | wrapper 8.14.3 (`./gradlew`) |
| Kotlin | 2.2.20 (Maven Central) |
| Maven Central / Gradle 플러그인 포털 | 접근 가능. 간헐적 429(요청 과다) → `--max-workers=1` + 재시도로 해결 |
| dl.google.com (Google Maven·Android SDK) | **차단(프록시 403)** → AGP·AndroidX·litertlm-android·SDK 설치 불가 |
| Android 모듈(service, llm/litertlm, app, mock-app) | **미빌드**. 코드만 작성, `capstone.android=auto`가 SDK 없음을 감지해 설정에서 제외 |
| 순수 Kotlin 모듈 | 빌드·테스트 통과 (`./gradlew test`) |

### 클라우드에서 Android 모듈을 빌드하려면

환경 네트워크 허용 목록에 `dl.google.com`, `maven.google.com`을 추가한 뒤:

```bash
mkdir -p ~/android-sdk/cmdline-tools && cd /tmp
curl -fLO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*.zip -d ~/android-sdk/cmdline-tools
mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest
export ANDROID_HOME=~/android-sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

## 2026-10-23 (2회차)

| 항목 | 결과 |
| --- | --- |
| JDK | 새 컨테이너라 17이 없어 apt `openjdk-17-jdk-headless` 재설치 |
| Maven Central | 429 반복 → `--max-workers=1` + 30초 간격 재시도로 통과 |
| dl.google.com / maven.google.com | **여전히 차단(403)**. maven.google.com은 dl.google.com으로 리다이렉트되어 같이 막힘 |
| 로케일 | `LANG` 미설정(POSIX). 한글 클래스 파일명 주의(DECISIONS-IMPL I-25) |
| 결과 | 순수 Kotlin 모듈 테스트 81개 통과(gate 49 + tools 23 + agent-core 9), Android 모듈 미빌드 |

## 2026-10-23 (3회차)

| 항목 | 결과 |
| --- | --- |
| JDK | 새 컨테이너, apt `openjdk-17-jdk-headless`(apt-get update 뒤) 재설치 |
| Gradle wrapper | 8.14.3 배포본 새로 받음(services.gradle.org 접근 가능) |
| Maven Central | 첫 해석에서 429 1회 → `--max-workers=1` 재시도로 통과 |
| dl.google.com | 확인 생략(2회차까지 계속 403). Android 모듈 미빌드 |
| 로케일 | `LANG=C.UTF-8`로 실행 |
| 결과 | 순수 Kotlin 모듈 테스트 104개 통과(gate 49 + tools 25 + agent-core 30) |

## 팀 PC (10-26 기록)

| 항목 | A | B | C |
| --- | --- | --- | --- |
| OS / Android Studio | | | |
| JDK | | | |
| `./gradlew :app:assembleDebug` | | | |
| litertlm-android 0.18.0 Maven 게시·ResponseFormat | | | |

## 2026-10-23 (4회차)

| 항목 | 결과 |
| --- | --- |
| JDK | 새 컨테이너, apt `openjdk-17-jdk-headless` 재설치(apt-get update 뒤) |
| Gradle wrapper | 8.14.3 배포본 새로 받음 |
| Maven Central | 429 없이 통과(`--max-workers=1`), kotlinx-coroutines는 Android 모듈 전용이라 클라우드에서는 받지 않음 |
| dl.google.com | 확인 생략(이전 회차 계속 403). Android 모듈 미빌드 |
| 결과 | 순수 Kotlin 모듈 테스트 144개 통과(gate/core 46 + gate/exec 3 + agent-core 30 + tools 41 + llm/core 24) |

## 2026-10-23 (5회차)

| 항목 | 결과 |
| --- | --- |
| JDK | 새 컨테이너, apt `openjdk-17-jdk-headless` 재설치 |
| dl.google.com / maven.google.com | **여전히 차단(프록시 403)**. service/android·mock-app 미빌드 |
| 결과 | 순수 Kotlin 모듈 테스트 166개 통과(gate/core 46 + gate/exec 3 + agent-core 30 + tools 34 + snapshot 7 + llm/core 24 + service/core 22) |

## 2026-10-23 (6회차)

| 항목 | 결과 |
| --- | --- |
| JDK | 새 컨테이너, apt `openjdk-17-jdk-headless` 재설치 |
| dl.google.com | 확인 생략(이전 회차 계속 403). app·service/android·mock-app·llm/litertlm 미빌드 |
| 결과 | 순수 Kotlin 모듈 테스트 191개 통과(gate/core 46 + gate/exec 3 + agent-core 33 + tools 34 + snapshot 7 + llm/core 24 + service/core 22 + app-core 22) |

## 2026-10-23 (7회차)

| 항목 | 결과 |
| --- | --- |
| JDK | 새 컨테이너, apt `openjdk-17-jdk-headless` 재설치 |
| dl.google.com | 확인 생략(이전 회차 계속 403). app·service/android·mock-app·llm/litertlm 미빌드 |
| 결과 | 순수 Kotlin 모듈 테스트 200개 통과(+ tools mask-lint 6·SRT 보기 2, agent-core USER_AUTH 1). `mask-lint` 저장소 검사 통과 |

## 2026-10-23 (8회차)

| 항목 | 결과 |
| --- | --- |
| JDK | 새 컨테이너, apt `openjdk-17-jdk-headless` 재설치 |
| Maven Central | 첫 해석 429 2회 → `--max-workers=1` + 40초 간격 재시도 3번째에 통과 |
| dl.google.com | 확인 생략(이전 회차 계속 403). app·service/android·mock-app·llm/litertlm 미빌드 |
| 결과 | 순수 Kotlin 모듈 테스트 209개 통과(gate/core 46 + gate/exec 3 + agent-core 35 + tools 46 + snapshot 7 + llm/core 24 + service/core 26 + app-core 22) |

## 2026-10-23 (9회차)

| 항목 | 결과 |
| --- | --- |
| JDK | 새 컨테이너, apt `openjdk-17-jdk-headless` 재설치 |
| Gradle wrapper | 8.14.3 배포본 새로 받음, Maven Central 429 없음(`--max-workers=1`) |
| dl.google.com | 확인 생략(이전 회차 계속 403). app·service/android·mock-app·llm/litertlm 미빌드 |
| 결과 | 순수 Kotlin 모듈 테스트 211개 통과(gate/core 46 + gate/exec 3 + agent-core 35 + tools 46 + snapshot 7 + llm/core 24 + service/core 28 + app-core 22). `mask-lint` 저장소 검사 통과 |

## 2026-10-23 (10회차)

| 항목 | 결과 |
| --- | --- |
| JDK | 새 컨테이너, apt `openjdk-17-jdk-headless` 재설치 |
| Gradle wrapper | 8.14.3 배포본 새로 받음, Maven Central 429 없음(`--max-workers=1`) |
| dl.google.com | 여전히 차단(프록시 connect_rejected). app·service/android·mock-app·llm/litertlm 미빌드 |
| raw.githubusercontent.com | **접근 가능**. LiteRT-LM v0.18.0 태그 Kotlin 원본(Config·Engine·Conversation·Message·ResponseFormat)을 읽어 `LiteRtLmRunner` 이름을 대조했다(I-62). api.github.com은 403 |
| 결과 | 순수 Kotlin 모듈 테스트 226개 통과(gate/core 46 + gate/exec 3 + agent-core 35 + tools 46 + snapshot 7 + llm/core 27 + service/core 28 + app-core 34). `mask-lint` 저장소 검사 통과 |
