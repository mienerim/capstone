# 1주차 계획 ↔ 코드 대조 (7회차, 2026-10-23)

Notion '상세 ⑥ 개발 계획(역할·1~2주차)'(10-23 14시판)과 `claude/dev` 코드를 맞춰 본 결과. 
✅ 일치 / 🔧 이번 회차에 고침 / ⏳ 실측·합의 뒤 코드에 반영 / 📝 이름만 다름(동작 같음).

| 계획 항목 | 코드 | 상태 |
| --- | --- | --- |
| §1 minSdk 34, INTERNET 미부여, litertlm-android 0.18.0 고정 | `app/build.gradle.kts` minSdk 34, `gradle/libs.versions.toml` 0.18.0, 매니페스트 INTERNET 없음 + `ManifestPolicyTest` | ✅ |
| §2 10모듈 구조 | settings.gradle.kts: gate/core·gate/exec·agent-core·llm/core·llm/litertlm·app-scenarios·app-core·app·snapshot·service/core·service/android·tools·mock-app (계획의 10모듈 + I-40·I-46 분리) | 📝 |
| §2 CI는 JVM 테스트만, 모델 파일 미포함 | `.github/workflows/ci.yml`, `.gitignore`(*.litertlm 등) | ✅ |
| §6 hello-world 7파일 | `res/xml/a11y_config.xml` → `accessibility_service_config.xml`, `NodeDumper.kt` → `TreeCapture`+`DumpRecorder`, `LlmRunner.kt`(인터페이스)+`LiteRtLmRunner` | 📝 |
| §6 a11y 플래그 canRetrieveWindowContent·canTakeScreenshot·flagReportViewIds\|flagRetrieveInteractiveWindows | 모두 있음 + flagIncludeNotImportantViews(G5 위임 확인용) | ✅ |
| §7·D57 첫날 LLM: CPU, topK 1·temperature 0·seed 고정, maxOutputToken 256, enableResponseFormat, tools 미등록 | `LiteRtLmRunner`·`WatchdogLimits` | ✅ (미빌드) |
| D57 maxNumTokens 2048 | 없었음 → `EngineConfig(maxNumTokens = 2048)` | 🔧 (미빌드, I-55) |
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
