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
