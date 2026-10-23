package capstone.app

import android.content.Context
import capstone.app.core.KeyValueStore

/** 앱 내부 SharedPreferences(allowBackup=false라 기기 밖으로 나가지 않는다, D25). */
class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("capstone", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
