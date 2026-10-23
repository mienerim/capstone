package capstone.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.format.DateTimeParseException

sealed interface Validation<out T> {
    data class Valid<T>(val value: T) : Validation<T>
    /** [why]는 로그용 짧은 코드다. 모델 출력 원문은 담지 않는다(로그에 사용자 발화가 새지 않게). */
    data class Invalid(val why: String) : Validation<Nothing>
}

/**
 * 생성 후 스키마 재검증(D11·D18 ②). 제약 디코딩을 믿지 않고 [SlotSchemas]와 같은 규칙을 다시 본다.
 *
 * 엄격 규칙: 출력 전체가 JSON 객체 하나(앞뒤 공백만 허용, 코드 펜스·설명문·두 번째 객체 = 거부),
 * 모르는 키·빠진 키·타입 불일치·열거 밖 값 = 거부. 거부되면 [SlotParser]가 재생성 1회 → 되묻기로 간다.
 */
object SlotValidator {
    private val json = Json { isLenient = false }

    fun stage1(raw: String): Validation<IntentResult> = guard {
        val o = parseObject(raw)
        exactKeys(o, SlotSchemas.STAGE1_KEYS)
        val intent = enumField(o, "intent", Intent.entries) ?: invalid("intent_null")
        var app = enumField(o, "app", TargetApp.entries)
        var reason = enumField(o, "reason", UnknownReason.entries)
        when (intent) {
            Intent.UNKNOWN -> {
                // 사유를 빠뜨린 unknown은 '모름'으로 본다(되묻기 문구만 달라지므로 거부할 이유가 없다).
                reason = reason ?: UnknownReason.UNCLEAR
                app = null
            }
            Intent.TRAIN_BOOK -> {
                if (reason != null) invalid("reason_on_known_intent")
                if (app != null && app != TargetApp.KORAIL_PLUS) invalid("app_intent_mismatch")
                app = TargetApp.KORAIL_PLUS
            }
            Intent.HOSPITAL_SEARCH, Intent.HOSPITAL_RESERVE -> {
                if (reason != null) invalid("reason_on_known_intent")
                if (app == TargetApp.KORAIL_PLUS) invalid("app_intent_mismatch")
            }
        }
        IntentResult(intent, app, reason)
    }

    fun stage2(raw: String, first: IntentResult): Validation<SlotsV1> = guard {
        val o = parseObject(raw)
        when (first.intent) {
            Intent.TRAIN_BOOK -> {
                exactKeys(o, SlotSchemas.TRAIN_KEYS)
                val origin = station(o, "origin")
                val destination = station(o, "destination")
                if (origin != null && origin == destination) invalid("same_station")
                SlotsV1(
                    intent = first.intent, app = first.app,
                    origin = origin, destination = destination,
                    date = date(o), dateRel = enumField(o, "date_rel", DateRel.entries), timeFrom = time(o),
                    trainType = enumField(o, "train_type", TrainType.entries),
                    adult = count(o, "adult"), senior = count(o, "senior"),
                    missing = missing(o, setOf(SlotName.ORIGIN, SlotName.DESTINATION, SlotName.DATE, SlotName.TIME_FROM, SlotName.TRAIN_TYPE, SlotName.ADULT, SlotName.SENIOR)),
                )
            }
            Intent.HOSPITAL_SEARCH, Intent.HOSPITAL_RESERVE -> {
                exactKeys(o, SlotSchemas.HOSPITAL_KEYS)
                SlotsV1(
                    intent = first.intent, app = first.app,
                    department = enumField(o, "department", Department.entries),
                    date = date(o), dateRel = enumField(o, "date_rel", DateRel.entries), timeFrom = time(o),
                    missing = missing(o, setOf(SlotName.DEPARTMENT, SlotName.DATE, SlotName.TIME_FROM)),
                )
            }
            Intent.UNKNOWN -> invalid("no_stage2_for_unknown")
        }
    }

    // ---- 규칙 ----

    private class Reject(val why: String) : RuntimeException(why, null, false, false)

    private fun invalid(why: String): Nothing = throw Reject(why)

    private inline fun <T> guard(body: () -> T): Validation<T> = try {
        Validation.Valid(body())
    } catch (e: Reject) {
        Validation.Invalid(e.why)
    }

    private fun parseObject(raw: String): JsonObject {
        val t = raw.trim()
        // 엔진은 출력 상한에 닿아도 에러 없이 끝낸다(D65). 닫히지 않은 객체는 따로 세어 측정 시트 E에 남긴다.
        if (t.startsWith("{") && !closes(t)) invalid("truncated")
        // 펜스·머리말·꼬리말을 잘라 내 '살리지' 않는다: 형식 탈취(T3)와 정상 출력을 구분할 수 없게 되기 때문이다.
        if (!t.startsWith("{") || !t.endsWith("}")) invalid("not_a_single_object")
        val e = try {
            json.parseToJsonElement(t)
        } catch (_: Exception) {
            invalid("json_syntax")
        }
        return e as? JsonObject ?: invalid("not_object")
    }

    /** 첫 `{`의 짝이 문자열 밖에서 닫히는가. 닫힌 뒤의 꼬리말은 여기서 보지 않는다(not_a_single_object가 거부). */
    private fun closes(t: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in t) {
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> {}
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> if (--depth == 0) return true
            }
        }
        return false
    }

    private fun exactKeys(o: JsonObject, keys: Set<String>) {
        if (o.keys.any { it !in keys }) invalid("unknown_key")
        if (keys.any { it !in o }) invalid("missing_key")
    }

    private fun str(o: JsonObject, key: String): String? = when (val v = o.getValue(key)) {
        is JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else invalid("type:$key")
        else -> invalid("type:$key")
    }

    private fun <E : WireEnum> enumField(o: JsonObject, key: String, values: List<E>): E? =
        str(o, key)?.let { s -> values.firstOrNull { it.wire == s } ?: invalid("enum:$key") }

    private fun station(o: JsonObject, key: String): String? =
        str(o, key)?.also { if (it !in Stations) invalid("station:$key") }

    private fun date(o: JsonObject): LocalDate? = str(o, "date")?.let { s ->
        if (!Regex(SlotSchemas.DATE_PATTERN).matches(s)) invalid("date_format")
        try {
            LocalDate.parse(s)
        } catch (_: DateTimeParseException) {
            invalid("date_value")
        }
    }

    private fun time(o: JsonObject): String? =
        str(o, "time_from")?.also { if (!Regex(SlotSchemas.TIME_PATTERN).matches(it)) invalid("time_format") }

    private fun count(o: JsonObject, key: String): Int? = when (val v = o.getValue(key)) {
        is JsonNull -> null
        is JsonPrimitive -> {
            // "2"(문자열)·2.0·2e0은 거부: 스키마의 integer와 정확히 맞춘다.
            val n = if (!v.isString && v.content.all { it.isDigit() }) v.intOrNull else null
            if (n == null || n !in 0..SlotSchemas.MAX_PEOPLE) invalid("count:$key")
            n
        }
        else -> invalid("type:$key")
    }

    private fun missing(o: JsonObject, allowed: Set<SlotName>): List<SlotName> {
        val arr = o.getValue("missing") as? JsonArray ?: invalid("type:missing")
        val names = arr.map { e: JsonElement ->
            val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("type:missing")
            SlotName.entries.firstOrNull { it.wire == s }?.takeIf { it in allowed } ?: invalid("enum:missing")
        }
        if (names.toSet().size != names.size) invalid("dup:missing")
        return names
    }
}
