# 목업 합성 스냅샷 생성기. 사용: python3 tools/fixtures/mock/gen_mock.py tools/fixtures/mock/mock-korail.jsonl [tools/fixtures/mock/mock-korail-srt.jsonl]
import json, sys
PKG = "capstone.mock"
def rid(x): return f"{PKG}:id/{x}"
def N(cls, **kw):
    kw["cls"] = cls; kw.setdefault("children", []); return kw
def text(t, b=None, **kw): return N("android.widget.TextView", text=t, bounds=b, **kw)
def button(t, b, r=None, **kw): return N("android.widget.Button", text=t, bounds=b, rid=rid(r) if r else None, clickable=True, **kw)
def edit(hint, b, r, t=None): return N("android.widget.EditText", hint=hint, text=t, bounds=b, rid=rid(r), clickable=True, editable=True)
def group(*c, b=None, clickable=False, cls="android.widget.LinearLayout", **kw): return N(cls, bounds=b, clickable=clickable, children=list(c), **kw)
W, H = 1080, 2340
def frame(*c): return group(*c, b=[0,0,W,H], cls="android.widget.FrameLayout")

def s1(popup=False):
    body = [
        text("승차권 조회", [0,100,W,220], rid=rid("title")),
        text("결제 예정 금액은 좌석 선택 뒤 안내됩니다", [0,230,W,300], rid=rid("notice")),
        edit("출발역", [40,320,520,440], "dep_station", "서울"),
        edit("도착역", [560,320,1040,440], "arr_station"),
        text("2026-11-03 (화)", [40,460,1040,560], rid=rid("date"), clickable=True),
        text("어른 1명", [40,580,1040,680], rid=rid("passengers"), clickable=True),
        group(button("KTX", [40,700,520,800], "type_ktx"), button("일반열차", [560,700,1040,800], "type_general"), b=[0,700,W,800]),
        button("조회하기", [40,2100,1040,2250], "btn_search"),
    ]
    if popup:
        body.append(group(text("공지사항", [140,800,940,900]), text("11월 열차 시간표가 변경되었습니다.", [140,900,940,1100]),
                          button("닫기", [140,1150,940,1260], "btn_popup_close"), b=[100,760,980,1300], rid=rid("popup")))
    return frame(*body)

def s2(srt=False):
    rows = []
    trains = [("KTX 101","06:00","08:40","예약가능"),("KTX 103","07:00","09:38","매진"),("KTX 105","08:00","10:41","예약가능"),
              ("무궁화호 1201","08:20","13:30","예약가능"),("KTX 107","09:00","11:39","예약가능"),("ITX-새마을 1001","09:30","14:10","예약가능")]
    if srt:
        # 안건 8: 결과에 SRT가 섞여 나오는 경우(코레일+ 실제 표기는 녹화로 확인, I-54). 보기에서 빠져야 한다.
        trains = trains[:1] + [("SRT 301","06:30","08:50","예약가능")] + trains[1:5] + [("SRT 305","09:10","11:30","예약가능")]
    for i,(no,dep,arr,st) in enumerate(trains):
        top = 300 + i*260
        rows.append(group(text(no,[40,top+20,400,top+100]), text(f"{dep} → {arr}",[420,top+20,800,top+100]),
                          text(f"일반실 {st}",[820,top+20,1040,top+100]), b=[0,top,W,top+240], clickable=(st!="매진"), rid=rid("row_train"),
                          enabled=(st!="매진")))
    return frame(text("서울 → 부산 2026-11-03", [0,100,W,220], rid=rid("title")),
                 group(*rows, b=[0,280,W,1900], cls="androidx.recyclerview.widget.RecyclerView", scrollable=True, rid=rid("list")),
                 button("이전", [40,2100,1040,2250], "btn_back"))

def s3():
    return frame(text("좌석 자동배정 결과", [0,100,W,220], rid=rid("title")),
                 text("KTX 105  08:00 → 10:41", [40,260,1040,340]),
                 text("3호차 12A (일반실, 순방향 창측)", [40,360,1040,440], rid=rid("seat")),
                 text("운임 59,800원", [40,460,1040,540], rid=rid("price")),
                 button("이전", [40,2100,520,2250], "btn_back"),
                 group(text("예매하기", [600,2140,1000,2210]), b=[560,2100,1040,2250], clickable=True, rid=rid("btn_reserve")))

def s4():
    return frame(text("결제", [0,100,W,220], rid=rid("title")),
                 text("총 결제금액 59,800원", [40,260,1040,340]),
                 text("결제수단 선택", [40,360,1040,440]),
                 edit("카드번호", [40,460,1040,580], "card_no"),
                 button("결제하기", [40,2100,1040,2250], "btn_pay"))

def flatten(root):
    nodes=[]
    def walk(n, parent, depth):
        i=len(nodes); d={"id":i,"parent":parent}
        nodes.append(d)
        kids=[walk(c,i,depth+1) for c in n["children"]]
        if kids: d["children"]=kids
        for k in ["cls","rid","text","desc","hint"]:
            if n.get(k) is not None: d[k]=n[k]
        if n.get("bounds"): d["bounds"]=n["bounds"]
        for k in ["clickable","editable","scrollable"]:
            if n.get(k): d[k]=True
        if n.get("enabled") is False: d["enabled"]=False
        return i
    walk(root,-1,0); return nodes

def snap(sid, label, root, ts):
    return {"v":0,"snap_id":sid,"app":"mock","app_version":"0.1","sdk":36,"a11y_tool_flag":False,"screen_label":label,"ts":ts,
            "windows":[{"id":1,"type":"APPLICATION","pkg":PKG,"active":True,"focused":True,"root":0,"nodes":flatten(root)},
                       {"id":2,"type":"SYSTEM","pkg":"com.android.systemui","root":0,"nodes":[{"id":0,"parent":-1,"cls":"android.widget.FrameLayout","text":"09:41","bounds":[0,0,W,80]}]}]}

out=[snap("mock_s1_popup","POPUP_KNOWN",s1(True),0), snap("mock_s1_search","SEARCH_FORM",s1(),1000), snap("mock_s2_results","RESULT_LIST",s2(),2000),
     snap("mock_s3_seat","DETAIL",s3(),3000), snap("mock_s4_payment","PAYMENT_GATE",s4(),4000)]
def write(path, header, snaps):
    with open(path,"w",encoding="utf-8") as f:
        f.write(header)
        for s in snaps: f.write(json.dumps(s, ensure_ascii=False, separators=(",",":"))+"\n")

write(sys.argv[1], "// 목업 4화면(+공지 팝업) 합성 스냅샷. 실기기 덤프가 아니다. 생성: gen_mock.py (DECISIONS-IMPL I-22)\n", out)
if len(sys.argv) > 2:
    write(sys.argv[2], "// 목업 결과 화면에 SRT가 섞인 변형(안건 8, I-54·I-57). 실기기 덤프가 아니다. 생성: gen_mock.py\n",
          [snap("mock_s2_results_srt","RESULT_LIST",s2(srt=True),2000)])
