# 10-26(월) 첫날 체크리스트

상세 ⑥ 개발 계획 6·7·8절과 1주차 실측표 W00·W03을 저장소 명령으로 옮긴 것. 결과는 `docs/ENV.md` '팀 PC' 표와
`docs/measurements/week1.csv`(한 항목 한 줄)에 적는다. 막히면 그 칸에 HOLD와 이유를 적고 다음 항목으로 간다.

완료 기준(W00): **접근성 서비스 활성 / dump.jsonl 1장 / LLM 1회 응답**.

## 0. 시작 전 (전원)

- [ ] 10-26 전 합의 6항목(결정 트리·승인 범위·계정 명의·기기 순서·gate/결함 작성자 분리·병합 규칙) 회의록 링크
- [ ] `CODEOWNERS`에 A/B/C GitHub 아이디 기입
- [ ] 팀원 각자 Hugging Face에서 Gemma 약관 동의

## 1. 빌드 (B, 팀 PC)

```bash
git clone https://github.com/mienerim/capstone && cd capstone
# Android Studio가 local.properties(sdk.dir)를 만들면 Android 모듈이 자동 포함된다(capstone.android=auto).
./gradlew test                      # JVM 테스트 전부
./gradlew :app:assembleDebug :mock-app:assembleDebug
```

- [ ] 첫 구성 성공(AGP 8.13.0·compileSdk 36 가정, DECISIONS-IMPL I-18). 실패하면 AGP/compileSdk 조정 내용을 ENV.md에 기록
- [ ] 클라우드에서 **한 번도 컴파일되지 않은** 파일: `service/android/**`, `mock-app/**`, `llm/litertlm/**`, `app/**`.
      컴파일 오류는 이름 차이일 가능성이 높다(특히 `LiteRtLmRunner`의 LiteRT-LM API 이름, I-39)
- [ ] INTERNET 권한 없음 확인:
  ```bash
  $ANDROID_HOME/build-tools/36.0.0/aapt dump permissions app/build/outputs/apk/debug/app-debug.apk
  $ANDROID_HOME/build-tools/36.0.0/aapt dump permissions mock-app/build/outputs/apk/debug/mock-app-debug.apk
  ```
  `android.permission.INTERNET`이 나오면 의존 라이브러리 매니페스트가 넣은 것 → `tools:node="remove"`로 제거하고 다시 확인

## 2. 기기 준비 (A)

- [ ] 기기 체크 양식: 모델번호·One UI·Android·보안패치 / RAM·저장 / SoC / 여유 RAM·저장(모델 4GB+)
  ```bash
  adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
  adb shell getprop ro.build.version.oneui; adb shell getprop ro.build.version.security_patch
  adb shell cat /proc/meminfo | head -1; adb shell df -h /data
  ```
- [ ] AAPM(고급 보호)·Auto Blocker·자동 업데이트 OFF, USB 디버깅 ON, 배터리 '제한 없음'
- [ ] 코레일+ 패키지·버전 (코드의 `GatePolicy.KORAIL_PLUS = "com.korail.talk"`가 맞는지)
  ```bash
  adb shell pm list packages | grep -i korail
  adb shell dumpsys package com.korail.talk | grep -E "versionName|targetSdk"
  ```

## 3. 접근성 서비스 → dump.jsonl 1장 (A)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r mock-app/build/outputs/apk/debug/mock-app-debug.apk
```

1. 설정 > 접근성 > 설치된 앱 > '말로 하는 예매 도우미' 켜기. ADB 설치 앱이라 막히면
   설정 > 애플리케이션 > 도우미 > ⋮ > **제한된 설정 허용** 뒤 다시 시도(W00, 실패 시 `adb shell appops set capstone.app ACCESS_RESTRICTED_SETTINGS allow`)
2. 켤 때 나오는 경고 문구(비 접근성 도구 경고·제한된 설정 경고)를 캡처(W09)
3. 목업 앱 실행 → S1~S3을 한 번씩 연다(S3 '예매하기'는 누르지 않는다)
   ```bash
   adb shell am start -S -n capstone.mock/.MockActivity
   adb logcat -s DumperService     # "dump s..._0001" 줄이 화면마다 나와야 한다
   ```
4. 덤프 꺼내서 형식 검사
   ```bash
   mkdir -p dumps/raw
   adb exec-out run-as capstone.app cat files/dump.jsonl > dumps/raw/mock-$(date +%m%d).jsonl
   ./gradlew :tools:run --args="validate $PWD/dumps/raw/mock-$(date +%m%d).jsonl"
   ```
   - [ ] 목업 화면 노드가 합성 픽스처(`tools/fixtures/mock/mock-korail.jsonl`)와 같은 viewId(`capstone.mock:id/...`)로 나오는지
   - [ ] `dumps/raw/`는 `.gitignore` 대상이다. **원본 덤프는 커밋하지 않는다**

덤프 규칙(DumperService, DECISIONS-IMPL I-42·I-43): 코레일+·목업이 앞에 있을 때만, 화면이 바뀔 때만, 세션당 500줄까지 쓴다.
기기 안에서 휴대폰·주민번호·카드번호·이메일·8자리 이상 숫자와 입력칸·data-sensitive 값은 `[MASK]`로 바뀌지만,
**이름·주소는 못 잡는다** → 커밋하려면 사람이 한 줄씩 보고 지운 뒤 `tools/fixtures/`에 넣는다.

## 3-1. D63 이벤트 수신 비교 (A, 시간이 남으면)

D63 ④(b): `packageNames`를 비웠을 때(기본)와 지정했을 때 받는 이벤트, 특히 패키지가 null인 `TYPE_WINDOWS_CHANGED`를
비교한다. AOSP 원문상 지정하면 이 이벤트가 전부 탈락한다(RQ-61) — One UI에서도 그런지 확인하는 실측이다.
DumperService가 30초마다·종료 시 이벤트 수를 **종류/출처(TARGET·NULL_PACKAGE·OTHER_APP)로만** 찍는다(`EventTally`, 다른 앱 이름은 안 남김).

```bash
adb logcat -c && adb logcat -s DumperService | grep events
# 목업 S1 → S2 → 뒤로 → 알림창 내렸다 올리기 → 키보드 열기를 2분 동안 반복
```

1. 기본(비움) 상태로 위 절차 → `events ...` 줄 기록
2. `service/android/src/main/res/xml/accessibility_service_config.xml`에 **임시로**
   `android:packageNames="capstone.mock"`을 넣고 다시 설치 → 접근성 끄고 켜기 → 같은 절차 → 기록
3. 설정 파일을 **원래대로 되돌린다**(커밋 금지)

- [ ] 비움: `WINDOWS_CHANGED/NULL_PACKAGE`가 0보다 큰지 / 지정: 0인지
- [ ] 지정했을 때 알림창·키보드 전환 뒤 덤프(`dump s..._000n`)가 빠지는지
- [ ] 결과를 week1.csv에 한 줄(`affected_D`=D63, 두 경우의 `events` 줄을 `actual_summary`에). 비움에서도 0이면 HOLD → B와 EventFilter 재검토

## 4. 모델 로드 체크 5줄 (C, W03, D40)

```bash
# ① 0.18.0 게시·ResponseFormat 확인 (없으면 0.18.1로 gradle/libs.versions.toml 수정, latest.release 금지)
curl -s https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml | grep -E "0\.18\.(0|1)|latest|release"
unzip -p litertlm-android-0.18.0.aar classes.jar > c.jar && unzip -l c.jar | grep -i ResponseFormat
# ② 모델 받기 + 크기·해시 기록 (models/는 .gitignore)
huggingface-cli download google/gemma-3n-E2B-it-litert-lm gemma-3n-E2B-it-int4.litertlm --local-dir ./models
ls -l models/; sha256sum models/*.litertlm
# ③ INTERNET 없음 (1절 aapt)
# ④ 앱 1회 실행(files/ 생성) 뒤 push. 폴더가 없으면 adb shell mkdir -p /sdcard/Android/data/capstone.app/files, 거부되면 /data/local/tmp + run-as 복사
adb push ./models/gemma-3n-E2B-it-int4.litertlm /sdcard/Android/data/capstone.app/files/
#   adb push ./models/gemma-3n-E2B-it-int4.litertlm /data/local/tmp/ && adb shell run-as capstone.app cp /data/local/tmp/gemma-3n-E2B-it-int4.litertlm files/
# ⑤ EngineConfig(modelPath, cacheDir) initialize → 1·2회차 로드 시간, 한 문장 생성 성공이면 통과 (CPU 먼저, Snapdragon만 GPU 추가)
```

- [ ] `LiteRtLmRunner`(llm/litertlm)를 AAR 실제 API에 맞게 고치고 컴파일(I-39)
- [ ] 로드 1·2회차 시간, 첫 토큰 시간, 메모리 피크를 week1.csv에 기록

## 4-1. 데모 앱 목업 연결 확인 (C, 시간이 남으면)

목업 앱(`capstone.mock`)만 대상으로 한다. 코레일+는 건드리지 않는다.

- [ ] `adb install -r app/build/outputs/apk/debug/app-debug.apk` → 첫 실행에 고지·동의 화면, '동의하고 시작' 뒤 알림 권한 요청
- [ ] '버튼으로 찾기' → 서울 → 부산 → 내일 → 복창 문장 확인 → '네' → 목업 앱이 앞으로 오고 입력·조회가 진행
- [ ] 열차 고르기는 우리 앱 화면 큰 버튼으로 묻는다(ASK_USER) → 고르면 목업 S3(좌석)에서 멈추고 요약 화면
- [ ] 진행 중 알림 '멈춰' → 우리 화면으로 오고 '계속'/'그만하기'. 목업 S4(결제)에 도달하면 **M7 실패**로 기록
- [ ] 증명 로그: `adb shell run-as capstone.app cat files/gate.jsonl | tail`
- [ ] 동의 기록 초기화(다시 시험): `adb shell pm clear capstone.app`

## 5. 마무리

- [ ] `docs/ENV.md` 팀 PC 표 채우기(OS·Android Studio·JDK·assembleDebug·litertlm 게시 여부)
- [ ] 막힌 것은 Notion '🛠 개발 백로그' > 막힌 것에 한 줄
- [ ] 실서비스(코레일+)는 첫날 조작하지 않는다. 덤프는 10-27, D17 한도(일 5건·조회 3회·간격 3초+·'예매하기' 0회)
