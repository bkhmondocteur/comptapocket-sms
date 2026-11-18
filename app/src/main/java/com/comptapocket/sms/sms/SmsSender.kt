package com.comptapocket.sms.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.comptapocket.sms.R
import com.comptapocket.sms.data.local.AppDatabase
import com.comptapocket.sms.data.local.ClientCategory
import com.comptapocket.sms.data.local.ClientReminderEntity
import com.comptapocket.sms.data.local.ReminderType
import com.comptapocket.sms.firebase.FirebaseSyncManager
import com.comptapocket.sms.simulation.SimulationDateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class SmsSender(private val context: Context) {

    companion object {
        private const val TAG = "SmsSender"
    }

    private fun getSafeSmsManager(): SmsManager {
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        val defaultSubId = SubscriptionManager.getDefaultSmsSubscriptionId()

        val targetSubId = if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            defaultSubId
        } else {
            try {
                subscriptionManager?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
                    ?: SubscriptionManager.getDefaultSubscriptionId()
            } catch (_: Exception) {
                SubscriptionManager.getDefaultSubscriptionId()
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (targetSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    context.getSystemService(SmsManager::class.java)?.createForSubscriptionId(targetSubId)
                        ?: context.getSystemService(SmsManager::class.java)
                        ?: SmsManager.getDefault()
                } else {
                    context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erreur createForSubscriptionId: ${e.message}, fallback sur SmsManager.getDefault()")
                SmsManager.getDefault()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            if (targetSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                SmsManager.getSmsManagerForSubscriptionId(targetSubId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    @Suppress("DEPRECATION")
    suspend fun sendReminders(reminders: List<ClientReminderEntity>, onSmsSent: suspend (Int) -> Unit) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ComptaPocket:SmsSenderWakeLock"
        )
        try {
            wakeLock?.acquire(180_000L) // 3 minutes de WakeLock garanti pour l'envoi complet

            val smsManager = getSafeSmsManager()
            val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
            val cabinetName = prefs.getString("cabinet_name", "Bureau de Comptabilité") ?: "Bureau de Comptabilité"

            for (reminder in reminders) {
                // Protection stricte anti-doublon en mémoire vive
                if (!SmsLock.tryClaim(reminder.id)) {
                    Log.d(TAG, "Rappel #${reminder.id} déjà en cours d'envoi par un autre processus. Ignoré.")
                    continue
                }

                try {
                    val message = if (reminder.texteSms.isNotBlank()) {
                        reminder.texteSms
                    } else {
                        buildDefaultMessage(reminder, cabinetName)
                    }

                    var emailSuccess = false

                    // 1. Envoi Email si présent (avec timeout pour ne jamais bloquer l'envoi SMS)
                    if (!reminder.email.isNullOrBlank()) {
                        val emailSender = com.comptapocket.sms.email.EmailSender(context)
                        try {
                            withTimeoutOrNull(15000L) {
                                emailSuccess = emailSender.sendEmail(
                                    toEmail = reminder.email,
                                    subject = "Rappel d'échéance fiscale & comptable - $cabinetName",
                                    body = message
                                )
                            } ?: run {
                                Log.w(TAG, "Timeout lors de l'envoi email pour ${reminder.clientName}")
                            }

                            if (emailSuccess) {
                                showToast("Email envoyé à ${reminder.clientName}")
                                if (reminder.firebaseKey != null) {
                                    try {
                                        val api = com.comptapocket.sms.network.RetrofitClient.getApi(context)
                                        api?.updateReminder(reminder.firebaseKey, reminder.copy(emailEnvoye = true))
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Erreur maj emailEnvoye API", e)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Erreur envoi email: ${e.message}", e)
                        }
                    }

                    // 2. Envoi SMS direct via SmsManager avec confirmation réseau
                    if (reminder.telephone.isNotBlank()) {
                        val cleanPhone = reminder.telephone.replace(Regex("[^0-9+]"), "")
                        if (cleanPhone.length >= 6) {
                            try {
                                val sentIntent = PendingIntent.getBroadcast(
                                    context,
                                    reminder.id,
                                    Intent(context, SmsSentReceiver::class.java).apply {
                                        action = SmsSentReceiver.ACTION_SMS_SENT
                                        putExtra("reminder_id", reminder.id)
                                    },
                                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                                )

                                val parts = smsManager.divideMessage(message)
                                if (parts.size > 1) {
                                    val sentIntents = ArrayList<PendingIntent>()
                                    for (i in parts.indices) {
                                        sentIntents.add(sentIntent)
                                    }
                                    smsManager.sendMultipartTextMessage(cleanPhone, null, parts, sentIntents, null)
                                    Log.i(TAG, "SMS Multipart transmis (${parts.size} parties) à $cleanPhone pour ${reminder.clientName}")
                                } else {
                                    smsManager.sendTextMessage(cleanPhone, null, message, sentIntent, null)
                                    Log.i(TAG, "SMS Simple transmis à $cleanPhone pour ${reminder.clientName}")
                                }

                                onSmsSent(reminder.id)
                                showToast("SMS transmis à l'antenne pour ${reminder.clientName}")
                                notifySmsSent(reminder.clientName, cleanPhone)

                                // Mise à jour Cloud si applicable
                                if (!reminder.firebaseKey.isNullOrBlank()) {
                                    FirebaseSyncManager.markSmsSentInCloud(reminder.firebaseKey)
                                }

                                delay(2500L) // Délai anti-congestion pour l'opérateur
                            } catch (e: Exception) {
                                Log.e(TAG, "Exception critique lors de l'envoi SMS à $cleanPhone: ${e.message}", e)
                                if (SimulationDateManager.isSimulationActive()) {
                                    Log.i(TAG, "Mode simulation actif: validation simulée pour #${reminder.id}")
                                    onSmsSent(reminder.id)
                                    showToast("Simulation: SMS validé pour ${reminder.clientName}")
                                } else {
                                    showToast("Erreur SMS: ${e.localizedMessage}")
                                    try {
                                        AppDatabase.getDatabase(context).clientReminderDao().resetSmsStatus(reminder.id)
                                    } catch (_: Exception) {}
                                }
                            }
                        } else if (SimulationDateManager.isSimulationActive()) {
                            Log.i(TAG, "Mode simulation actif avec numéro court: validation simulée pour #${reminder.id}")
                            onSmsSent(reminder.id)
                            showToast("Simulation: SMS validé pour ${reminder.clientName}")
                        } else {
                            Log.w(TAG, "Numéro invalide: ${reminder.telephone}")
                        }
                    } else if (emailSuccess || SimulationDateManager.isSimulationActive()) {
                        onSmsSent(reminder.id)
                        if (SimulationDateManager.isSimulationActive()) {
                            showToast("Simulation: Rappel validé pour ${reminder.clientName}")
                        }
                    }
                } finally {
                    SmsLock.release(reminder.id)
                }
            }
        } finally {
            if (wakeLock?.isHeld == true) {
                try {
                    wakeLock.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun buildDefaultMessage(reminder: ClientReminderEntity, cabinetName: String): String {
        val typeLabel = ReminderType.getLabel(reminder.reminderType)
        return "Bonjour ${reminder.clientName}, votre cabinet comptable $cabinetName vous rappelle que la date limite pour $typeLabel est fixée au ${reminder.dueDate}. Merci de finaliser vos démarches dans les délais."
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    private fun notifySmsSent(clientName: String, phone: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channelId = "comptapocket_sms_sent_channel"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Confirmations d'envoi SMS",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications lors de la transmission d'un rappel fiscal ou social"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("SMS d'échéance transmis 🚀")
                .setContentText("Rappel transmis pour $clientName ($phone)")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur affichage notification SMS: ${e.message}")
        }
    }
}
