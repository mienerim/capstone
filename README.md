# 말로 하는 열차 예매 도우미 (캡스톤)

고령 사용자가 "내일 부산 가는 KTX 예매해줘"라고 말하면, 갤럭시 폰 안의 AI가 접근성(AccessibilityService)으로
코레일+ 앱을 대신 조작해 열차 검색·좌석 자동배정까지 하고 **'예매하기' 버튼 바로 앞에서 멈추는** 앱이다.
마지막 버튼과 결제는 사용자가 직접 누른다.

- AI(LLM)는 **발화 → 슬롯 JSON** 한 곳에만 쓴다(D3·D30). 화면 조작은 미리 정의한 상태머신이 한다.
- 모든 조작은 **독립 정지 게이트(gate)** 를 거친다. 확정·결제 버튼, 안 보이는 화면은 무조건 정지(D18·D35·D36).
- 완전 온디바이스: 앱에 `INTERNET` 권한이 없다. Play 배포 없이 ADB로 설치한다(D1).
- 대상 기기: 갤럭시 S23~S25(One UI 8+, Android 16+), `minSdk 34`. S26 제외, A34는 조사 중.

설계 원천은 팀 Notion '캡스톤 온디바이스 조사'(종합 보고서·결정 로그)이고, 코드에서 문서를 해석한 내용은
[`docs/DECISIONS-IMPL.md`](docs/DECISIONS-IMPL.md)에 남긴다.

## 모듈 구조 (D26·D36)

| 모듈 | 종류 | 내용 | 담당 |
| --- | --- | --- | --- |
| `gate/core` | 순수 Kotlin | 정지 게이트 판정 `check`/`checkAfter` → `Allow`/`Block(reason)`, UiTree 스냅샷 모델, 증명 로그(JSONL) | B |
| `gate/exec` | 순수 Kotlin | 유일한 `ActionExecutor` 구현. `RawActuator`(service가 구현) 호출·CLICK 실패 시 중심 탭 1회 폴백 | B |
| `agent-core` | 순수 Kotlin | 화면 분류기(`ScreenClassifier`)·최소 DSL(`Flow`: click/type/wait_for/back/expect/ask/finish)·상태머신(`ScenarioRunner`: RUN·HANDOFF·ASK_USER·PAUSED_FOR_USER, 복구 사다리). **gate API(`ActionExecutor`)에만 의존** | B |
| `llm/core` | 순수 Kotlin | 슬롯 스키마 v1·검증기·`LlmRunner` 인터페이스·워치독·2단계 파서(`SlotParser`), D18 T1~T8 llm 쪽 | C |
| `llm/litertlm` | Android | LiteRT-LM 어댑터(`litertlm-android` 0.18.0 고정, **미빌드**: API 이름 10-26 확인) | C |
| `app-scenarios` | 순수 Kotlin | 앱별 화면 서명·시나리오(목업 `MockKorail`·`MockKorailFlow`, 코레일+는 10-27 덤프 뒤) | A |
| `service` | Android | AccessibilityService, 노드 덤퍼, raw `performAction`/`dispatchGesture` | A |
| `app` | Android | 데모 앱: 고지·동의, 버튼/음성 입력, 고정 알림, 모듈 조립 | C |
| `mock-app` | Android | 코레일+ 흉내 4화면(S1 검색/S2 결과/S3 좌석+예매하기/S4 결제) + 변형 스위치 | C |
| `tools` | 순수 Kotlin | JSONL 재생기([`tools/README.md`](tools/README.md))·결함 주입 7종·n=20/60 하니스(Wilson 구간)·adb 스크립트(예정) | B |

의존 방향: `app → {service, gate/exec, agent-core, app-scenarios, llm}`, `agent-core → gate/core`,
`service → gate/exec`. agent-core는 service를 볼 수 없으므로 gate를 우회해 누를 방법이 없다.

## 빌드

```bash
# JDK 17 필요
./gradlew test                          # SDK가 없으면 순수 Kotlin 모듈만
./gradlew -Pcapstone.android=false test # CI와 같은 조건(JVM만)
./gradlew :app:assembleDebug            # Android SDK(ANDROID_HOME 또는 local.properties sdk.dir) 필요
```

- `capstone.android=auto|true|false`(gradle.properties). auto는 Android SDK가 보이면 Android 모듈을 포함한다.
- 모델 파일(`*.litertlm`)은 저장소에 넣지 않는다. 기기에 `adb push`로 올린다(D40, `docs/` 첫날 체크리스트 예정).
- 빌드 후 `aapt dump permissions app-debug.apk`로 INTERNET 권한이 없는지 확인한다.

## 역할 (D37 안 1)

- **A — 실기기·코레일+ 조작**: `service`, `app-scenarios`. 코레일+·병원 앱 노드 덤프, 창 목록, 오류 6, M1·M2·M5.
- **B — 안전장치·상태머신·자동 테스트**: `gate`, `agent-core`, `tools`, CI. gate T1~T12·G1~G8, 재생기 M3·M4·M6·M7.
- **C — AI 모델·앱·목업**: `llm`, `app`, `mock-app`. 모델 로드·8GB 실측·STT, **gate 결함·변형 풀 작성**.

리뷰는 A→B→C→A로 돌고, 트렁크 기반 짧은 PR + 리뷰 1명 + CI 통과 후 병합한다.

## 지켜야 할 것

- '예매하기'·결제·확정 버튼을 누르는 코드 금지. 비밀번호 저장·대리 로그인 금지.
- 앱에 INTERNET 권한·클라우드 API 금지. AGPL 코드(DroidRun Portal 등) 복사 금지.
- 비밀키·토큰·모델 파일 커밋 금지. 실기기 덤프는 마스킹한 것만 커밋.
- 실서비스 시도는 하루 5건·조회 3회·간격 3초 이상·'예매하기' 클릭 0회(D17).
