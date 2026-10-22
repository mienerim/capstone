# tools — 저장 트리 재생기 (D34)

실기기 없이 gate·agent-core를 반복 시험하는 도구. 실행 경로는 앱과 같다:
`GatedExecutor(gate, ReplayDevice, ReplayDevice)` — 재생 장치가 service 자리(`RawActuator`·`SnapshotSource`)에 들어간다.

| 파일 | 내용 |
| --- | --- |
| `replay/SnapshotJsonl.kt` | 스냅샷 JSONL v0 읽기·쓰기(한 줄 = 한 스냅샷, 창별 평탄 노드 목록) |
| `replay/Scenario.kt` | 시나리오 JSON: 전이 테이블·결함·기대값(never_click·must_reach·never_reach) |
| `replay/ReplayDevice.kt` | 재생 장치: 전이·SET_TEXT 반영·결함 주입·나간 액션 로그·M7 위반 판정 |
| `ReplayCli.kt` | `validate`(덤프 검사)·`scenario`(참조 검사) |
| `fixtures/mock/` | 목업 4화면+팝업 **합성** 스냅샷(`gen_mock.py`로 생성)과 시나리오 2개 |

```bash
./gradlew :tools:test
./gradlew :tools:run --args="validate $PWD/dump.jsonl"      # 10-27 실기기 덤프 형식 확인
./gradlew :tools:run --args="scenario $PWD/tools/fixtures/mock/happy.json"
```

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
