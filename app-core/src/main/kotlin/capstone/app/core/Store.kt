package capstone.app.core

/**
 * 기기 안 저장소(앱 내부 SharedPreferences). 동의 기록·실행 횟수만 둔다(D25: 동의 기록 기기 내).
 * 슬롯 값·발화·화면 내용은 저장하지 않는다.
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MemoryStore(private val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
