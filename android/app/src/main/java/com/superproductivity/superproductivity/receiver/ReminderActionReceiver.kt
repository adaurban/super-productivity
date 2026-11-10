package com.superproductivity.superproductivity.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.core.app.NotificationManagerCompat
import com.superproductivity.superproductivity.service.ReminderDoneWorker
import com.superproductivity.superproductivity.service.ReminderAlarmStore
import com.superproductivity.superproductivity.service.ReminderNotificationHelper
import com.superproductivity.superproductivity.widget.ReminderDoneQueue
import com.superproductivity.superproductivity.widget.ReminderSnoozeQueue
import java.util.concurrent.TimeUnit

/**
 * Handles reminder snooze action in the background by simply rescheduling the alarm.
 * No app involvement needed - just dismiss notification and schedule new alarm.
 */
class ReminderActionReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "ReminderActionReceiver"
        const val ACTION_SNOOZE = "com.superproductivity.REMINDER_SNOOZE"
        const val ACTION_SNOOZE_1H = "com.superproductivity.REMINDER_SNOOZE_1H"
        const val ACTION_DONE = "com.superproductivity.REMINDER_DONE"
        const val ACTION_DONE_DRAIN = "com.superproductivity.superproductivity.REMINDER_DONE_DRAIN"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_RELATED_ID = "related_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_REMINDER_TYPE = "reminder_type"
        const val EXTRA_USE_ALARM_STYLE = "use_alarm_style"
        const val EXTRA_IS_ONGOING = "is_ongoing"

        const val SNOOZE_DURATION_MS = 10 * 60 * 1000L // 10 minutes
        const val SNOOZE_DURATION_1H_MS = 60 * 60 * 1000L // 1 hour
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val relatedId = intent.getStringExtra(EXTRA_RELATED_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reminder"
        val reminderType = intent.getStringExtra(EXTRA_REMINDER_TYPE) ?: "TASK"
        val useAlarmStyle = intent.getBooleanExtra(EXTRA_USE_ALARM_STYLE, false)
        val isOngoing = intent.getBooleanExtra(EXTRA_IS_ONGOING, false)

        when (intent.action) {
            ACTION_SNOOZE -> {
                Log.d(TAG, "Snooze 10m: notificationId=$notificationId")

                // Dismiss the notification
                if (notificationId != -1) {
                    NotificationManagerCompat.from(context).cancel(notificationId)
                }

                // Reschedule alarm for 10 minutes from now
                val newTriggerTime = System.currentTimeMillis() + SNOOZE_DURATION_MS
                snooze(context, notificationId, reminderId, relatedId, title, reminderType, useAlarmStyle, isOngoing, newTriggerTime)
            }

            ACTION_SNOOZE_1H -> {
                Log.d(TAG, "Snooze 1h: notificationId=$notificationId")

                // Dismiss the notification
                if (notificationId != -1) {
                    NotificationManagerCompat.from(context).cancel(notificationId)
                }

                // Reschedule alarm for 1 hour from now
                val newTriggerTime = System.currentTimeMillis() + SNOOZE_DURATION_1H_MS
                snooze(context, notificationId, reminderId, relatedId, title, reminderType, useAlarmStyle, isOngoing, newTriggerTime)
            }

            ACTION_DONE -> {
                Log.d(TAG, "Done: notificationId=$notificationId, relatedId=$relatedId")

                // Dismiss the notification
                if (notificationId != -1) {
                    NotificationManagerCompat.from(context).cancel(notificationId)
                }

                // Remove from alarm store so BootReceiver won't re-schedule
                if (notificationId != -1) {
                    ReminderAlarmStore.remove(context, notificationId)
                }

                // Queue task ID for the frontend to pick up
                ReminderDoneQueue.addTaskId(context, relatedId)

                // Signal a live (backgrounded) activity to drain the queue now,
                // so the completion reaches the op-log and sync without waiting
                // for the next resume. Contentless: Angular pulls the queued ids
                // itself via getReminderDoneQueue(). When the process is dead
                // nobody receives this; the queue still holds the id for the
                // next app open.
                LocalBroadcastManager.getInstance(context)
                    .sendBroadcast(Intent(ACTION_DONE_DRAIN))

                // When the process is dead the broadcast above has no receiver:
                // boot the engine headlessly instead, so the queued completion
                // is persisted and synced without opening the app. A live app
                // drains via the broadcast first, so this becomes a no-op peek.
                try {
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "reminder_done_drain",
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        OneTimeWorkRequestBuilder<ReminderDoneWorker>()
                            .setConstraints(
                                Constraints.Builder()
                                    .setRequiredNetworkType(NetworkType.CONNECTED)
                                    .build()
                            )
                            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                            .build()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enqueue reminder done worker", e)
                }
            }
        }
    }

    private fun snooze(
        context: Context,
        notificationId: Int,
        reminderId: String,
        relatedId: String,
        title: String,
        reminderType: String,
        useAlarmStyle: Boolean,
        isOngoing: Boolean,
        newTriggerTime: Long,
    ) {
        ReminderNotificationHelper.scheduleReminder(
            context, notificationId, reminderId, relatedId,
            title, reminderType, newTriggerTime, useAlarmStyle, isOngoing,
        )
        // Queue for frontend to update NgRx state on next app open
        ReminderSnoozeQueue.addSnoozeEvent(context, relatedId, newTriggerTime, reminderType)
        Log.d(TAG, "Snoozed reminder to ${newTriggerTime}")
    }
}
