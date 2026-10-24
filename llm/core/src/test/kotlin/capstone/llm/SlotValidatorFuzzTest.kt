package capstone.llm

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 슬롯 검증기 퍼즈(D18 ② 생성 후 재검증 보강). 고정 사례(SlotValidatorTest·InjectionTest) 밖의 출력에서도
 * ① 예외 없이 Valid|Invalid로 끝나고 ② Valid면 값이 스키마 안이며 ③ Invalid 사유 코드에 출력 원문이 섞이지 않는다
 * (로그 금지, D70·I-67)를 확인한다. 시드 고정, 실패 시 시드를 남긴다.
 */
class SlotValidatorFuzzTest {
    private val train = IntentResult(Intent.TRAIN_BOOK, TargetApp.KORAIL_PLUS, null)
    private val hospital = IntentResult(Intent.HOSPITAL_SEARCH, TargetApp.GOODDOC, null)

    private val stage1Seeds = listOf(
        Outputs.TRAIN,
        """{"intent":"unknown","app":null,"reason":"unsupported_app"}""",
        """{"intent":"hospital_search","app":"gooddoc","reason":null}""",
        """{"intent":"hospital_reserve","app":null,"reason":null}""",
    )
    private val trainSeeds = listOf(
        Outputs.SEOUL_BUSAN,
        """{"origin":null,"destination":"광주송정","date":"2026-10-30","date_rel":null,"time_from":null,"train_type":"general","adult":2,"senior":1,"missing":["origin","time_from"]}""",
    )
    private val hospitalSeeds = listOf(
        """{"department":"ent","date":null,"date_rel":"today","time_from":"14:30","missing":[]}""",
    )

    /** 끼워 넣을 조각: JSON 구조 문자·이스케이프·숫자·한글·주입 문구·제어 문자. */
    private val pool = listOf(
        "{", "}", "[", "]", "\"", ",", ":", "\\", "\\\"", "\\u0000", "null", "true", "-1", "10", "2.0", "1e0",
        " ", "\n", "​", "﻿", "서울", "부산", "SRT", "예매하기", "\"click\":\"예매하기\"", "```", "}{",
        "\"origin\":\"서울\"", "\"extra\":1", "ktx", "unknown", "99:99", "2026-02-30",
    )

    private fun mutate(seed: String, r: Random): String {
        val sb = StringBuilder(seed)
        repeat(r.nextInt(1, 4)) {
            if (sb.isEmpty()) sb.append(pool.random(r))
            val at = r.nextInt(sb.length + 1)
            when (r.nextInt(5)) {
                0 -> sb.insert(at, pool.random(r))
                1 -> if (at < sb.length) sb.deleteCharAt(at)
                2 -> sb.setLength(at) // 잘림(#3854)
                3 -> if (at < sb.length) sb.setCharAt(at, pool.random(r).first())
                else -> { // 조각 복제
                    val from = r.nextInt(sb.length)
                    val to = (from + r.nextInt(1, 12)).coerceAtMost(sb.length)
                    sb.insert(at.coerceAtMost(sb.length), sb.substring(from, to))
                }
            }
        }
        return sb.toString()
    }

    private val whyShape = Regex("^[a-z0-9_]+(:[a-z_]+)?$")

    private fun checkWhy(why: String, seed: Int) {
        if (!whyShape.matches(why)) fail("seed=$seed 사유 코드 형식 위반: '$why'")
    }

    private fun checkStage1(v: IntentResult, seed: Int) {
        val ok = when (v.intent) {
            Intent.TRAIN_BOOK -> v.app == TargetApp.KORAIL_PLUS && v.reason == null
            Intent.HOSPITAL_SEARCH, Intent.HOSPITAL_RESERVE -> v.app != TargetApp.KORAIL_PLUS && v.reason == null
            Intent.UNKNOWN -> v.app == null && v.reason != null
        }
        if (!ok) fail("seed=$seed 1단계 불변식 위반: $v")
    }

    private fun checkStage2(v: SlotsV1, first: IntentResult, seed: Int) {
        val problems = buildList {
            if (v.intent != first.intent || v.app != first.app) add("의도·앱이 1단계와 다름")
            v.origin?.let { if (it !in Stations) add("역 밖 출발") }
            v.destination?.let { if (it !in Stations) add("역 밖 도착") }
            if (v.origin != null && v.origin == v.destination) add("같은 역")
            v.timeFrom?.let { if (!Regex(SlotSchemas.TIME_PATTERN).matches(it)) add("시각 형식") }
            listOfNotNull(v.adult, v.senior).forEach { if (it !in 0..SlotSchemas.MAX_PEOPLE) add("인원 $it") }
            if (v.missing.toSet().size != v.missing.size) add("missing 중복")
            val allowed = if (first.intent == Intent.TRAIN_BOOK) SlotName.entries - SlotName.DEPARTMENT
            else listOf(SlotName.DEPARTMENT, SlotName.DATE, SlotName.TIME_FROM)
            if (!allowed.containsAll(v.missing)) add("missing 범위 밖")
            if (first.intent == Intent.TRAIN_BOOK && v.department != null) add("기차에 진료과")
            if (first.intent != Intent.TRAIN_BOOK &&
                listOf(v.origin, v.destination, v.trainType, v.adult, v.senior).any { it != null }
            ) add("병원에 기차 슬롯")
        }
        if (problems.isNotEmpty()) fail("seed=$seed 2단계 불변식 위반 $problems: $v")
    }

    private fun run(raw: String, seed: Int, check: (String) -> Validation<*>): Validation<*> = try {
        check(raw)
    } catch (t: Throwable) {
        fail("seed=$seed 검증기가 예외를 던짐(${t::class.simpleName}): ${raw.take(200)}")
    }

    @Test
    fun `1단계 출력 변형 4000개 - 예외 없음, Valid면 불변식`() {
        var valid = 0
        for (seed in 1..4000) {
            val r = Random(seed)
            when (val v = run(mutate(stage1Seeds.random(r), r), seed, SlotValidator::stage1)) {
                is Validation.Valid<*> -> { valid++; checkStage1(v.value as IntentResult, seed) }
                is Validation.Invalid -> checkWhy(v.why, seed)
            }
        }
        // 변형 일부(공백 삽입 등)는 여전히 정상이어야 퍼즈가 Valid 쪽 불변식도 실제로 본 것이다.
        assertTrue(valid in 1..3999, "Valid $valid 개 — 변형이 너무 약하거나 너무 셈")
    }

    @Test
    fun `2단계 기차·병원 출력 변형 6000개 - 예외 없음, Valid면 불변식`() {
        var valid = 0
        for (seed in 1..6000) {
            val r = Random(seed)
            val (seeds, first) = if (r.nextInt(3) == 0) hospitalSeeds to hospital else trainSeeds to train
            when (val v = run(mutate(seeds.random(r), r), seed) { SlotValidator.stage2(it, first) }) {
                is Validation.Valid<*> -> { valid++; checkStage2(v.value as SlotsV1, first, seed) }
                is Validation.Invalid -> checkWhy(v.why, seed)
            }
        }
        assertTrue(valid in 1..5999, "Valid $valid 개")
    }

    @Test
    fun `깊게 중첩된 배열·객체도 예외 없이 거부`() {
        // 수정 전: depth 5,000 배열에서 kotlinx 재귀 파서가 StackOverflowError(I-81). 출력 384토큰이어도 '[[[[' 토큰이면 수천 겹 가능.
        for (depth in listOf(500, 5_000, 50_000)) {
            val arr = """{"origin":${"[".repeat(depth)}${"]".repeat(depth)},"x":1}"""
            assertEquals("too_deep", assertIs<Validation.Invalid>(run(arr, depth) { SlotValidator.stage2(it, train) }).why)
            val obj = """{"intent":${"{\"a\":".repeat(depth)}1${"}".repeat(depth)}}"""
            assertEquals("too_deep", assertIs<Validation.Invalid>(run(obj, depth, SlotValidator::stage1)).why)
        }
    }

    @Test
    fun `아주 긴 문자열 값은 원문 없이 거부`() {
        val long = "서울".repeat(50_000)
        val v = run(Outputs.SEOUL_BUSAN.replace("\"서울\"", "\"$long\""), 0) { SlotValidator.stage2(it, train) }
        assertEquals("station:origin", assertIs<Validation.Invalid>(v).why)
    }
}

