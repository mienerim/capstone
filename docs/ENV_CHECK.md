# 개발 환경 점검 (2026-10-22 UTC)

| 항목 | 결과 |
|---|---|
| 저장소 | /home/user/capstone, 브랜치 main, 커밋 b10412f Initial commit, 파일: LICENSE, README.md |
| OS/자원 | Ubuntu 24.04.5, x86_64, 4 CPU, 15GiB RAM, 디스크 여유 약 31G, root + sudo 가능 |
| JDK | OpenJDK 21.0.12 (/usr/lib/jvm/java-21-openjdk-amd64). JDK 17은 apt 후보 확인(설치 가능) |
| Gradle | /opt/gradle (8.14.3), Maven 3.9.11 |
| kotlin/kotlinc/sdkmanager/adb | 없음 |
| ANDROID_HOME / ANDROID_SDK_ROOT | 미설정 |
| repo.maven.apache.org | 200 |
| plugins.gradle.org/m2 | 200 |
| services.gradle.org/distributions | 200 |
| dl.google.com (Google Maven, repository2-3.xml, commandlinetools zip) | 실패: CONNECT tunnel failed, response 403 (프록시 차단) |
| github.com | CONNECT 200 후 HTTP 400 (프록시 경유 루트 접근) |
| huggingface.co | 403 (차단) |
| apt-get update | 성공 (sudo 가능) |

## 결론
- 순수 Kotlin/JVM 테스트: 가능 (Maven Central, Gradle 플러그인 포털, Gradle 배포 접근 가능).
- Android(AGP) 빌드: 불가. dl.google.com 이 차단되어 Android SDK 및 Google Maven(AGP/AndroidX) 을 받을 수 없음.
  환경의 네트워크 허용 목록에 dl.google.com, maven.google.com 추가가 필요.

## 제안 setup script
```
apt-get update && apt-get install -y openjdk-17-jdk-headless unzip
# dl.google.com 허용 후:
mkdir -p $HOME/android-sdk/cmdline-tools && cd /tmp
curl -fLO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*.zip -d $HOME/android-sdk/cmdline-tools && mv $HOME/android-sdk/cmdline-tools/cmdline-tools $HOME/android-sdk/cmdline-tools/latest
export ANDROID_HOME=$HOME/android-sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```
