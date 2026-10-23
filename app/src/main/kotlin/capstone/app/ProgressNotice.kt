package capstone.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 고정 알림(D39·D41): 대상 앱이 앞에 있는 동안 진행 안내는 알림 글자로, 멈춤은 알림 탭으로.
 * 탭과 '멈춰' 버튼 모두 PendingIntent.getActivity로 우리 Activity를 직접 연다(트램폴린 금지, Android 12+).
 * deleteIntent 재게시·creator opt-in(BAL)은 10-28 W02 실측과 함께 붙인다.
 */
class ProgressNotice(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "진행 안내", NotificationManager.IMPORTANCE_LOW).apply {
                description = "기차 찾기 진행 상황과 멈춤 버튼"
            },
        )
    }

    fun show(text: String) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_PAUSE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("말로 하는 예매 도우미")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(stop) // 알림 아무 데나 눌러도 멈추고 우리 화면으로 온다(고령층: 버튼 1개 원칙)
            .addAction(Notification.Action.Builder(null, "멈춰", stop).build())
            .addAction(Notification.Action.Builder(null, "앱 열기", open).build())
            .build()
        nm.notify(ID, n)
    }

    fun hide() = nm.cancel(ID)

    private companion object {
        const val CHANNEL = "progress"
        const val ID = 1
    }
}
