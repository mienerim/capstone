package capstone.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SlotValidatorTest {
    private val train = IntentResult(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, null)

    private fun s2(vararg overrides: Pair<String, String>): String {
        val base = linkedMapOf(
            "origin" to "\"서울\"", "destination" to "\"부산\"", "date" to "null", "date_rel" to "null", "time_from" to "null",
            "train_type" to "null", "adult" to "null", "senior" to "null", "missing" to "[]",
        )
        overrides.forEach { (k, v) -> if (v == "<drop>") base.remove(k) else base[k] = v }
        return base.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
    }

    private fun why(r: Validation<*>) = assertIs<Validation.Invalid>(r).why

    @Test
    fun `1단계 - 정상과 기본값`() {
        assertEquals(train, assertIs<Validation.Valid<IntentResult>>(SlotValidator.stage1(Outputs.TRAIN)).value)
        // app을 비운 기차 요청은 코레일+로 채운다.
        val r = SlotValidator.stage1("""{"intent":"train_book","app":null,"reason":null}""")
        assertEquals(TargetApp.KORAIL_PLUS, assertIs<Validation.Valid<IntentResult>>(r).value.app)
        // 사유 없는 unknown은 unclear.
        val u = SlotValidator.stage1("""{"intent":"unknown","app":"gooddoc","reason":null}""")
        assertEquals(IntentResult(Intent.UNKNOWN, null, UnknownReason.UNCLEAR), assertIs<Validation.Valid<IntentResult>>(u).value)
    }

    @Test
    fun `1단계 - 모순 거부`() {
        assertEquals("app_intent_mismatch", why(SlotValidator.stage1("""{"intent":"train_book","app":"gooddoc","reason":null}""")))
        assertEquals("app_intent_mismatch", why(SlotValidator.stage1("""{"intent":"hospital_search","app":"korail_plus","reason":null}""")))
        assertEquals("reason_on_known_intent", why(SlotValidator.stage1("""{"intent":"train_book","app":null,"reason":"unclear"}""")))
        assertEquals("intent_null", why(SlotValidator.stage1("""{"intent":null,"app":null,"reason":null}""")))
    }

    @Test
    fun `2단계 - 정상 기차 슬롯과 날짜 계산`() {
        val v = assertIs<Validation.Valid<SlotsV1>>(SlotValidator.stage2(Outputs.SEOUL_BUSAN, train)).value
        assertEquals("서울", v.origin)
        assertEquals(DateRel.TOMORROW, v.dateRel)
        assertEquals(LocalDate.of(2026, 10, 27), v.resolveDate(LocalDate.of(2026, 10, 26)))
        assertEquals(TrainType.KTX, v.trainType)
        assertEquals(listOf(SlotName.SENIOR), v.missing)
        assertEquals(emptyList(), v.requiredMissing())

        val abs = assertIs<Validation.Valid<SlotsV1>>(SlotValidator.stage2(s2("date" to "\"2026-10-30\"", "date_rel" to "\"today\""), train)).value
        assertEquals(LocalDate.of(2026, 10, 30), abs.resolveDate(LocalDate.of(2026, 10, 26)), "절대 날짜가 우선")
    }

    @Test
    fun `2단계 - 빈 슬롯은 코드가 되묻기 대상을 계산한다`() {
        val v = assertIs<Validation.Valid<SlotsV1>>(SlotValidator.stage2(s2("destination" to "null"), train)).value
        // 모델이 missing을 비워 보내도 코드가 판단한다.
        assertEquals(listOf(SlotName.DESTINATION, SlotName.DATE), v.requiredMissing())
    }

    @Test
    fun `2단계 - 타입·형식·범위 거부`() {
        assertEquals("station:origin", why(SlotValidator.stage2(s2("origin" to "\"서울역\""), train)))
        assertEquals("station:origin", why(SlotValidator.stage2(s2("origin" to "\"서 울\""), train)))
        assertEquals("same_station", why(SlotValidator.stage2(s2("destination" to "\"서울\""), train)))
        assertEquals("date_format", why(SlotValidator.stage2(s2("date" to "\"10월 27일\""), train)))
        assertEquals("date_value", why(SlotValidator.stage2(s2("date" to "\"2026-02-30\""), train)))
        assertEquals("time_format", why(SlotValidator.stage2(s2("time_from" to "\"9시\""), train)))
        assertEquals("count:adult", why(SlotValidator.stage2(s2("adult" to "\"2\""), train)))
        assertEquals("count:adult", why(SlotValidator.stage2(s2("adult" to "2.0"), train)))
        assertEquals("count:adult", why(SlotValidator.stage2(s2("adult" to "10"), train)))
        assertEquals("count:senior", why(SlotValidator.stage2(s2("senior" to "-1"), train)))
        assertEquals("enum:train_type", why(SlotValidator.stage2(s2("train_type" to "\"srt\""), train)))
        assertEquals("enum:missing", why(SlotValidator.stage2(s2("missing" to "[\"department\"]"), train)))
        assertEquals("dup:missing", why(SlotValidator.stage2(s2("missing" to "[\"date\",\"date\"]"), train)))
        assertEquals("type:origin", why(SlotValidator.stage2(s2("origin" to "[\"서울\"]"), train)))
        assertEquals("missing_key", why(SlotValidator.stage2(s2("senior" to "<drop>"), train)))
    }

    @Test
    fun `2단계 - 병원 슬롯`() {
        val hosp = IntentResult(Intent.HOSPITAL_SEARCH, null, null)
        val ok = """{"department":"ent","date":null,"date_rel":"today","time_from":null,"missing":["time_from"]}"""
        assertEquals(Department.ENT, assertIs<Validation.Valid<SlotsV1>>(SlotValidator.stage2(ok, hosp)).value.department)
        assertEquals("unknown_key", why(SlotValidator.stage2(Outputs.SEOUL_BUSAN, hosp)))
    }

    @Test
    fun `스키마와 검증기의 키·열거값이 같다`() {
        val s1 = Json.parseToJsonElement(SlotSchemas.stage1()).jsonObject
        assertEquals(SlotSchemas.STAGE1_KEYS, s1.getValue("properties").jsonObject.keys)
        assertEquals("false", s1.getValue("additionalProperties").jsonPrimitive.content)
        val intents = s1.getValue("properties").jsonObject.getValue("intent").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(Intent.entries.map { it.wire }, intents)

        val t = Json.parseToJsonElement(SlotSchemas.stage2(Intent.TRAIN_BOOK)).jsonObject
        assertEquals(SlotSchemas.TRAIN_KEYS, t.getValue("properties").jsonObject.keys)
        assertEquals(SlotSchemas.TRAIN_KEYS, t.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val stations = t.getValue("properties").jsonObject.getValue("origin").jsonObject.getValue("enum").jsonArray
            .mapNotNull { it.jsonPrimitive.takeIf { p -> p.isString }?.content }
        assertEquals(Stations.KORAIL, stations)

        val h = Json.parseToJsonElement(SlotSchemas.stage2(Intent.HOSPITAL_RESERVE)).jsonObject
        assertEquals(SlotSchemas.HOSPITAL_KEYS, h.getValue("properties").jsonObject.keys)
    }

    @Test
    fun `열거값과 역 이름에는 공백이 없다(#3510)`() {
        val wires = listOf(Intent.entries, TargetApp.entries, TrainType.entries, UnknownReason.entries, DateRel.entries, Department.entries, SlotName.entries)
            .flatten().map { (it as WireEnum).wire }
        assertEquals(emptyList(), (wires + Stations.KORAIL).filter { w -> w.any { it.isWhitespace() } })
        assertEquals(Stations.KORAIL.size, Stations.KORAIL.toSet().size)
    }

    @Test
    fun `잘림 감지 - 닫히지 않은 객체만 truncated, 문자열 안의 괄호는 세지 않는다`() {
        fun why(raw: String) = (SlotValidator.stage1(raw) as? Validation.Invalid)?.why
        assertEquals("truncated", why("""{"intent":"train_book","app":"""))
        assertEquals("truncated", why("""{"intent":"train_book","app":"korail_plus","reason":"}"""))
        assertEquals("not_a_single_object", why("""{"intent":"unknown","app":null,"reason":null} 끝"""))
        assertEquals("not_a_single_object", why("설명: {"))
        assertIs<Validation.Valid<IntentResult>>(SlotValidator.stage1("""{"intent":"train_book","app":null,"reason":null}"""))
    }

    @Test
    fun `컨텍스트 한도 - 8GB급은 4096, 6GB급·못 읽음은 2048(D65)`() {
        val gib = 1024L * 1024 * 1024
        assertEquals(4096, ContextBudget.maxNumTokens(7_600_000_000L))
        assertEquals(4096, ContextBudget.maxNumTokens(11 * gib))
        assertEquals(2048, ContextBudget.maxNumTokens(5_600_000_000L))
        assertEquals(2048, ContextBudget.maxNumTokens(0))
    }
}
