package capstone.app

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.security.advancedprotection.AdvancedProtectionManager
import capstone.app.core.DeviceChecks
import capstone.service.DumperService

/**
 * 온보딩·실행 전 확인에 쓰는 기기 상태(D66). 미빌드(클라우드 Google Maven 차단).
 */
class AndroidDeviceChecks(private val context: Context) : DeviceChecks {

    /**
     * D69 ⑤: 앱 알림(POST_NOTIFICATIONS 거부 포함)과 진행 알림 채널이 모두 켜져 있어야 한다.
     * 채널이 아직 없으면(첫 실행 전) 앱 알림만 본다 — 채널은 ProgressNotice가 만들 때 IMPORTANCE_LOW로 생긴다.
     */
    override fun notificationsGranted(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        val ch = nm.getNotificationChannel(ProgressNotice.CHANNEL) ?: return true
        return ch.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** 서비스가 실제로 연결된 상태만 '켜짐'으로 본다(설정 문자열만 켜져 있고 죽은 경우를 거르기 위해). */
    override fun accessibilityEnabled(): Boolean = DumperService.instance != null

    /**
     * AdvancedProtectionManager는 API 36부터, QUERY_ADVANCED_PROTECTION_MODE(protectionLevel normal, featureFlag
     * android.security.aapm_api — AOSP 매니페스트 기준, D71) 필요. 갤럭시 실제 반환은 W09 실측.
     * 그 아래·플래그 꺼짐·권한 없음·예외는 '불명' null — 막지 않고, 접근성 2회 실패 시 분기 A + 보조 문구로 간다(D68 ②).
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
