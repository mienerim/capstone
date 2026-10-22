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
