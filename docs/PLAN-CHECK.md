# 1주차 계획 ↔ 코드 대조 (7회차, 2026-10-23 / 8·9회차 보충 / 10회차 20시판 재대조 / 11회차 22시판)

Notion '상세 ⑥ 개발 계획(역할·1~2주차)'(7회차 14시판, 10회차에 20시판 D65·D66·D67로 다시 맞춤)과 `claude/dev` 코드를 맞춰 본 결과. 
✅ 일치 / 🔧 이번 회차에 고침 / ⏳ 실측·합의 뒤 코드에 반영 / 📝 이름만 다름(동작 같음).

| 계획 항목 | 코드 | 상태 |
| --- | --- | --- |
| §1 minSdk 34, INTERNET 미부여, litertlm-android 0.18.0 고정 | `app/build.gradle.kts` minSdk 34, `gradle/libs.versions.toml` 0.18.0, 매니페스트 INTERNET 없음 + `ManifestPolicyTest` | ✅ |
| §2 10모듈 구조 | settings.gradle.kts: gate/core·gate/exec·agent-core·llm/core·llm/litertlm·app-scenarios·app-core·app·snapshot·service/core·service/android·tools·mock-app (계획의 10모듈 + I-40·I-46 분리) | 📝 |
| §2 CI는 JVM 테스트만, 모델 파일 미포함 | `.github/workflows/ci.yml`, `.gitignore`(*.litertlm 등) | ✅ |
| §6 hello-world 7파일 | `res/xml/a11y_config.xml` → `accessibility_service_config.xml`, `NodeDumper.kt` → `TreeCapture`+`DumpRecorder`, `LlmRunner.kt`(인터페이스)+`LiteRtLmRunner` | 📝 |
| §6 a11y 플래그 canRetrieveWindowContent·canTakeScreenshot·flagReportViewIds\|flagRetrieveInteractiveWindows | 모두 있음 + flagIncludeNotImportantViews(G5 위임 확인용) | ✅ |
| §7·D57 첫날 LLM: CPU, topK 1·temperature 0·seed 고정, maxOutputToken 256, enableResponseFormat, tools 미등록 | `LiteRtLmRunner`·`WatchdogLimits` | ✅ (미빌드) → 256은 10회차 D65로 384 |
| D57 maxNumTokens 2048 | 없었음 → `EngineConfig(maxNumTokens = 2048)` | 🔧 (미빌드, I-55) → 10회차 D65로 대체 |
| §8 week1.csv 19열 | `docs/measurements/week1.csv` 헤더 동일 | ✅ |
| D58 커밋 전 CI 정규식 린트 | 없었음 → `tools mask-lint` + CI 단계 + 저장소 자체 검사 테스트 | 🔧 (I-52) |
| D58 logcat에 노드 텍스트 출력 금지 | `DumperService`는 경로·snapId·예외만 로그 | ✅ |
| 안건: 인증 단계는 USER_AUTH, 에이전트 조작 0 | 없었음(LOGIN만 HANDOFF) → `HandoffTrigger.USER_AUTH` | 🔧 (I-53) |
| 안건 8: SRT 걸러냄(기본) | 없었음 → 목업 시나리오 열차 보기에서 제외 | 🔧 (I-54) |
| D60 정지 = 서명 일치 AND text/desc 예매·결제·접수 키워드, resource-id 보조, 불확실=정지 | gate `ConfirmMatcher`(동사+접미사·BARE_TAILS, viewId 토큰)·fail-closed 6종 | ✅ (사전은 10-27 덤프로 보정) |
| D60 '10분 초과 정지 시 조회 한도 안 재조회 1회' | 없음. 10분은 실측(W10 방치 실험) 후 확정 | ⏳ |
| D17 일 5건·조회 3회·간격 3초 | `DailyRunLimiter`, `RunnerConfig.maxQueries`·`minQueryGapMs` | ✅ |
| D52 병원 탐색 하루 3회 별도 | 병원 시나리오 없음(I-47) | ⏳ |
| W07 takeScreenshotOfWindow 오류 코드 → 스냅샷 | `screenshotError` 필드·러너 처리는 있음, 서비스에서 채우는 코드 없음 | ⏳ (10-27 A) |
| W02 알림 → Activity → PAUSED_FOR_USER | `pauseForUser()`·`resumeFromPause()` 있음, 알림 탭 연결은 B가 10-28 실측과 함께 | ⏳ |
| D55 AAPM·Auto Blocker 안내 | 없음(W09 문구 캡처 뒤) | ⏳ |
| D46 NOTICE에 Gemma ToU 문장·PUP·HF repo | 없었음 → NOTICE에 추가 | 🔧 |
| §3 gate 작성자 ≠ 결함 풀 작성자 | gate·결함 풀 모두 루틴 작성 → C가 독립 결함·변형 풀 추가 필요 | ⏳ (팀) |
| 2주차 D45 목업 팝업·알림·노드 텍스트 주입 3~5건 | 재생기 결함 7종은 있음, 주입 텍스트 풀은 C 몫 | ⏳ (팀) |
| 안건 8 보강: 미리 채운 열차가 SRT·매진이어도 누르지 않음 | `ask` 단계가 미리 채운 값을 지금 보기로 다시 확인 + SRT 재생기 픽스처 | 🔧 (8회차, I-57) |
| D62 숨김 상태 기본 호출 = 진행 중 알림 '계속/그만', setDeleteIntent 재게시 | `ProgressNotice`는 있음, 액션·재게시·5분 TTS 질문은 B가 W02와 함께 | ⏳ |
| D63 ② packageNames 비움 + 이벤트 필터 + 허용목록 밖 순회 금지 | `EventFilter`·`TreeCapture(targetPackages)`·`DumperService.TARGET_PACKAGES` | 🔧 (8회차, I-58, android 쪽 미빌드) |
| D63 ④(b) packageNames 지정/비움 수신 비교(실측) | `EventTally` 집계 + `DumperService` 30초 로그, 절차는 FIRST-DAY 3-1 | 🔧 (9회차, I-61, 실측은 A) |
| D63 ① 직접 startActivity는 보조·실측 비교용 | 코드 없음(기본은 알림 경로) | ✅ |
| D64 llama.cpp 대안 경로 | 실험 브랜치 전용이라 `claude/dev`에 없음 | ⏳ (I-60) |

## 10회차: 20시판(D65·D66·D67) 재대조

| 계획 항목 | 코드 | 상태 |
| --- | --- | --- |
| D65 maxOutputToken 384(요청 1회 생성 상한, 도달 시 에러 없이 끝남) | `WatchdogLimits.maxTokens` 384, 단계별 요청 64·192 유지 | 🔧 (I-62) |
| D65 잘림 감지 = 앱 쪽 JSON 닫힘 검사 → 재프롬프트 1회/되묻기 | `SlotValidator` `truncated` 코드 → `SlotParser` 재생성 1회 → `AskAgain` | 🔧 (I-62) |
| D65 maxNumTokens 4096 후보, A34 6GB 2048 | `ContextBudget`(totalMem 7GiB 경계) → `LiteRtLmRunner(maxNumTokens)` | 🔧 (미빌드, 값은 첫날 PSS로 확정) |
| D65 '4096은 UI 트리 포함', A34 UI 트리 압축 | 우리 llm 입력에 UI 트리 없음(D32·I-38) → 할 일 없음, 2048 단일화 검토 | ⏳ (팀 확인, I-62 ④) |
| D65·D13 요청마다 새 Conversation | `LiteRtLmRunner.generate`가 매번 만들고 닫음 | ✅ |
| D65 thinking 끄기(ThinkingConfig 1순위) | `ConversationConfig(thinkingConfig = ThinkingConfig(false))` | 🔧 (미빌드) |
| D65 Qwen3 extraContext·`<think>` 제거, llama.cpp 5c0d188 | W13 실험 브랜치 몫(D64·I-60) | ⏳ |
| (10회차 발견) 어댑터 API 이름 | v0.18.0 태그 원본 대조: `Backend.CPU()`·`Contents.of`·`cancelProcess()` | 🔧 (I-62 ⑤) |
| D66 온보딩 ①~⑦ + 분기 A·B | `Onboarding`(app-core) + `MainActivity` 온보딩 화면 + `AndroidDeviceChecks` | 🔧 (app 쪽 미빌드, I-63) |
| D66 ④ 알림 거부 시 서비스 시작 막음(D62) | `Onboarding.startBlocker()`를 실행 직전에 확인 | 🔧 |
| D66 수정 ① 마이크는 첫 음성 사용 때 | 온보딩에 마이크 단계 없음(테스트로 고정). STT 자체가 아직 없음(W03 뒤) | ✅ / ⏳ |
| D66 수정 ② '외부 전송 없음' 범위 한정 | `Disclosure` v2 문구 | 🔧 (I-64, 기내모드 실측 뒤 확정) |
| D66 수정 ③ AP 감지 API 36 가드, 34·35는 분기 A로 흡수 | `advancedProtection()` null = 감지 불가 | 🔧 (미빌드) |
| D66 메뉴명 실기기 캡처로 교체 | 6단계 안내 글은 가안 | ⏳ (FIRST-DAY 4-1) |
| D66 '연습 1회' | 목업 버튼 흐름 | 🔧 |
| 10-26 안건: 온보딩 권한 범위·최대 단계 수 | 7단계 그대로 구현, 줄이면 `Onboarding` 상태만 바꾸면 됨 | ⏳ (팀 합의) |
| D67 코레일 약관 팀 확인 #2 | 저장소 할 일 없음 | ✅ (I-65) |


## 11회차: 22시판(D68·D69·D70) 대조

| 계획 항목 | 코드 | 상태 |
| --- | --- | --- |
| D68 ① AP 감지는 SDK ≥ 36에서만, 예외는 불명, QUERY_ADVANCED_PROTECTION_MODE | `AndroidDeviceChecks.advancedProtection()`·매니페스트 권한 | ✅ (미빌드) |
| D68 ② 켜짐 확인 + 서비스 꺼짐 → 분기 B(설명 위주·해제 강요 없음·보호자 확인) | `Onboarding.blockedByProtection()`·`PROTECTED_TEXT` 문구 테스트 | 🔧 (I-68, 전에는 AP 켜짐만으로 B) |
| D68 ② 불명 → 분기 A + '제한될 수 있어요' 보조 문구 | `AskFamily(protectionUnknown)`·`AP_UNKNOWN_HINT` | 🔧 |
| D68 ③ 서비스 활성 매번 확인, AP 콜백은 재검사 트리거만 | `onResume` → `recheckProtection()`, 콜백 미등록 | 🔧 (I-68) |
| D68 분기 B 문구 확정·Auto Blocker·위험 앱 보호 실측 | W09(A) | ⏳ |
| D69 ⑤ 앱·채널 알림 꺼짐 → 대행 미시작·앱 화면 허용 안내 | `notificationsGranted()` = areNotificationsEnabled + 채널 중요도, `NOTIFICATIONS_OFF_TEXT` | 🔧 (I-69, android 쪽 미빌드) |
| D69 ① 진입 3중화(알림 + 앱 아이콘 재개 + 인앱 배너) | 알림·아이콘 재개는 있음, 인앱 배너 없음 | ⏳ (W02, B) |
| D69 ② deleteIntent 1회 재게시 ③ 접근성 버튼 보조 ④ CallStyle 금지 | CallStyle 안 씀, 재게시·접근성 버튼 없음 | ✅ / ⏳ (W02, B) |
| D39 '고정 알림' → '진행 알림(지울 수 있음)' | `ProgressNotice` 주석 | 🔧 |
| D70 ① 병원명·진료과·의사명·예약 일시·증상 저장 전 마스킹 | `DumpMasker` 기관명·진료과 패턴 + `healthPackages` 창 전체 마스킹 | 🔧 (I-66, 패키지명은 10-28 뒤) |
| D70 ③ 건강정보 GitHub 금지 | `mask-lint` 기관명에 약국·보건소 추가 | 🔧 |
| D70 검증: logcat·LLM 입출력 로그 마스킹 | llm·app-core·app 로그 호출 0개를 `LogPolicyTest`로 고정 | 🔧 (I-67) |
| D70 ② 비로그인 → 가상 프로필 → 목업, 본인인증부터 목업 | 병원 시나리오 없음(I-47) | ⏳ (10-28 C 드라이런 뒤) |

## 12회차: 결정 로그 10-24 00시(D71·D72·D73·D13 정정) 대조

상세 ⑥(개발 계획)에는 아직 10-24 00시 반영이 없어 결정 로그 원문과 직접 대조했다.

| 결정 | 코드 | 상태 |
| --- | --- | --- |
| D71 QUERY_ADVANCED_PROTECTION_MODE = normal(AOSP), 호출 실패는 불명 | 매니페스트 권한·`advancedProtection()` null 가드 | ✅ (미빌드, 갤럭시 반환은 W09) |
| D71 AP 켜짐 = 지원 대상 밖, 분기 B '안내 후 중단'·조건부 문구 | `blockedByProtection()` = AP true, `PROTECTED_TEXT` '…수 있어서, 여기서 멈출게요' | 🔧 (I-70, I-68 대체) |
| D72 BAL 주석 2줄, 추가 리서치·코드 없음 | `MainActivity.bringToFront()` 주석 | 🔧 (I-71) |
| D73 ① 1초 안 확인음+'생각 중' ② 3초 필러 1회 ③ 10초 선택지 ④ 25초 안전 중단 | `WaitFeedback`·`AppSession.beginParsing/onWaitTick/answerWait`·MainActivity 틱 | 🔧 (I-72, android 쪽 미빌드) |
| D73 음성+문구 병행 | 확인음·글자만, 필러 음성은 TTS와 함께 | ⏳ (W03) |
| D73 W12 판정(첫 피드백 p95 ≤ 1초, 복창 p50 ≤ 8초·p95 ≤ 10초) | 측정 시트 몫, 단계값은 `Timing` | ⏳ (W12) |
| D13 정정: 8GB는 STT 언로드 직후 initialize, 선로드 금지 | 파싱 요청 때 첫 로드(이미 그 구조), 주석으로 순서 고정 | 🔧 (I-73, STT 붙일 때 강제) |

## 13회차: 결정 로그 10-24 02시(D74·D75·D76) + 상세 ⑥ 10-24 02시 절 대조

상세 ⑥에 '10-24 02시 추가' 절이 생겨 그 절과 결정 로그 원문을 함께 대조했다. 단, 상세 ⑥ 20시판의 온보딩 표 분기 B 문구('쓸 수 없어요')는 여전히 D66판이다 — 코드는 D71 조건부 문구를 따른다(I-70).

| 결정 | 코드 | 상태 |
| --- | --- | --- |
| D74 ① 국내 S23~S25 현행 API 37, A34 36(→37 예정), minSdk 34 유지·34/35 분기 유지·36이 보류 폰 주 경로 | minSdk 34, `advancedProtection()` API 가드, compileSdk·targetSdk 36 유지 | ✅ (I-75) |
| D74 ① 10-26 팀 폰 3대+A34 One UI·API·자동 업데이트·AP 기록 → D55 재결정 | FIRST-DAY 2절 항목·ENV '팀 기기' 표 | 🔧 (문서) |
| D74 ② 24시간 경고: 서비스 끄면 기록 삭제·재경고 | FIRST-DAY 3절 '켠 시각 기록·W09 전 토글 금지' | 🔧 (문서, One UI 동작은 W09) |
| D74 ③ 온보딩 사실 전달형 사전 안내 1문장 + '스위치는 그대로' 안내, '닫으세요' 금지 | `Onboarding.WARNING_NOTICE_TEXT`·`WARNING_SWITCH_TEXT`, ⑦ 화면에 표시, 금지어 테스트 | 🔧 (I-74, 그림은 W09 캡처 뒤) |
| D74 ④ 시연 48시간 전 고정·토글/재설치 금지·`notified_non_accessibility_category_services` 확인 | FIRST-DAY 5절 시연 기기 절차 | 🔧 (문서) |
| D74 ⑤ isAccessibilityTool=true 금지 | `accessibility_service_config.xml` false + `ManifestPolicyTest` | ✅ |
| D75 ② 결제기한을 코드 상수로 두지 않음 | 결제기한 상수·타이머 없음(정지점은 '예매하기' 전) | ✅ (I-76) |
| D75 ①③④ 대표번호·제재 단위·서면 문의 1통 | 코드 없음(팀 확인 #2) | ⏳ (팀, B 10-26 발송) |
| D76 STT 폴백 순위 ① SpeechRecognizer ② Moonshine tiny-ko / Whisper ③ sherpa 내부 측정만 | STT 코드 없음. NOTICE에 순위·sherpa 모델 번들·고지 금지·Moonshine 재배포 보류, `.gitignore`에 `*.ort`·`*.pt`·`*.safetensors` | 🔧 (I-77) |
| 상세 ⑥ W10 '정지 중 좌석 선점·세션 만료' | D60 재조회 1회와 함께 W10 실측 뒤 | ⏳ |

## 14회차: 새 결정 없음(결정 로그 최종 D76) — 9단계 테스트 보강

| 항목 | 코드 | 상태 |
| --- | --- | --- |
| D36 4.3 라벨 정규화·G2 변형 차단 | `LabelMutationFuzzTest`(변형 3,000개, 시드 고정)로 한글 채움 문자·제로폭으로 끊긴 NFD 자모 우회 2건 발견 → `LabelNormalizer`가 채움 문자 4개 제거 + 마지막 NFC | 🔧 (I-78) |

## 15회차: 결정 로그·상세 ⑥ 10-24 06시판(D77·D78) 대조

| 계획 항목 | 코드 | 상태 |
| --- | --- | --- |
| D77 maxNumTokens 전 기기 2048 단일(A34 포함), maxOutputToken 384 유지 | `ContextBudget.MAX_NUM_TOKENS` → `MainActivity`·`LiteRtLmRunner` 기본값, RAM 경계 삭제 | 🔧 (I-79, app·litertlm 미빌드) |
| D77 상향 조건: 최대 렌더링 입력 > 1,400 또는 Prefill 에러·kMaxNumTokensReached 1회 → 3072 PSS 재측정 | `ContextBudget.shouldTryRaise`·`RAISE_CANDIDATE` + 테스트 | 🔧 (판정은 W03 실측) |
| D77 입력 600~1,300토큰 추정·스키마 주입 여부 미확인 | 토큰 어림 울타리 테스트(2단계 기차 약 1,325가 최대), FIRST-DAY 4절 켬/끔 측정 | 🔧 / ⏳ (W03) |
| D77 '4096 후보'·'A34만 2048'·'UI 트리 압축' 폐기 | 코드·FIRST-DAY에서 4096 제거, 10회차 표의 D65 4096 행은 이력으로 남김 | ✅ |
| D77 '요청마다 새 Conversation' 유지 | `LiteRtLmRunner.generate`가 매번 만들고 닫음 | ✅ |
| D78 첫날 할 일 5줄 | FIRST-DAY 맨 앞 절(각 절 연결) | 🔧 (문서) |
| D78 팀 직접 확인 최종본 14건·킥오프 A1~A13 | FIRST-DAY 0절에 처리 항목, 개발 해석 I-5~I-11·I-62·I-70·I-72·I-74·I-78·I-79를 같은 회의로 | ⏳ (팀 10-26) |
| D78 코레일 서면 문의 B 1통 | 코드 없음 | ⏳ (팀) |
| 미해결 E P1: .litertlm 고정 KV(예: `_ekv1280`)와 2048의 관계 | 코드 없음. Gemma 3n 파일이 고정 KV면 maxNumTokens보다 파일 값이 우선일 수 있음 → W03에서 `litert-lm-peek`로 확인 | ⏳ (W03, C) |

## 16회차: 결정 로그 10-24 08시(D79) 대조

| 계획 항목 | 코드 | 상태 |
| --- | --- | --- |
| D79 요청값 2048 유지, 실효 예산 = min(2048, 파일 KV) | `ContextBudget.effective(fileKv)` → `Budget(requested, fileKv, effective)`, `LiteRtLmRunner.budget` | 🔧 (I-80, litertlm·app 미빌드) |
| D79 파일 KV는 엔진 생성 전 `ModelInfo.from(path).maxContextTokens()` | 지금은 파일명 표지(`_ekv1280`)만 `ContextBudget.fileKvFromName`, Gemma 3n은 null(추정값 금지) | ⏳ (10-26 AAR 노출 확인, 없으면 peek 상수) |
| D79 1·2단계 각각 '프롬프트 토큰 + 384 ≤ 실효 예산' 사전 검사, 초과 시 실행 0 | `SlotParser` 단계 앞 `ContextBudget.fitsStage` → `AskAgain(budget_exceeded)`, 생성 0회 + 테스트 | 🔧 (계수기는 보수적 어림, I-80) |
| D79 프롬프트 토큰 = 모델 토크나이저(템플릿 포함) | `PromptTokenCounter` 포트, 기본 `ConservativeTokenEstimate`(과대 어림) | ⏳ (W03 기기 토크나이저) |
| D79 실효 예산을 로그·실험 CSV 필수 열로 | `Budget.csv()` = `요청,파일KV,실효`. 로그 금지(D70·I-67)라 logcat이 아니라 week1.csv `measured_value`에 사람이 적는다 | 🔧 (FIRST-DAY 4절) |
| D79 A34 Qwen3 ekv1280 = 실효 1280·입력 약 896, 짧은 프롬프트 | 현 프롬프트는 2단계 기차 어림 약 1,325 → Qwen 예산 초과를 테스트로 기록(사전 검사가 막음) | ⏳ (팀 A12: 두 벌 vs 공통 짧은 판) |
| D79 D77 상향(>1,400 → 3072)은 파일 KV ≥ 3072일 때만 | `shouldTryRaise(..., fileKv)` — 3072 미만 확인이면 false | 🔧 |
| D57 주석: 첫날 peek 표에 max_num_tokens·prefill 시그니처, 'Using model limit' 경고 | FIRST-DAY 4절 항목 | 🔧 (문서) |
