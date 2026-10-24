# 10-26(월) 첫날 체크리스트

상세 ⑥ 개발 계획 6·7·8절과 1주차 실측표 W00·W03을 저장소 명령으로 옮긴 것. 결과는 `docs/ENV.md` '팀 PC' 표와
`docs/measurements/week1.csv`(한 항목 한 줄)에 적는다. 막히면 그 칸에 HOLD와 이유를 적고 다음 항목으로 간다.

완료 기준(W00): **접근성 서비스 활성 / dump.jsonl 1장 / LLM 1회 응답**.

## 첫날 할 일 5줄 (D78, 상세 ⑥ 10-24 06시 최종 동결 절)

이 문서의 절과 연결만 적는다. 순서는 이대로.

1. **A·B** 팀 폰 3대 + A34 상태 기록 → 2절 (API 37이면 W09부터, D55 '업데이트 보류' 재결정 = 안건 A13)
2. **B** Android 모듈 컴파일 → 1절 / **C** 모델 로드 체크 5줄 → 4절 (CPU, maxNumTokens 2048, maxOutputToken 384, **실제 입력 토큰 수 기록**)
3. **A** 삼성 보호 기능 캡처, 위험 앱 보호·Auto Blocker가 우리 APK를 막는지 → 2절 (막히면 5절 시연 기기 절차 변경)
4. **전원** 킥오프 안건(기존 9+3 + A1~A13)과 코레일 서면 문의(B 1통) 발송 여부 합의 → 0절
5. **전원** 팀 직접 확인 최종본 14건 담당·기한, 마스킹·파기 책임자 1명, gate 작성자 ≠ 결함 풀 작성자 지정 → 0절

## 0. 시작 전 (전원)

- [ ] 10-26 전 합의 6항목(결정 트리·승인 범위·계정 명의·기기 순서·gate/결함 작성자 분리·병합 규칙) 회의록 링크
- [ ] `CODEOWNERS`에 A/B/C GitHub 아이디 기입
- [ ] 팀원 각자 Hugging Face에서 Gemma 약관 동의
- [ ] 킥오프 안건 A1~A13(상세 ⑥ 10-24 06시 절)과 함께 개발 백로그 해석 I-5~I-11·I-62·I-70·I-72·I-74·I-78·I-79·I-80 처리.
      A12(토큰 한도)는 코드가 이미 요청값 2048 단일(I-79) + 실효 예산 min(2048, 파일 KV)·단계 사전 검사(D79, I-80). A12 안건 = 모델별 실효 예산 표와 A34 Qwen용 짧은 프롬프트(두 벌 vs 공통 판)
- [ ] 팀 직접 확인 최종본 14건(이전 11건·STT 6건 표기를 대체) 담당·기한을 회의록에 적는다

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
- [ ] (D74, 끄기 **전에** 먼저 기록 — A34 포함 4대) 설정 > 휴대전화 정보의 One UI·Android 버전·API·보안 패치,
      자동 업데이트 켜짐 여부, 고급 보호·Auto Blocker 상태를 `docs/ENV.md` '팀 기기' 표에 적는다
  ```bash
  adb shell getprop ro.build.version.sdk          # 37 = One UI 9(Android 17), 36 = One UI 8/8.5
  ```
  판정: **API 37 기기는 W09(고급 보호·위험 앱 보호) 먼저**, API 36 기기는 '업데이트 보류' 유지 여부를 그 자리에서 결정(D55 재결정).
  앱은 compileSdk·targetSdk 36이라 API 37 기기에서도 설치된다(I-75) — 문제가 보이면 HOLD로 적는다
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
2. 켤 때 나오는 경고 문구(비 접근성 도구 경고·제한된 설정 경고)를 캡처(W09). 켠 시각을 적어 둔다 —
   24시간 뒤 비tool 경고가 1회 뜨는지(D74 ②, 10-27 같은 시각에 확인). **서비스를 껐다 켜면 기록이 지워져 다시 24시간**이므로
   W09 확인 전에는 토글하지 않는다
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

- [ ] `LiteRtLmRunner`(llm/litertlm) 컴파일. 이름은 10회차에 v0.18.0 태그 원본과 대조해 맞췄다(I-62) — 그래도 틀리면 AAR 기준으로 고친다
- [ ] 로드 1·2회차 시간, 첫 토큰 시간, 메모리 피크를 week1.csv에 기록
- [ ] (15회차, D77) KV 한도는 전 기기 `ContextBudget.MAX_NUM_TOKENS` = 2048. 로드 뒤 PSS(`adb shell dumpsys meminfo capstone.app`)를 적는다
- [ ] (D77) **최종 렌더링 입력 토큰 수**: 채팅 템플릿 적용 뒤 1단계·2단계 각각, 최장 발화(장황한 말·STT 반복 오인식 흉내, 200자)로 **최대값**을 잰다(평균 아님).
      `enableResponseFormat` 켬/끔 두 번 재서 차이 = 엔진이 스키마를 프롬프트에 넣는 몫. 저장소 어림값은 2단계 기차가 가장 커서 약 1,325(I-79, 실측 아님)
- [ ] (D77) 판정: 최대값 > 1,400, 또는 Prefill 에러·kMaxNumTokensReached가 1회라도 → `ContextBudget.shouldTryRaise`가 참(파일 KV가 3072 미만이면 거짓, D79) → 3072로 PSS 재측정(코드 기본값은 바꾸지 않는다). 넘치면 3072보다 예시·지시 줄이기를 먼저 검토(팀 합의)
- [ ] (16회차, D79·D57) `litert-lm-peek`로 모델 파일의 `max_num_tokens`·prefill 시그니처를 표로 적는다(Gemma 3n E2B, A34면 Qwen3도). 추정값을 쓰지 않는다
- [ ] (D79) maxNumTokens=2048로 로드할 때 logcat에 `Using model limit` 경고가 나오는지(`adb logcat | grep -i "model limit"`). 나오면 파일 KV < 2048 → 그 값을 `LiteRtLmRunner.budget`에 넣어야 한다(I-80)
- [ ] (D79) AAR에 `ModelInfo`·`maxContextTokens`·`isDynamicContext`가 보이는지: `unzip -l c.jar | grep -i ModelInfo`. 보이면 `LiteRtLmRunner.budget`을 그 값으로 교체, 없으면 peek 값을 상수로
- [ ] (D79, 17회차) week1.csv 마지막 열 `effective_kv`에 실효 예산 숫자(min(2048, 파일 KV))를 LLM이 도는 모든 행(W03·W04·W11·W12·W13)에 적는다. LLM과 무관한 행은 비운다. W03 행 `measured_value`에는 `Budget.csv()` 형식 `요청,파일KV,실효`(예: `2048,,2048` = 파일 KV 미확인)를 따옴표로 감싸 적는다(I-82). 파일 KV가 4096 등 2048보다 크면 2048 지정 vs 미지정 PSS를 비교(KV 버퍼가 실제로 줄어드는지)
- [ ] (D79) 2단계 사전 검사로 `budget_exceeded`(되묻기)가 나오면 프롬프트가 실효 예산에 안 들어간 것 — 예시·지시를 줄일지 팀에 올린다
- [ ] 로그 금지(D70·I-67): 토큰 수는 숫자만 week1.csv에. 프롬프트·발화·출력 원문은 logcat·파일에 남기지 않는다

## 4-1. 데모 앱 목업 연결 확인 (C, 시간이 남으면)

목업 앱(`capstone.mock`)만 대상으로 한다. 코레일+는 건드리지 않는다.

- [ ] `adb install -r app/build/outputs/apk/debug/app-debug.apk` → 첫 실행 온보딩 v1(D66): 인사 → 고지·동의('동의합니다') → 혼자/가족 → 알림 허용 → 접근성 켜기(설정 다녀오면 자동 확인) → '켜졌어요' → 연습
- [ ] (13회차, D74 ③) '켜졌어요' 화면에 24시간 경고 사전 안내 2줄('…알림이 한 번 올 수 있어요…그대로 두셔도 됩니다', '스위치는 켜진 그대로')이 보이는지
- [ ] 온보딩 분기: 알림 거부 → 알림 화면에 남음 / 접근성을 안 켜고 2번 돌아옴 → '가족에게 안내 보내기' / 스위치가 안 눌리면 '제한된 설정 허용' 안내. One UI 실제 메뉴 이름을 캡처 3장으로 남긴다(문구 교체용)
- [ ] (11회차, D69 ⑤) 온보딩 끝낸 뒤 설정 > 알림에서 이 앱 알림 또는 '진행 안내' 채널만 끄기 → '이렇게 찾아 주세요' → 대행이 시작되지 않고 알림 허용 안내 문구가 나오는지. 확인 후 원복
- [ ] (11회차, D68) S24/S25가 API 36이면 고급 보호 켬/끔 상태 기록만(켜지 말 것 — W09 담당 A가 따로). API 34·35 기기는 접근성 2번 실패 시 분기 A에 '고급 보호가 켜져 있으면 제한될 수 있어요' 문구가 나오는지
- [ ] '버튼으로 찾기' → 서울 → 부산 → 내일 → 복창 문장 확인 → '네' → 목업 앱이 앞으로 오고 입력·조회가 진행
- [ ] 열차 고르기는 우리 앱 화면 큰 버튼으로 묻는다(ASK_USER) → 고르면 목업 S3(좌석)에서 멈추고 요약 화면
- [ ] 진행 중 알림 '멈춰' → 우리 화면으로 오고 '계속'/'그만하기'. 목업 S4(결제)에 도달하면 **M7 실패**로 기록
- [ ] 증명 로그: `adb shell run-as capstone.app cat files/gate.jsonl | tail`
- [ ] 동의 기록 초기화(다시 시험): `adb shell pm clear capstone.app`

## 5. 시연 기기 절차 (D74 ④, 시연 주간에 — 첫날은 읽어 두기만)

D55 보호 기능 점검 6줄에 더한다. 비tool 접근성 서비스는 켠 지 24시간 뒤 경고가 한 번 뜨고, 서비스를 끄면 그 기록이 지워진다(AOSP).

1. 시연 **48시간 전** 빌드 고정 → 설치 → 접근성 서비스 켜기
2. 그 뒤 서비스 끄기/켜기·앱 재설치 **금지**
3. 경고가 뜨면 한 번 닫아 둔다(사용자 안내 문구에는 '닫으세요'를 쓰지 않는다 — 시연 담당만)
4. 시연 직전 기록 확인 — 우리 서비스 이름이 있어야 한다
   ```bash
   adb shell settings get secure notified_non_accessibility_category_services   # capstone.app/capstone.service.DumperService 포함?
   ```
5. D55 ②③(고급 보호·Auto Blocker 꺼짐) 재확인

## 6. 마무리

- [ ] `docs/ENV.md` 팀 PC 표 채우기(OS·Android Studio·JDK·assembleDebug·litertlm 게시 여부)
- [ ] 막힌 것은 Notion '🛠 개발 백로그' > 막힌 것에 한 줄
- [ ] 실서비스(코레일+)는 첫날 조작하지 않는다. 덤프는 10-27, D17 한도(일 5건·조회 3회·간격 3초+·'예매하기' 0회)
