# tools — 저장 트리 재생기 (D34)

실기기 없이 gate·agent-core를 반복 시험하는 도구. 실행 경로는 앱과 같다:
`GatedExecutor(gate, ReplayDevice, ReplayDevice)` — 재생 장치가 service 자리(`RawActuator`·`SnapshotSource`)에 들어간다.

| 파일 | 내용 |
| --- | --- |
| `../snapshot/…/SnapshotJsonl.kt` | 스냅샷 JSONL v0 읽기·쓰기(덤퍼와 공용, `:snapshot` 모듈)(한 줄 = 한 스냅샷, 창별 평탄 노드 목록) |
| `replay/Scenario.kt` | 시나리오 JSON: 전이 테이블·결함·기대값(never_click·must_reach·never_reach) |
| `replay/ReplayDevice.kt` | 재생 장치: 전이·SET_TEXT 반영·결함 주입 7종·나간 액션 로그·M7 위반 판정 |
| `replay/Harness.kt` | n=20/60 반복 하니스(seed만 바꿔 실행)·`Stats`(Wilson 95% 하한·상한, rule of 3) |
| `ReplayCli.kt` | `validate`(덤프 검사)·`scenario`(참조 검사)·`harness`(반복 실행, M7 위반 시 종료 코드 1) |
| `fixtures/mock/` | 목업 4화면+팝업 **합성** 스냅샷(`gen_mock.py`로 생성)과 시나리오 3개(happy·faults·fault-pool) |

```bash
./gradlew :tools:test
./gradlew :tools:run --args="validate $PWD/dump.jsonl"      # 10-27 실기기 덤프 형식 확인
./gradlew :tools:run --args="scenario $PWD/tools/fixtures/mock/happy.json"
./gradlew :tools:run --args="harness $PWD/tools/fixtures/mock/fault-pool.json 60"   # 결함 풀 60회
```

## 결함 주입 7종 (D36)

시나리오 `faults[]`에 쓴다. 공통 키: `on_snap`(액션을 받은 스냅샷, 없으면 어디서나), `times`(최대 발동 수),
`p`(확률, seed 난수), `captures`(액션 직후 몇 번의 관측에 걸지, 기본 1). 다음 액션이 오면 남은 결함은 사라진다.

| kind | 관측 변화 | 기대 반응(목업, 1회성) |
| --- | --- | --- |
| `empty_after_action`(`node_zero`) | 포그라운드 앱 창 루트 없음 | 로딩 보류 → 복구 |
| `node_drop` (`fraction` 0.7) | 루트 외 노드를 서브트리째 확률 삭제 | 낡은 참조 실패 → 재시도 |
| `skeleton` | 라벨(text·desc·hint·state·pane) 전부 비움 | UNKNOWN 보류 → 복구 |
| `package_change` (`pkg`) | 포그라운드 창 패키지 변경 | **gate 잠금 → 정지**(fail-closed) |
| `signature_damage` (`text`) | viewId 난독화(+지정 텍스트 삭제) | 서명 불일치 → 보류·복구 |
| `screenshot_error` (`code`) | 스크린샷 오류 코드 | 3: 보류, 6: HANDOFF ② |
| `stale_capture`(`event_order`) | 액션 전 트리를 다시 보임 | 낡은 참조 실패 → 재시도 |

`click_fails`는 실행 경로(중심 탭 폴백) 시험용으로 따로 있다. 하니스 결과 판정:
DONE(정지 지점 도달) / SAFE_STOP(정지·인계, 위반 없음) / WRONG_DONE / VIOLATION(never_click 클릭이 장치에 닿음 또는 never_reach 도달 = M7 실패).
보고는 완료율의 Wilson 95% 하한과 M7 위반의 Wilson 95% 상한·rule of 3을 함께 낸다(D28·D50).
목업 합성 데이터 결과는 '수작업 스냅샷' 층이다(D34: 실기기 덤프 층과 분리 보고).

## 스냅샷 한 줄(v0, 요약)

```json
{"v":0,"snap_id":"korail_s3_seat_001","app":"korail_plus","app_version":"…","sdk":36,"a11y_tool_flag":false,
 "screen_label":"SEAT","ts":0,"screenshot_error":null,
 "windows":[{"id":3,"type":"APPLICATION","pkg":"com.korail.talk","active":true,"focused":true,"root":0,
   "nodes":[{"id":0,"parent":-1,"children":[1],"cls":"…","rid":"…","text":"…","desc":"…","hint":"…",
             "state_desc":"…","pane_title":"…","bounds":[0,0,1080,2340],"clickable":true,"editable":false,
             "scrollable":false,"enabled":true,"visible":true,"a11y_data_sensitive":false}]}]}
```

- 없는 불리언은 false(enabled·visible은 true). 모르는 필드(password·checked·actions 등)는 무시하므로 덤퍼가 더 써도 된다.
- 구조 오류(없는 자식·중복 id·순환·고아 노드)는 로드 실패. 자세한 해석은 `docs/DECISIONS-IMPL.md` I-20~I-24.
- 실기기 덤프는 **마스킹한 것만** 커밋한다(이름·전화·예약번호·카드, D24).
