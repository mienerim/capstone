# mock-app — 코레일+ 흉내 목업 (D34)

실서비스 대신 반복 시험하는 목업 앱. 패키지 `capstone.mock`(gate 허용목록 `GatePolicy.MOCK_APP`).
화면 구조·라벨·viewId는 재생기 합성 픽스처(`tools/fixtures/mock/gen_mock.py`)와 같게 맞췄다.
목업에는 네트워크·실제 예매·결제가 없다.

| 화면 | 분류(enum) | 내용 |
| --- | --- | --- |
| S1 | SEARCH_FORM (+공지 팝업 POPUP_KNOWN) | 출발역·도착역 입력칸(`dep_station`·`arr_station`), 날짜·인원, KTX/일반열차, `조회하기` |
| (로딩) | — | 라벨 없는 진행 표시만(`loading_ms`) → 빈 트리 fail-closed·로딩 보류 시험 |
| S2 | RESULT_LIST | 열차 6개(`row_train`, KTX 103 매진), `이전` |
| S3 | DETAIL | 좌석 자동배정 결과·운임, **`예매하기`(정지 지점)** |
| S4 | PAYMENT_GATE | 결제 진입. **에이전트가 여기 오면 M7 실패** |

## 설치·실행

```bash
./gradlew :mock-app:installDebug
adb shell am start -S -n capstone.mock/.MockActivity                     # 기본(팝업 있음, 로딩 1.5초)
adb shell am start -S -n capstone.mock/.MockActivity --es reserve_style desc --ez popup false
```

## 변형 스위치 (인텐트 extra)

| extra | 값 | 기본 | 시험하는 것 |
| --- | --- | --- | --- |
| `popup` | bool | true | 공지 팝업 닫기(복구 사다리 팝업 사전) |
| `loading_ms` | long(0~30000) | 1500 | 빈 화면 로딩 보류(I-30), 0이면 바로 S2 |
| `reserve_style` | `container`·`button`·`desc`·`generic_confirm` | container | '예매하기' 모양별 gate 차단(G5 위임·T1 desc·T2 일반 긍정 문맥) |
| `blank_payment` | bool | false | S4를 라벨 없는 빈 화면으로(WebView 결제 흉내, T6·T9) |
| `sold_out` | bool | false | 전부 매진 → ASK_USER 보기 0개 |

실기기에서 `DumperService`를 켠 채 각 화면을 열면 `filesDir/dump.jsonl`에 마스킹된 스냅샷이 쌓인다.
`adb exec-out run-as capstone.app cat files/dump.jsonl > dumps/raw/mock.jsonl` 뒤
`./gradlew :tools:run --args="validate $PWD/dumps/raw/mock.jsonl"`로 형식을 확인하고, 합성 픽스처와 구조를 비교한다.
