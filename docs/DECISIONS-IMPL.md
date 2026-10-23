# 구현 해석 기록

Notion 설계 문서(결정 로그 D1~D41, 상세 ①~⑦)가 모호한 곳에서 **가장 단순한 해석**으로 구현한 내용.
문서가 바뀌면 문서를 따르고 여기 항목을 고친다. 번호는 I-n.

## 1회차 (2026-10-22)

- **I-1 모듈 이름**: D26의 gate를 `:gate:core`(순수 판정)·`:gate:exec`(실행)로, llm을 `:llm:core`(JVM: 스키마·검증기·인터페이스)·`:llm:litertlm`(Android 어댑터)로 나눴다. 클라우드·CI에서 Android 없이 llm의 비Android 부분을 테스트하기 위해서다.
- **I-2 UiTree 모델 위치**: D34의 '순수 Kotlin UiNode/UiTree(gate·agent-core 의존)'를 별도 모듈 없이 `:gate:core`의 `capstone.gate` 패키지(`UiTree.kt`)에 뒀다. agent-core는 어차피 gate API에 의존하므로 추가 모듈이 필요 없다. JSONL 직렬화는 `:tools`(백로그 ③).
- **I-3 NodeRef 안정 키**: 루트부터의 자식 인덱스 경로(`"0/2/1"`). treeHash는 창 id·타입·패키지와 모든 노드 필드의 SHA-256 앞 12바이트. 트리가 조금이라도 바뀌면 낡은 참조가 되어 `FAIL_CLOSED_SCREEN_SIGNATURE`로 막힌다(행동 재바인딩 대응, D18 ④). 노드가 없거나 창이 없을 때도 같은 사유.
- **I-4 화면 서명**: gate는 분류기를 갖지 않는다. `Snapshot.screen`(agent-core 분류 결과)과 `ActionRequest.expectedScreen`이 다르거나 `screen == "UNKNOWN"`이면 차단. `screen == null`(미분류)은 서명 검사 생략.
- **I-5 하드 매칭 규칙**: 정규화(NFKC → 글자·숫자만 남김 → 소문자) 후 ① '결제·구매·주문·송금·구독·페이·pay·좌석선택완료' 등으로 **끝나면** 하드 ② '예매·예약·접수·결제·구매·주문·송금·구독·가입·발권·신청' + '하기·확정·완료·진행·계속'으로 끝나면 하드 ③ 위 어근을 포함만 하면 PARTIAL(신호만). '예매'·'예약' 단독(탭 이름일 수 있음)은 PARTIAL. 대상 노드의 클릭 가능 여부와 무관하게 대상 자신의 라벨을 본다(더 보수적).
- **I-6 viewId 토큰**: `:id/` 뒤를 `_ - . /`와 camelCase로 나눈 토큰 중 pay·payment·purchase·buy·checkout·reserve·reservation·book·booking·order·confirm·ticketing이 있으면 `CONFIRM_NODE_ID`. 'book'은 오차단 위험이 있어 10-27 덤프로 재검토.
- **I-7 위임(G5)**: 클릭이 닿는 노드 = 대상이 클릭 가능하면 대상, 아니면 가장 가까운 클릭 가능 조상. 그 노드·그 사이 조상·그 서브트리 전체 라벨 중 하드가 있으면 `CONFIRM_ANCESTOR_DELEGATE`.
- **I-8 일반 긍정 버튼 문맥(T1·T2)**: '확인·예·네·계속·동의·진행·다음·OK' 버튼은 3단계 위 조상 서브트리에 '결제·구매·송금·주문·예매·확정·pay'가 있으면 `CONFIRM_ANCESTOR_DELEGATE`. 문서에 명시된 규칙은 아니며, T2('결제를 계속하려면 확인')를 막기 위한 해석.
- **I-9 결제 화면**: 창 안 어느 노드든 '카드번호·결제비밀번호·결제수단선택·할부개월·카드사선택·카드유효기간'을 포함하면 결제 화면으로 보고 모든 액션을 `CONFIRM_NODE_TEXT`(signal `payment_screen`)로 차단. '결제금액'은 좌석 화면에도 나올 수 있어 제외(M6).
- **I-10 IME 엔터**: 화면(포그라운드 앱 창)에 클릭 가능한 확정 노드가 하나라도 있으면 차단.
- **I-11 급감(T10)**: 기준선은 (패키지, 화면 라벨)별 최근 5개 중앙값. 노드 수·상호작용 노드 수가 **둘 다** 0.4배 미만이면 차단, 기준선 < 20이면 판정 안 함. 차단된 스냅샷은 기준선에 넣지 않는다. 문서의 '600ms 지속'은 gate가 시간 상태를 갖지 않도록 **호출자(agent-core) 정책**으로 넘겼다: gate는 매번 차단하고, 호출자가 로딩 보류 상한(5초 가설) 안에서 재시도하다 넘으면 인계. checkAfter에서 새 화면이 미분류(`screen == null`)면 직전 화면과 직접 비교.
- **I-12 잠금**: checkAfter에서 확정 계열(CONFIRM_*)·`PACKAGE_NOT_ALLOWED`·실행 후 패키지 전환(`FAIL_CLOSED_PACKAGE_MISMATCH`)이 나오면 gate가 잠기고, 이후 모든 check가 같은 사유로 차단된다. `unlockByUser()`는 사용자가 우리 UI에서 명시적으로 넘겨받은 뒤에만 app이 호출한다. 그 외 fail-closed(빈 트리·급감·스크린샷 오류·UNKNOWN)는 잠그지 않는다(로딩 중 일시 상태일 수 있음).
- **I-13 스크린샷 오류**: `Snapshot.screenshotError`가 null이 아니면 코드와 무관하게 차단(3은 `retryable` 신호). 재시도는 service가 한다.
- **I-14 빈 트리**: 포그라운드 앱 창 루트가 없거나, 라벨(text·desc·hint·viewId)도 클릭·입력 가능 노드도 하나도 없으면 빈 트리(T6 WebView 결제 = T9).
- **I-15 포그라운드 창 없음**: APPLICATION 창이 하나도 활성이 아니면 `FAIL_CLOSED_PACKAGE_MISMATCH`, 창 목록이 비면 `FAIL_CLOSED_EMPTY_TREE`.
- **I-16 증명 로그 실패**: sink가 예외를 던지면 결정과 무관하게 `FAIL_CLOSED_INTERNAL_EXCEPTION`(로그 없이 실행하면 M7을 증명할 수 없음).
- **I-17 T7**: VLM 단조성 테스트는 VLM 도입(P2) 시 작성. 현재 gate API에는 모델이 정지를 해제할 입력 자체가 없다.
- **I-18 Android 빌드 설정**: compileSdk·targetSdk 36(Android 16), minSdk 34(D38), AGP 8.13.0. 클라우드에서 미검증 → 팀 PC에서 첫 빌드 시 확인.
- **I-19 data-sensitive 노드에 제스처 금지(D2)**: 아직 미구현. 실기기에서 false 서비스에는 노드가 보이지 않으므로 우선순위 낮음. 목업 스위치 작업(⑦) 때 추가 검토.

## 2회차 (2026-10-23)

- **I-20 screen_label ≠ Snapshot.screen**: 덤프의 `screen_label`은 사람이 붙인 정답 라벨(분류기 정확도 측정용, 측정 시트 A '분류 정답')이고, `Snapshot.screen`은 분류기 결과다. 로더는 `screen`을 비워 두고 라벨은 `SnapshotMeta.screenLabel`에 둔다.
- **I-21 오라클 화면**: 분류기(④) 전에는 `ReplayDevice(oracleScreens = true)`로 정답 라벨을 `screen`에 넣어 gate·실행 경로만 시험한다. ④ 이후 재생 측정(M3·M4·M6·M7)은 `false` + 실제 분류기로 해야 한다(분류 오류가 결과에 반영되도록). → ④-a에서 `ClassifiedReplayTest`가 실제 분류기 경로를 시험한다.
- **I-22 포맷 v0 세부**: D34 원자료의 v0 예시를 따르되 ① 창에 `pkg`를 두고 없으면 루트 노드 `pkg`를 쓴다 ② 최상위 `ts`(ms)·`screenshot_error`를 추가 ③ 노드 `children`이 없으면 `parent` 역참조를 목록 순서로 쓴다 ④ `//`로 시작하는 줄은 주석 ⑤ 쓰기는 기본값(false·빈 값)을 생략한다. 노드 id는 덤프 안 번호일 뿐이고, gate의 NodeRef 키(인덱스 경로)는 트리 모양에서 다시 계산된다. 구조 오류(없는 자식·중복 id·순환·고아 노드)는 로드 실패로 처리한다: 조용히 잘린 트리는 gate가 확정 버튼을 못 보는 원인이 된다. 목업 픽스처는 `tools/fixtures/mock/gen_mock.py`로 만든 **합성** 데이터다.
- **I-23 전이 테이블 의미**: 선택자는 대상 노드 또는 그 자손 중 하나와 맞으면 일치(클릭 가능한 컨테이너 안 텍스트, G5 구조). `tap_center`는 click 전이를 공유한다. 테이블에 없는 클릭·스크롤은 화면을 바꾸지 않고 성공으로 기록(`no_transition`). 낡은 NodeRef(treeHash 불일치)는 장치가 false를 돌려준다.
- **I-24 M7 위반 판정**: `never_click` 선택자에 맞는 노드에 raw 클릭(중심 탭 포함)이 **장치까지 닿으면** 클릭 성공 여부와 무관하게 위반이다. `never_reach` 스냅샷(S4 결제)에 도달해도 실패. 결함은 3종(click_fails·empty_after_action·screenshot_error)만 먼저 넣었고 ④에서 7종으로 늘린다. 확률 결함만 seed 난수를 쓴다.
- **I-25 테스트 이름과 로케일**: 한글 백틱 테스트 이름 안에서 reified inline 함수(`assertThrows<T> {}`)를 쓰면 한글이 든 클래스 파일이 생겨, 로케일이 POSIX인 환경(LANG 미설정)에서 Kotlin 컴파일러가 실패한다. `assertThrows(T::class.java) {}`를 쓴다.
- **I-26 화면 라벨 = 상세 ① 4절 enum**: 목업 픽스처 `screen_label`을 enum 이름으로 맞췄다(SEARCH_FORM·RESULT_LIST·DETAIL·PAYMENT_GATE·POPUP_KNOWN). enum에 좌석 화면이 없어 S3(좌석 자동배정+예매하기)는 **DETAIL**로 둔다. 코레일+에서 좌석과 예매하기가 같은 화면인지는 미확인(상세 ⑤).
- **I-27 화면 분류기(④-a)**: 패키지 → must_have 전부 → must_not_have 없음 → 점수 = must_have 개수 + 가중치 합. 1·2위 차 ≤ 0.5(가설)면 UNKNOWN. 포그라운드 패키지가 어떤 서명에도 없으면 FOREIGN_APP, 루트 없음·창 없음은 UNKNOWN. 텍스트 비교는 gate의 `LabelNormalizer`를 그대로 쓰고, hint는 text가 비었을 때만 본다(값이 들어간 입력칸은 hint가 안 보이는 실기기 동작과 맞춤). 안 보이는(`visible=false`) 노드는 무시. 서명은 대상 앱별로 `app-scenarios`에 두고(목업은 `MockKorail`), 재생 하니스(tools)가 app-scenarios에 의존한다.

## 3회차 (2026-10-23)

- **I-28 DSL 모양**: 상세 ① 4절의 click/type/wait_for/back/expect에 `ask`(ASK_USER 큰 버튼)와 `finish`(정지 지점)를 더했다. expect는 `wait_for(화면, 0)`(로딩 보류만). 시나리오는 `finish`로 정확히 한 번 끝나야 만들어진다: 정지 지점 없는 시나리오는 구조적으로 불가. 대상 노드는 슬롯 람다(`(Slots) -> NodeMatcher`)라 사용자가 고른 열차 이름 같은 값으로 찾는다. 노드 → 클릭 대상은 '자신 또는 가장 가까운 클릭 가능 조상'이고 **정확히 하나**일 때만 누른다(0개·여러 개면 그 시도는 실패로 보고 사다리로).
- **I-29 복구 사다리 해석**: 한 단계 안에서 ① pre-check(화면 = on)이 맞으면 실행 → post-check(then 화면, 기본 5초·조회 15초) ② 실패하면 같은 단계 재시도 1회(총 2회) ③ POPUP_KNOWN이면 팝업 사전으로 닫기(최대 2회, 시도 횟수에 안 셈) ④ back 1회 ⑤ 정지·안내(`ladder_exhausted`). 시나리오 중간 화면에서 시작해도 ④ 때문에 첫 단계 화면으로 되돌아간다: 검색 조건을 넣지 않은 결과 화면에서 이어 가지 않기 위해서다. UNKNOWN 화면에서는 back도 gate가 막으므로(I-4) 아무것도 누르지 않고 정지한다.
- **I-30 로딩 보류·HANDOFF**: UNKNOWN·FOREIGN_APP·스크린샷 오류(6 외)는 `loadingHoldMs`(5초 가설)까지 폴링(300ms)하며 기다린다(I-11의 '600ms 지속'을 호출자 정책으로 둔 것의 구현). 그래도 빈 트리면 HANDOFF ①a(LOADING). ①b는 포그라운드 창에 보이는 data-sensitive 노드, ②는 스크린샷 오류 6, ③은 화면 SENSITIVE_INPUT 또는 LOGIN(비밀번호는 사람이 넣는다). HANDOFF 중 `tick`은 관측만 하고, 트리거가 사라지고 분류되는 화면이 시나리오에 있으면 그 단계로 복귀, 5분이 지나면 `handoff_timeout`으로 정지. FOREIGN_APP은 보류 뒤에도 그대로면 사다리로 간다(EXTERNAL_APP_HANDOFF는 RQ-28 대기).
- **I-31 재탐색**: PAUSED_FOR_USER 복귀와 HANDOFF 복귀 모두 `Flow.relocate(화면)` = 그 화면에서 시작하는 **가장 앞** 단계로 간다. 앞 단계의 type·ask는 이미 된 값이면 건너뛰므로 반복되지 않는다. 직전 화면과 같으면 그 단계 그대로.
- **I-32 일시정지**: `pauseForUser()`는 다른 스레드에서 불러도 되고, 진행 중 단계는 **다음 제출 직전**이나 다음 폴링에서 멈춘다(이미 나간 액션 1건은 되돌리지 않는다). PAUSED_FOR_USER 중 `tick`은 아무것도 하지 않는다. 우리 패키지는 gate 허용목록에 없어서 우리 Activity가 앞에 있으면 어차피 gate가 막는다(D39 이중).
- **I-33 gate 차단 처리**: 확정 계열·PACKAGE_NOT_ALLOWED·FAIL_CLOSED_PACKAGE_MISMATCH 차단은 gate가 잠그므로(I-12) 재시도 없이 바로 `gate:사유`로 정지. 그 외 fail-closed는 다음 관측에서 다시 판단한다(빈 트리 뒤 화면이 이미 넘어갔으면 성공으로 본다). 상태머신도 누르기 직전 대상·자손 라벨을 `ConfirmMatcher`로 보고 확정 라벨이면 제출하지 않는다(`flow_targets_confirm`, gate와 이중 방어). 이 사전 검사는 gate의 문맥 규칙(I-8)까지는 하지 않는다 — 그건 gate 몫이고 테스트로 확인했다.
- **I-34 D17 한도(일 5건·조회 3회·간격 3초)**: 아직 상태머신에 넣지 않았다. 실서비스 실행 횟수는 앱(⑧)의 실행 기록으로 세는 것이 단순해서 ⑧에서 다룬다.

## 4회차 (2026-10-23)

- **I-35 결함 주입 7종의 모양**: D36의 7종(노드 0·70% 삭제·스켈레톤·패키지 변경·서명 훼손·스크린샷 오류·이벤트 순서)을 모두 **액션 직후 관측만 바꾸는 일시 결함**으로 구현했다. 장치의 실제 상태 전이는 그대로 일어나고, `captures`번의 capture에만 걸리며, 다음 액션이 오면 남은 결함은 버린다. ① 70% 삭제는 '루트 외 노드 각각을 0.7 확률로 서브트리째 삭제'(결함을 걸 때 한 번 뽑아 같은 결함 동안 같은 잘린 트리) ② 스켈레톤은 구조·id·bounds를 두고 text·desc·hint·state_desc·pane_title을 비움 ③ 서명 훼손은 viewId 이름을 `x+해시`로 바꾸고(앱 업데이트·난독화) 지정하면 텍스트 하나도 지움 ④ 이벤트 순서는 '창 변경 이벤트가 늦게 와서 액션 전 트리를 다시 읽는' 경우로 해석. 관측이 바뀐 트리는 treeHash가 달라서, 그 트리로 만든 NodeRef는 장치에서 `stale_ref`로 실패한다(실기기에서 노드 refresh 실패와 같음).
- **I-36 하니스 판정**: 실행마다 gate·장치·러너를 새로 조립해 seed만 바꾼다(gate 잠금·급감 기준선이 실행 사이에 섞이지 않게). 사람 흉내는 최소: ASK_USER는 첫 보기(CLI는 열차 슬롯을 미리 채움), HANDOFF는 아무것도 안 하고 시간만 흘림. 판정 = DONE / SAFE_STOP / WRONG_DONE / VIOLATION, M7 실패는 VIOLATION만. 보고는 완료율 Wilson 95% 하한과 위반 Wilson 95% 상한(D50의 0/100 → 3.7%와 같은 식)·rule of 3. 같은 시나리오 반복은 iid가 아니므로(검증 지적) 이 수치는 **로직 층·수작업 스냅샷** 결과로만 보고하고, 고유 (화면, 발화, 앱 버전) 조합 수 n은 따로 센다.
- **I-37 슬롯 v1 세부(D30 해석)**: ① 1단계 `{intent, app, reason}`, reason은 자유 텍스트가 아니라 `unsupported_app·out_of_scope·unclear` 열거값(모델 출력에 화면에 보일 문장이 없게). 사유 없는 unknown은 unclear로 받는다 ② train_book은 app이 null이면 korail_plus로 채우고 다른 앱이면 거부, 병원 intent에 korail_plus면 거부 ③ 2단계 기차 `{origin, destination, date, date_rel, time_from, train_type, adult, senior, missing}`, 병원 `{department, date, date_rel, time_from, missing}`. 상대 날짜는 모델이 `date_rel`(today·tomorrow·day_after_tomorrow) 열거값으로만 내고 날짜 계산은 앱 코드(`resolveDate`). date가 있으면 date 우선 ④ 역은 코레일+ 화면 표기(공백 없음) 목록 54개 초안, 진료과는 10개 초안 — 10-27·10-28 덤프로 보정 ⑤ 스키마는 모든 키를 required로 두고 검증기도 빠진 키를 거부한다(잘린 JSON #3854을 확실히 잡기 위해) ⑥ `missing[]`은 모델 보고일 뿐이고 되묻기 대상은 코드가 `requiredMissing()`으로 다시 계산한다(기차: 출발·도착·날짜).
- **I-38 llm 입력 경계**: `SlotParser.parse(발화)` 하나뿐이고 화면 텍스트를 받을 인자가 없다(D32, D18 ①). 발화는 제어 문자 제거·공백 접기·200자 제한(#3854 KV 캐시)만 하고 내용은 바꾸지 않는다. 형식이 틀린 출력에서 JSON을 '찾아 살리지' 않는다(코드 펜스·설명문 포함 = 거부): 살리면 형식 탈취(T3)와 정상을 구분할 수 없다. 시도 기록(`ParseAttempt`)에는 사유 코드·토큰 수·시간만 남기고 발화·출력 원문은 남기지 않는다. D18 T1~T8 중 llm 쪽은 '탈취된 출력이 실행 가능한 값이 되지 못함'으로 시험했고(T6·T7은 결과 타입에 화면·정지 해제 필드가 없음을 확인), 화면 쪽은 gate 테스트가 맡는다.
- **I-39 워치독·LiteRT-LM 어댑터**: 워치독은 생성을 전용 스레드에서 돌리고 호출 스레드가 시간(10초)을 지킨다. 토큰(256, sink 호출 수 근사)·공백 연속 8개(U+2581 포함, #3510)·문자 2,048자에서 끊고, 끊긴 출력은 버린다. 시간 초과 시 `runner.cancel()`. 단계별 요청 토큰 상한은 1단계 64·2단계 192(가설). `LiteRtLmRunner`는 Google Maven 차단으로 **미빌드**이고 `Engine`·`EngineConfig`·`ConversationConfig`·`SamplerConfig`·`ResponseFormat.json`·`sendMessageAsync(Flow)` 이름은 가이드·이슈 예시 기준 가정 — 10-26 팀 PC에서 0.18.0 AAR로 컴파일해 맞춘다. 요청마다 새 Conversation, temperature 0·topK 1·seed 7, thinking·추측 디코딩은 켜지 않는다.

## 5회차 (2026-10-23)

- **I-40 모듈 추가·분리**: D26에 없는 `:snapshot`(JSONL v0 코덱)을 만들었다. 덤퍼(service)와 재생기(tools)가 같은 포맷을 써야 하는데, service가 tools(agent-core·app-scenarios까지 끌어옴)에 의존하는 것은 방향이 거꾸로라서다. 또 `:service`를 `:service:core`(순수 Kotlin)와 `:service:android`로 나눴다(I-1의 llm과 같은 이유: Android 없이 테스트).
- **I-41 트리 캡처**: `AccessibilityNodeInfo`는 `A11yNode` 보기로만 다룬다. 창마다 노드 3,000개·깊이 128을 넘거나, 순회 중 자식이 null(사라짐)이거나 예외가 나면 **그 창의 루트를 통째로 비운다** — 잘린 트리는 확정 버튼을 못 본 채 Allow가 날 수 있지만, 빈 트리는 gate가 fail-closed로 막고 상태머신이 로딩 보류로 다시 본다(I-14·I-30). 한도는 가설이며 10-27 노드 수 분포(W05)로 보정. 비밀번호 칸(`isPassword`)의 text는 스냅샷에 넣지 않는다. 스크린샷 오류 코드는 아직 넣지 않는다(`screenshotError = null` = 미측정, takeScreenshotOfWindow 연결은 W07 실측과 함께).
- **I-42 RawActuator 구현의 확인 범위**: `FrameActuator`는 판단하지 않지만 '누르는 노드가 gate가 본 그 노드인가'는 확인한다. ① ref의 treeHash = 마지막 캡처 창의 treeHash ② `refresh()` 성공 ③ refresh 뒤 노드 필드(자식 제외)가 캡처 때와 같음. 하나라도 어긋나면 실행하지 않고 false(낡은 참조) → GatedExecutor는 CLICK이면 중심 탭 폴백을 gate에 다시 묻고, 그것도 같은 확인에 걸린다. 캡처와 실행 사이에 같은 자리 버튼이 '예매하기'로 바뀌는 행동 재바인딩(D18 ④)을 실기기 쪽에서도 막는다. 중심 탭은 refresh한 노드 bounds 중심만, 빈 bounds면 하지 않는다.
- **I-43 덤프 규칙**: 화면 변경 이벤트 800ms 디바운스 뒤 1회 캡처, 포그라운드 앱이 덤프 허용목록(코레일+·목업, 병원 앱은 10-28 추가)일 때만, 마스킹 후 포그라운드 창 treeHash가 직전과 다를 때만, 세션당 500줄까지. `snap_id = s{yyyyMMdd_HHmmss}_{순번}`, `screen_label`은 비움(I-20), `one_ui`는 공개 API가 없어 null(측정 시트에 사람이 적음). 덤프 파일은 앱 내부 `filesDir/dump.jsonl`(run-as로 꺼냄)이라 다른 앱이 읽을 수 없다.
- **I-44 기기 안 1차 마스킹**: 상세 문서의 마스킹 규칙(RQ 마스킹·보존 규칙)이 아직 정리 전이라 가장 단순하게: 입력칸·data-sensitive 노드의 text·desc·state는 전체 `[MASK]`, 나머지 문자열은 이메일·주민번호·카드번호·휴대폰·8자리 이상 숫자열만 `[MASK]`. viewId·hint·역 이름·시각·요금·호차/좌석은 남긴다(분류기·gate가 써야 함). 이름·주소는 못 잡으므로 **커밋 전 사람 검토는 그대로 필요**. 마스킹 규칙이 정리되면 여기와 `DumpMasker`를 고친다.
- **I-45 목업 앱**: 레이아웃 XML·AndroidX 없이 플랫폼 위젯으로 한 Activity 안에서 화면을 바꾼다(의존성·미빌드 위험 축소). viewId는 `res/values/ids.xml`로 정의해 접근성 `viewIdResourceName`이 픽스처와 같은 `capstone.mock:id/…`가 되게 했다. 로딩 화면(라벨 없는 ProgressBar)을 S1→S2 사이에 넣어 빈 트리 보류를 실기기에서 시험한다. 변형 스위치 5개(팝업·로딩 시간·예매하기 모양 4종·빈 결제 화면·전부 매진)는 인텐트 extra. 목업의 '예매하기'는 S4로 넘어가며, S4 도달 자체가 M7 실패 신호다. targetSdk 36은 예측형 뒤로가기가 기본이라 `OnBackInvokedCallback`을 쓴다.

## 6회차 (2026-10-23)

- **I-46 `:app-core` 분리**: app의 판단(고지·동의 기록, D17 일일 한도, 슬롯 채우기·복창·실행 상태 → 화면)을 순수 Kotlin `:app-core`에 두고, Android `:app`은 그리기·조립(서비스·gate·러너·LLM·알림)만 한다(I-1·I-40과 같은 이유). `:app`의 경로를 바꾸지 않으려고 `:app:core`가 아니라 형제 모듈로 뒀다. `AppSession`은 메인 스레드에서만 부른다고 가정하고(Android Activity), 러너는 `AgentHost`가 작업 스레드에서 돌린다. LLM 파싱도 작업 스레드에서 하고 결과만 `onParsed`로 넘긴다.
- **I-47 D17 한도의 자리**: ① 조회 3회·간격 3초는 '1건 안'의 규칙이라 상태머신이 센다(`Step.Click.query`, `RunnerConfig.maxQueries`·`minQueryGapMs`). 재시도로 다시 누른 조회도 센다. 넘으면 `limit:queries`로 정지. ② 일 5건은 앱이 센다(`DailyRunLimiter`): '건' = 복창 확인 버튼으로 시작한 실행 1회, 하루 = KST 자정, **기기별**(팀 합계는 기기에서 셀 수 없으므로 측정 시트로 맞춘다). 목업 시나리오(`Scenario.liveService = false`)는 세지 않는다. 병원 탐색 3회/일(D52)은 병원 시나리오가 생길 때 같은 방식으로 따로 센다.
- **I-48 고지·동의(D25)**: 상세 ⑤의 필수 요소(읽는 데이터·사용 방식·외부 전송 없음·이유·명시적 동의·알림·비tool 알림 미리 안내)를 5절 문구 초안으로 썼다(RQ-56이 아직 비어 있어 가장 단순한 고령층 문장). 동의는 '동의하고 시작' 버튼을 눌러야만 기록되고(기본 동의 없음), 기록은 버전·시각만 앱 내부 저장소(`allowBackup=false`)에. 문구를 바꾸면 `Disclosure.VERSION`을 올려 다시 묻는다. 거부하면 아무 기능도 쓰지 않고 고지 화면에 남는다. POST_NOTIFICATIONS는 동의 직후 묻는다. AAPM 감지·Auto Blocker 확인(D10·D55)은 아직 없다(W09 실측과 함께).
- **I-49 버튼 기반 흐름**: '버튼으로 찾기'는 기차 예매로 고정하고 출발 → 도착 → 날짜(오늘·내일·모레)를 큰 버튼으로 묻는다. 역 보기는 화면 한 장에 들어가는 주요 8역(서울·용산·수원·대전·동대구·부산·광주송정·강릉, 가설)이고 54역 전체는 말로 고른다. 발화로 일부만 오면 빠진 필수 슬롯(코드가 `requiredMissing()`으로 계산)만 버튼으로 묻는다. 발화 실패는 1번째 '한 번 더', 2번째 '버튼으로 찾기' 안내(D16). 모든 문장은 검증된 슬롯으로 코드가 만들고, 러너 정지 사유 코드는 화면에 내지 않고 고정 문장으로 바꾼다. 실행 중 ASK_USER는 우선 우리 Activity의 큰 버튼으로 묻는다(오버레이 큰 버튼은 service 쪽 작업, 10-28 W02와 함께).
- **I-50 일시정지 직후 재개**: 러너가 한 번도 화면을 보기 전에 멈췄다가 계속하면, 비교할 직전 서명이 없으므로 재탐색 없이 그 단계의 pre-check·복구 사다리에 맡긴다(전에는 UNKNOWN과 비교해 `resume_mismatch`로 정지했다). 화면을 본 뒤의 일시정지는 그대로 서명 비교 → 재탐색(I-31).
- **I-51 실행 조립(AgentHost)**: 실행마다 gate를 새로 만들고 허용목록은 그 시나리오의 `Flow.packages`, 우리 패키지는 `ownPackage`로 넣어 우리 화면이 앞에 있으면 모두 막힌다(D39). gate 판정은 `filesDir/gate.jsonl`에 JSONL로 쌓는다(M7 증명 로그, run-as로 꺼냄). 화면 분류기는 지금 목업 서명(`MockKorail`)으로 고정 — 코레일+ 서명이 생기면 `Scenario`에 분류기를 붙인다.

## 7회차 (2026-10-23)

- **I-52 마스킹 린트 범위(D58)**: '커밋 전 CI 정규식 린트'를 `tools mask-lint`로 구현했다. 대상은 데이터 확장자(.jsonl·.json·.csv·.tsv·.txt·.log)만이고 코드·문서는 보지 않는다(규칙 설명에 예시 번호가 들어가므로). 생년월일은 '1900~2009년 날짜'로 좁혔다(열차 날짜 2026-…·타임스탬프 오탐 방지). '실병원명 사전'은 아직 사전이 없어 `…병원/의원/한의원/치과/요양원/클리닉` 패턴으로 대신하고, D58 치환 접두어(테스트·목업·가짜·예시·샘플)로 시작하면 통과. 이메일은 example.com 계열만 통과. 결과에 값은 찍지 않는다(CI 로그 공개). 실제 병원명 사전이 생기면 패턴 대신 사전으로 바꾼다.
- **I-53 본인 인증 = USER_AUTH HANDOFF**: 상세 ⑥ 10-26 합의 안건('인증 단계는 USER_AUTH 상태로 사람에게, 에이전트 조작 0')을 새 상태가 아니라 HANDOFF의 트리거(`HandoffTrigger.USER_AUTH`)로 넣었다: 조작 0·복귀 규칙·5분 안전망이 HANDOFF와 같기 때문. 화면 이름 `Screens.USER_AUTH`는 앱별 서명이 붙어야 동작한다(코레일+ 서명은 비회원 녹화 뒤). 외부 인증 앱으로 넘어가면 지금은 FOREIGN_APP → 로딩 보류 → 복구 사다리이고 gate가 패키지로 막으므로 누르지는 않는다. 인증 앱 패키지를 서명에 넣어 USER_AUTH로 분류할지는 녹화(외부 인증 앱 전환 여부) 뒤 정한다. 5분 HANDOFF 상한이 인증에 충분한지도 같이 본다.
- **I-54 SRT 걸러내기**: 안건 8 기본값('걸러냄', D4·D17)을 목업 시나리오의 열차 보기에서 구현했다. 라벨을 gate 정규화로 바꿔 'srt'로 시작하면 뺀다. 열차 슬롯은 사용자가 보기에서 고르므로 보기에서 빼면 충분하다고 봤다(LLM 슬롯 v1에는 열차 이름이 없다). 코레일+ 결과 행에 SRT가 어떻게 표기되는지는 녹화로 확인.
- **I-55 D57 컨텍스트 2048**: `LiteRtLmRunner`의 `EngineConfig`에 `maxNumTokens = 2048`을 넣었다(이름은 가정, 미빌드 — I-39와 함께 10-26 컴파일로 맞춤). ExperimentalFlags off/on A/B와 `litert-lm-peek`는 C의 실측 절차라 코드에 넣지 않았다.
- **I-56 1주차 계획과 코드 대조**: 결과는 [`PLAN-CHECK.md`](PLAN-CHECK.md). 코드로 할 수 있는 불일치는 이번 회차에 고쳤고, 남은 것은 실측·합의가 필요한 항목이다.

## 8회차 (2026-10-23)

- **I-57 미리 채운 보기 슬롯도 화면 보기로 검증**: 전에는 `ask` 단계가 '슬롯에 값이 있으면 끝'이라, 발화·CLI로 미리 채운 열차가 SRT(안건 8)이거나 매진이면 보기 거르기를 건너뛰고 그 행을 누르러 갔다(I-54의 '보기에서 빼면 충분' 가정의 구멍). 이제 그 화면에서는 값이 지금 보기에 있을 때만 끝난 것으로 보고, 없으면 같은 보기로 다시 묻는다(값은 `answer`가 덮어씀). 다른 화면에 있을 때(재탐색 중 앞 단계 건너뛰기)는 그대로 둔다. 재생기 픽스처 `mock-korail-srt.jsonl`·`srt.json`(SRT 2행 섞인 결과)로 시험했다.
- **I-58 이벤트 필터·허용목록 밖 비순회(D63 ②)**: `packageNames`는 비운 채 `EventFilter`가 거른다 — 대상 앱 이벤트와 패키지가 null인 TYPE_WINDOWS_CHANGED만 캡처를 부르고, 나머지는 내용을 읽지 않고 버린다(전에는 WINDOWS_CHANGED가 패키지 null이라 항상 탈락했다). `TreeCapture(targetPackages)`는 허용목록 밖 앱 창(키보드·시스템 UI·다른 앱·우리 앱)의 **루트 노드 패키지만** 읽고 순회하지 않는다: gate는 포그라운드 창의 패키지로 막으므로 그것으로 충분하다. 서비스의 허용목록은 `DumperService.TARGET_PACKAGES`(코레일+·목업) 하나로 덤프·캡처가 같이 쓰고, 시나리오 `Flow.packages`는 그 안에 있어야 한다. 'accessibilityDataSensitive 이벤트·노드 미수신 = HANDOFF'는 이미 빈 트리 → 로딩 보류 → HANDOFF ①a 경로(I-30)라 코드를 더하지 않았다. 이벤트 누락은 실행 경로에 영향이 없다(액션마다 새로 캡처해 gate에 묻는다).
- **I-59 mask-lint 오탐 줄이기**: 번호 패턴(휴대폰·주민번호·카드·생년월일)은 앞뒤가 숫자뿐 아니라 **영문자**여도 잡지 않는다(16진수 해시·영숫자 id 속 `010…` 오탐). 한글·문장부호와 붙은 번호('연락처01012345678')는 그대로 잡는다. 병원명 패턴은 낱말 전체가 일반 명사(대학병원·종합병원·요양병원·동물병원·전문병원·한방병원·치과병원·상급종합병원·국회의원)이면 통과. 고유 이름이 붙으면('서울대학병원') 잡는다. 미탐을 늘리는 방향이므로 범위는 이 둘로만 좁혔다.
- **I-60 D64 llama.cpp 경로는 claude/dev에 넣지 않음**: D64가 'W13 비교 전까지 실험 브랜치에만'이라 이 루틴의 작업 브랜치(`claude/dev`)에는 `LlamaCppRunner`를 만들지 않았다. `LlmRunner` 인터페이스·`SlotParser`(무제약 생성 → 앱 파서·스키마 검증, D48)는 이미 런타임에 매이지 않으므로 그 경로를 붙일 자리는 있다.

## 9회차 (2026-10-23)

- **I-61 D63 ④(b) 실측은 이벤트 수 집계로**: 'packageNames 지정/비움 수신 이벤트 비교'를 사람이 logcat으로 할 수 있게 `EventTally`가 이벤트를 **종류 × 출처(대상 앱 / 패키지 null / 다른 앱)** 로만 세고, `DumperService`가 30초마다·종료 시 한 줄 찍는다. 다른 앱 패키지 이름은 남기지 않는다(어떤 앱을 쓰는지도 개인정보, D58). 지정 상태 비교는 설정 파일을 임시로 바꿔 설치하는 수동 절차로 두고(FIRST-DAY 3-1), 코드에 '지정 모드' 스위치를 만들지 않았다: 실서비스 경로에 쓰지 않을 분기를 남기지 않기 위해서. (a) BAL startActivity·(c) filterTouchesWhenObscured·FLAG_SECURE는 기기 동작 관찰이라 코드가 필요 없어 이번에 넣지 않았다.

## 10회차 (2026-10-23)

- **I-62 D65 토큰 한도와 LiteRT-LM 이름 대조**: ① 워치독 토큰 상한을 256 → 384로 올렸다(D65 maxOutputToken). 단계별 요청 상한 64·192(I-39)는 그대로다 — 엔진 상한은 요청마다 이 값이 가고, 384는 워치독의 바깥 울타리다. ② 엔진은 출력 상한에 닿아도 에러 없이 끝나므로(D65) 검증기가 `{`로 시작해 짝이 닫히지 않은 출력을 `truncated`로 따로 센다. 동작은 전과 같이 거부 → 재생성 1회 → 되묻기이고, 사유 코드만 갈라 측정 시트 E에서 잘림 수를 볼 수 있게 했다. ③ KV 한도(`maxNumTokens`)는 `ContextBudget`이 `totalMem` 7GiB 이상이면 4096(후보), 아래면 2048로 고른다. '8GB' 기기의 totalMem이 7GiB를 넘는다는 것은 추정 — 첫날 실측으로 확인. ④ D65 원문은 4096이 'UI 트리 포함' 기준이라 했지만, 우리 llm 입력에는 UI 트리가 없다(D32·I-38: 화면 텍스트를 받을 인자 자체가 없음). 그래서 'A34 UI 트리 압축'은 할 일이 없고, 4096이 필요 없을 수도 있다 — 첫날 PSS로 2048 단일화를 검토할 것(팀 확인). ⑤ raw.githubusercontent.com에서 v0.18.0 태그 원본을 읽어 `LiteRtLmRunner`를 고쳤다: `Backend.CPU`는 data class라 `Backend.CPU()`, `systemInstruction`은 `Contents`(→ `Contents.of`), `ThinkingConfig(enableThinking = false)`는 `ConversationConfig` 인자, 시간 초과 때 `Conversation.cancelProcess()`(Flow 수집 취소만으로는 네이티브 생성이 안 멈춤: `callbackFlow`의 `awaitClose {}`가 비어 있음). 여전히 미빌드. Qwen3 `extraContext enable_thinking=false`·`<think>` 제거는 Qwen3를 실제로 올리는 W13 실험 때(D64 실험 브랜치) 붙인다 — 우리 검증기는 JSON 앞의 `<think>`를 지우지 않고 거부한다(I-38).
- **I-63 온보딩 v1(D66)**: 순수 Kotlin `Onboarding`(app-core)이 단계를 정하고 Activity는 그리기만 한다(I-46과 같은 이유). 다음 단계는 사용자 말이 아니라 `DeviceChecks`(알림 권한·서비스 연결·AP)로만 넘어간다. 해석: ① 이미 된 단계(알림 허용·접근성 켜짐)는 건너뛴다 ② '미허용 2회' = 설정에 다녀왔는데 꺼져 있던 횟수 2. 분기 A에서도 다시 시도·제한된 설정 안내로 갈 수 있다(막다른 길을 만들지 않음) ③ 분기 A의 '안내 이미지'는 메뉴 캡처가 아직 없어 글 공유(공유 시트)로 대신한다. 보내는 것은 사용자가 고른 앱이고 우리 앱은 네트워크가 없다 ④ AP는 API 36 미만이면 null(감지 불가)이고 막지 않는다. 켜져 있으면 어느 단계에서든 분기 B, 실행 직전 `startBlocker()`도 같은 판단(D62 알림 없음·접근성 꺼짐 포함) ⑤ 접근성 '켜짐'은 설정 문자열이 아니라 서비스가 실제로 연결됐는지(`DumperService.instance`)로 본다 ⑥ 연습 1회는 목업 시나리오의 버튼 흐름(`startWithButtons`)이고 결제·실서비스 없음 ⑦ 고지 문구를 바꾸면 동의만 다시 묻고 나머지 단계는 건너뛴다. 온보딩 단계가 바뀌면 `DONE_VERSION`을 올린다 ⑧ `AppScreen.Consent`는 지우지 않고 동의 기록이 사라진 경우의 방어선으로 남겼다.
- **I-64 고지 문구 v2(D66 ②)**: '아무 데도 보내지 않습니다'를 '이 앱은 화면 내용을 밖으로 보내지 않아요 … 코레일+ 같은 앱이 스스로 하는 통신은 별개'로 좁혔다(플랫폼 STT·TTS는 다른 프로세스, 기내모드 실측 D31 전). '끄는 법' 절을 더하고 버튼을 '동의합니다'로 바꿨다. `Disclosure.VERSION` 2 → 이전 동의는 다시 묻는다. 실측 통과 뒤 문구를 확정하면 VERSION을 또 올린다.
- **I-65 D67은 코드 변경 없음**: 코레일 약관 확인(팀 직접 확인 #2)은 운영·발표 문구의 문제라 저장소에서 할 일이 없다. D17 한도·D54 '비회원 실패 시 목업'은 이미 코드(`DailyRunLimiter`·목업 시나리오만 연결)와 맞는다.
