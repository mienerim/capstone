package capstone.snapshot

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// 재생기 시나리오(tools)도 같은 규칙으로 읽도록 공개한다.
// ---- JsonObject 접근 도우미(형식이 틀리면 null → 호출자가 기본값 또는 오류) ----

fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }
fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.contentOrNull
fun JsonObject.int(key: String): Int? = prim(key)?.takeUnless { it.isString }?.intOrNull
fun JsonObject.long(key: String): Long? = prim(key)?.takeUnless { it.isString }?.longOrNull
fun JsonObject.bool(key: String): Boolean? = prim(key)?.takeUnless { it.isString }?.booleanOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
