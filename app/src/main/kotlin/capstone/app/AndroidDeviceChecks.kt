package capstone.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.advancedprotection.AdvancedProtectionManager
import capstone.app.core.DeviceChecks
import capstone.service.DumperService

/**
 * 온보딩·실행 전 확인에 쓰는 기기 상태(D66). 미빌드(클라우드 Google Maven 차단).
 */
class AndroidDeviceChecks(private val context: Context) : DeviceChecks {

    override fun notificationsGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** 서비스가 실제로 연결된 상태만 '켜짐'으로 본다(설정 문자열만 켜져 있고 죽은 경우를 거르기 위해). */
    override fun accessibilityEnabled(): Boolean = DumperService.instance != null

    /**
     * AdvancedProtectionManager는 API 36부터(D66 ③, 추정). 그 아래는 감지할 수 없어 null.
     * 권한·서비스가 없거나 예외가 나도 null — 막지는 않되 접근성 활성 검사가 실패하면 분기 A로 간다.
     */
    override fun advancedProtection(): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        return try {
            context.getSystemService(AdvancedProtectionManager::class.java)?.isAdvancedProtectionEnabled
        } catch (_: Exception) {
            null
        }
    }
}
