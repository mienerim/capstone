package capstone.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/** 모델 출력의 열거값. [wire]가 JSON에 쓰이는 값이다(공백 없음, #3510 회피). */
interface WireEnum {
    val wire: String
}

/** D30 1단계: 의도. 'SRT 예매' 같은 범위 밖 요청은 [UNKNOWN] + [UnknownReason]. */
enum class Intent(override val wire: String) : WireEnum {
    TRAIN_BOOK("train_book"), HOSPITAL_SEARCH("hospital_search"), HOSPITAL_RESERVE("hospital_reserve"), UNKNOWN("unknown"),
}

/** D30 app. srt는 D4로 제외. */
enum class TargetApp(override val wire: String) : WireEnum {
    KORAIL_PLUS("korail_plus"), GOODDOC("gooddoc"), DDOCDOC("ddocdoc"),
}

/** D30·D17 열차 구분: KTX와 일반열차(ITX·무궁화 등)만. */
enum class TrainType(override val wire: String) : WireEnum {
    KTX("ktx"), GENERAL("general"),
}

/**
 * unknown의 사유. 자유 텍스트를 받지 않는다: 모델 출력 어디에도 화면·사용자에게 그대로 보여 줄 문장이 없게 하기 위해서다
 * (D18 ②, DECISIONS-IMPL I-37).
 */
enum class UnknownReason(override val wire: String) : WireEnum {
    /** SRT 등 지원하지 않는 앱·서비스. */
    UNSUPPORTED_APP("unsupported_app"),
    /** 예매·병원 검색이 아닌 요청(결제·송금·취소 포함). */
    OUT_OF_SCOPE("out_of_scope"),
    /** 무슨 말인지 모름. */
    UNCLEAR("unclear"),
}

/** 상대 날짜(D30 '상대 날짜는 앱 코드'). 모델은 오늘 날짜를 모르므로 말 그대로의 표현만 고르고 계산은 [SlotsV1.resolveDate]가 한다. */
enum class DateRel(override val wire: String, val days: Long) : WireEnum {
    TODAY("today", 0), TOMORROW("tomorrow", 1), DAY_AFTER_TOMORROW("day_after_tomorrow", 2),
}

/** 진료과(병원 앱 P2). 목록은 굿닥·똑닥 덤프 뒤 보정. */
enum class Department(override val wire: String) : WireEnum {
    INTERNAL("internal"), FAMILY("family"), PEDIATRICS("pediatrics"), ORTHOPEDICS("orthopedics"), ENT("ent"),
    OPHTHALMOLOGY("ophthalmology"), DERMATOLOGY("dermatology"), DENTAL("dental"), OBGYN("obgyn"), UROLOGY("urology"),
}

/**
 * 역 이름 = 코레일+ 화면 표기 그대로(공백 없음). 모델은 이 목록 밖의 값을 낼 수 없다(제약 디코딩 enum + 검증기).
 * 목록은 KTX·주요 일반열차 정차역 초안이며 10-27 코레일+ 역 선택 화면 덤프로 보정한다.
 */
object Stations {
    val KORAIL: List<String> = listOf(
        "서울", "용산", "영등포", "수원", "광명", "천안아산", "천안", "오송", "조치원", "대전", "서대전", "김천구미", "구미",
        "동대구", "대구", "경주", "포항", "울산", "밀양", "구포", "부산", "신경주", "청량리", "상봉", "양평", "원주", "제천",
        "단양", "영주", "안동", "강릉", "정동진", "동해", "평창", "진부", "익산", "전주", "남원", "순천", "여수엑스포",
        "광주송정", "목포", "나주", "정읍", "김제", "논산", "공주", "창원중앙", "창원", "마산", "진주", "평택", "아산",
    )
    private val set = KORAIL.toSet()
    operator fun contains(name: String): Boolean = name in set
}

/** 2단계에서 비어 있을 수 있는 슬롯 이름(missing[]에 쓰는 값). */
enum class SlotName(override val wire: String) : WireEnum {
    ORIGIN("origin"), DESTINATION("destination"), DATE("date"), TIME_FROM("time_from"), TRAIN_TYPE("train_type"),
    ADULT("adult"), SENIOR("senior"), DEPARTMENT("department"),
}

/** 1단계 출력. */
data class IntentResult(val intent: Intent, val app: TargetApp?, val reason: UnknownReason?)

/**
 * 슬롯 JSON v1(D30) 검증 뒤 값. 모든 필드는 nullable이고 열거값·날짜·정수뿐이다:
 * 노드·좌표·행동·정지 해제 같은 필드는 타입에 없으므로 모델이 낼 수 없다(D18 ②·③).
 */
data class SlotsV1(
    val intent: Intent,
    val app: TargetApp?,
    val origin: String? = null,
    val destination: String? = null,
    val date: LocalDate? = null,
    val dateRel: DateRel? = null,
    /** "HH:MM" 24시간. */
    val timeFrom: String? = null,
    val trainType: TrainType? = null,
    val adult: Int? = null,
    val senior: Int? = null,
    val department: Department? = null,
    /** 모델이 비었다고 보고한 슬롯. 실제 되묻기 대상은 [requiredMissing]이 코드로 계산한다. */
    val missing: List<SlotName> = emptyList(),
) {
    /** 절대 날짜가 있으면 그것, 없으면 상대 날짜를 [today] 기준으로 계산. */
    fun resolveDate(today: LocalDate): LocalDate? = date ?: dateRel?.let { today.plusDays(it.days) }

    /** 상태머신을 시작하기 전에 반드시 채워야 하는 슬롯 중 빈 것(모델 보고와 무관하게 코드가 판단). */
    fun requiredMissing(): List<SlotName> = when (intent) {
        Intent.TRAIN_BOOK -> buildList {
            if (origin == null) add(SlotName.ORIGIN)
            if (destination == null) add(SlotName.DESTINATION)
            if (date == null && dateRel == null) add(SlotName.DATE)
        }
        Intent.HOSPITAL_SEARCH, Intent.HOSPITAL_RESERVE -> if (department == null) listOf(SlotName.DEPARTMENT) else emptyList()
        Intent.UNKNOWN -> emptyList()
    }
}

/**
 * ResponseFormat.json에 넘길 JSON Schema(D11·D18 ②). 검증기([SlotValidator])가 같은 규칙을 다시 검사한다:
 * 제약 디코딩이 막히거나(#3510) 잘린 출력(#3854)이 와도 생성 후 검증이 최종 판정이다.
 */
object SlotSchemas {
    val STAGE1_KEYS: Set<String> = setOf("intent", "app", "reason")
    val TRAIN_KEYS: Set<String> = setOf("origin", "destination", "date", "date_rel", "time_from", "train_type", "adult", "senior", "missing")
    val HOSPITAL_KEYS: Set<String> = setOf("department", "date", "date_rel", "time_from", "missing")

    const val DATE_PATTERN: String = "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"
    const val TIME_PATTERN: String = "^([01][0-9]|2[0-3]):[0-5][0-9]$"
    const val MAX_PEOPLE: Int = 9

    fun stage1(): String = obj(
        "intent" to enumOf(Intent.entries, nullable = false),
        "app" to enumOf(TargetApp.entries),
        "reason" to enumOf(UnknownReason.entries),
    ).toString()

    fun stage2(intent: Intent): String = when (intent) {
        Intent.TRAIN_BOOK -> obj(
            "origin" to stringEnum(Stations.KORAIL),
            "destination" to stringEnum(Stations.KORAIL),
            "date" to pattern(DATE_PATTERN),
            "date_rel" to enumOf(DateRel.entries),
            "time_from" to pattern(TIME_PATTERN),
            "train_type" to enumOf(TrainType.entries),
            "adult" to count(),
            "senior" to count(),
            "missing" to missing(),
        )
        Intent.HOSPITAL_SEARCH, Intent.HOSPITAL_RESERVE -> obj(
            "department" to enumOf(Department.entries),
            "date" to pattern(DATE_PATTERN),
            "date_rel" to enumOf(DateRel.entries),
            "time_from" to pattern(TIME_PATTERN),
            "missing" to missing(),
        )
        Intent.UNKNOWN -> error("unknown에는 2단계가 없다")
    }.toString()

    private fun obj(vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props.toMap()))
        put("required", JsonArray(props.map { JsonPrimitive(it.first) }))
        put("additionalProperties", false)
    }

    private fun nullableType(t: String) = buildJsonArray { add(JsonPrimitive(t)); add(JsonPrimitive("null")) }

    private fun stringEnum(values: List<String>, nullable: Boolean = true) = buildJsonObject {
        put("type", if (nullable) nullableType("string") else JsonPrimitive("string"))
        put("enum", JsonArray(values.map(::JsonPrimitive) + if (nullable) listOf(kotlinx.serialization.json.JsonNull) else emptyList()))
    }

    private fun enumOf(values: List<WireEnum>, nullable: Boolean = true) = stringEnum(values.map { it.wire }, nullable)

    private fun pattern(p: String) = buildJsonObject {
        put("type", nullableType("string"))
        put("pattern", p)
    }

    private fun count() = buildJsonObject {
        put("type", nullableType("integer"))
        put("minimum", 0)
        put("maximum", MAX_PEOPLE)
    }

    private fun missing() = buildJsonObject {
        put("type", "array")
        put("items", stringEnum(SlotName.entries.map { it.wire }, nullable = false))
        put("uniqueItems", true)
    }
}
