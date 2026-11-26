package net.jejer.hipda.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import net.jejer.hipda.R
import net.jejer.hipda.data.message.UnreadSnapshot
import net.jejer.hipda.feature.app.MainActivity

/** 仅在 Android 13（API 33）及以上发送系统通知需具备 `POST_NOTIFICATIONS` 运行时权限。 */
fun requiresNotificationPermission(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU

/** 检查当前进程是否允许发布系统通知：API < 33 恒返回 `true`，API 33+ 校验 `POST_NOTIFICATIONS` 授权状态。 */
@SuppressLint("InlinedApi")
fun hasNotificationPermission(
    context: Context,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Boolean = !requiresNotificationPermission(sdkInt) ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Rendered title and body copy for a system notification derived from an [UnreadSnapshot].
 */
data class NotificationDisplayContent(
    val title: String,
    val text: String,
)

/**
 * Abstraction for posting and cancelling unread forum notifications in the Android notification shade.
 */
interface NotificationPoster {
    fun postUnread(snapshot: UnreadSnapshot)
    fun cancel()
}

@Singleton
class AndroidNotificationPoster @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationPoster {

    override fun postUnread(snapshot: UnreadSnapshot) {
        if (snapshot.smsUnreadCount <= 0 && snapshot.notifyUnreadCount <= 0) {
            cancel()
            return
        }
        if (!hasNotificationPermission(context)) {
            return
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(notificationManager)

        val content = formatNotificationContent(context, snapshot)
        val singleSms = snapshot.latestUnreadSms
            ?.takeIf { snapshot.smsUnreadCount == 1 && snapshot.notifyUnreadCount == 0 }

        val intent = MainActivity.createNotificationIntent(
            context = context,
            smsCount = snapshot.smsUnreadCount,
            threadCount = snapshot.notifyUnreadCount,
            uid = singleSms?.uid,
        )
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val accentColor = ContextCompat.getColor(context, R.color.icon_blue)
        val publicText = if (singleSms != null) {
            context.getString(R.string.notification_single_sms_body_fallback)
        } else {
            content.text
        }
        val publicNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_summary_title))
            .setContentText(publicText)
            .setSmallIcon(R.drawable.ic_stat_hi)
            .setColor(accentColor)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_stat_hi)
            .setColor(accentColor)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission revoked on API 33+; fail quietly without crashing worker.
        }
    }

    override fun cancel() {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (_: SecurityException) {
            // Notification permission revoked on API 33+; fail quietly without crashing worker.
        }
    }

    private fun ensureChannel(notificationManager: NotificationManager) {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val accentColor = ContextCompat.getColor(context, R.color.icon_blue)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            enableLights(true)
            lightColor = accentColor
            enableVibration(false)
            setBypassDnd(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID: String = "HIPDA_NOTI"
        const val NOTIFICATION_ID: Int = 0
        internal const val MAX_SUMMARY_PREVIEW_CHARS: Int = 80
        private val WHITESPACE_REGEX = Regex("\\s+")

        fun formatNotificationContent(
            context: Context,
            snapshot: UnreadSnapshot,
        ): NotificationDisplayContent {
            val smsCount = snapshot.smsUnreadCount.coerceAtLeast(0)
            val notifyCount = snapshot.notifyUnreadCount.coerceAtLeast(0)
            val singleSms = snapshot.latestUnreadSms
            if (smsCount == 1 && notifyCount == 0 && singleSms != null) {
                val author = singleSms.author.trim()
                val title = if (author.isNotEmpty()) {
                    context.getString(R.string.notification_single_sms_title, author)
                } else {
                    context.getString(R.string.notification_single_sms_title_fallback)
                }
                val text = truncateSummaryPreview(singleSms.summary).ifEmpty {
                    context.getString(R.string.notification_single_sms_body_fallback)
                }
                return NotificationDisplayContent(title = title, text = text)
            }

            val smsPart = if (smsCount > 0) {
                context.getString(R.string.notification_summary_sms_part, smsCount)
            } else {
                null
            }
            val notifyPart = if (notifyCount > 0) {
                context.getString(R.string.notification_summary_notify_part, notifyCount)
            } else {
                null
            }
            val text = when {
                smsPart != null && notifyPart != null ->
                    context.getString(R.string.notification_summary_body_both, smsPart, notifyPart)
                smsPart != null ->
                    context.getString(R.string.notification_summary_body_single, smsPart)
                notifyPart != null ->
                    context.getString(R.string.notification_summary_body_single, notifyPart)
                else -> ""
            }
            return NotificationDisplayContent(
                title = context.getString(R.string.notification_summary_title),
                text = text,
            )
        }

        private fun truncateSummaryPreview(raw: String): String {
            val collapsed = raw.trim().replace(WHITESPACE_REGEX, " ")
            if (collapsed.length <= MAX_SUMMARY_PREVIEW_CHARS) return collapsed
            return collapsed.take(MAX_SUMMARY_PREVIEW_CHARS - 1).trimEnd() + "…"
        }
    }
}
