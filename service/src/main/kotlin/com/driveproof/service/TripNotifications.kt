package com.driveproof.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * 알림 두 가지(implementation.md "서비스 상태", parking-feedback.md "주차 알림").
 * - 상시 알림: 채널 하나(중요도 LOW). 문구만 바꾼다
 * - 주차 알림: 다른 채널(중요도 HIGH, heads-up). 누르면 그 주행의 오늘의 한 장이 열린다
 *
 * POST_NOTIFICATIONS가 없으면 시스템이 보이지 않게 한다. 서비스와 기록은 그대로 돈다.
 * 이때는 앱을 열면 안 본 한 장부터 보여 준다(U-4).
 */
object TripNotifications {
    internal const val CHANNEL_ID = "dp_trip_status"
    internal const val NOTIFICATION_ID = 1

    internal const val PARK_CHANNEL_ID = "dp_trip_parked"
    internal const val PARK_NOTIFICATION_ID = 2

    /** 주차 알림을 눌러 앱을 열 때 담는 주행 id(Long). 화면(U)이 읽어 그 주행의 한 장을 연다 */
    const val EXTRA_TRIP_ID = "com.driveproof.extra.TRIP_ID"

    internal fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (nm.getNotificationChannel(PARK_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(PARK_CHANNEL_ID, context.getString(R.string.park_channel_name), NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    internal fun build(context: Context, recording: Boolean): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentText(
                context.getString(if (recording) R.string.service_notice_recording else R.string.service_notice_idle)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    internal fun update(context: Context, recording: Boolean) {
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(context, recording))
    }

    /**
     * 주차 알림. 제목 "주행 요약이 준비됐어요", 본문은 한 장의 첫 줄.
     * 한 장(E-6)이 없으면 본문 없이 제목만 띄운다.
     */
    internal fun showParked(context: Context, tripId: Long, firstLine: String?) {
        val open = openTripIntent(context, tripId) ?: return
        val pi = PendingIntent.getActivity(
            context,
            tripId.toInt(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, PARK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setColor(0xfffea93a.toInt())
            .setContentTitle(context.getString(R.string.park_notice_title))
            .apply { if (!firstLine.isNullOrBlank()) setContentText(firstLine) }
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(PARK_NOTIFICATION_ID, n)
    }

    /** 30초 안에 다시 출발하면 주차 알림을 지운다 */
    internal fun cancelParked(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(PARK_NOTIFICATION_ID)
    }

    /** 앱 시작 화면을 연다. :service는 화면 모듈을 모르므로 런처 Intent에 tripId를 담는다 */
    private fun openTripIntent(context: Context, tripId: Long): Intent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_TRIP_ID, tripId)
        }
}
