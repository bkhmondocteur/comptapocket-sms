package com.comptapocket.sms.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import com.comptapocket.sms.data.local.ClientCategory
import com.comptapocket.sms.data.local.ClientReminderEntity
import com.comptapocket.sms.data.local.ReminderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SmsSender(private val context: Context) {

    @Suppress("DEPRECATION")
    suspend fun sendReminders(reminders: List<ClientReminderEntity>, onSmsSent: suspend (Int) -> Unit) {
        val smsManager: SmsManager = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            } else {
                SmsManager.getDefault()
            }
        } catch (e: Exception) {
            SmsManager.getDefault()
        }

        for (reminder in reminders) {
            val SENT_ACTION = "COMPTA_SMS_SENT_ACTION_${reminder.id}_${System.currentTimeMillis()}"
            val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
            val cabinetName = prefs.getString("cabinet_name", "Bureau de Comptabilité") ?: "Bureau de Comptabilité"

            val message = if (reminder.texteSms.isNotBlank()) {
                reminder.texteSms
            } else {
                buildDefaultMessage(reminder, cabinetName)
            }

            var smsSuccess = false
            var emailSuccess = false

            // 1. Envoi Email si présent
            if (!reminder.email.isNullOrBlank()) {
                val emailSender = com.comptapocket.sms.email.EmailSender(context)
                try {
                    val subject = "Rappel d'échéance fiscale & comptable - $cabinetName"
                    emailSuccess = emailSender.sendEmail(
                        toEmail = reminder.email,
                        subject = subject,
                        body = message
                    )
                    if (emailSuccess) {
                        showToast("Email envoyé à ${reminder.clientName}")
                        CoroutineScope(Dispatchers.IO).launch {
                            if (reminder.firebaseKey != null) {
                                try {
                                    val api = com.comptapocket.sms.network.RetrofitClient.getApi(context)
                                    if (api != null) {
                                        val updated = reminder.copy(emailEnvoye = true)
                                        api.updateReminder(reminder.firebaseKey, updated)
                                    }
                                } catch (e: Exception) {
                                    Log.e("SmsSender", "Erreur maj emailEnvoye API", e)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SmsSender", "Erreur d'envoi d'email à ${reminder.clientName}", e)
                }
            }

            // 2. Envoi SMS si téléphone renseigné
            if (reminder.telephone.isNotBlank()) {
                val cleanPhone = reminder.telephone.replace(Regex("[\\s-]"), "")
                try {
                    val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }

                    val intent = Intent(SENT_ACTION)
                    intent.setPackage(context.packageName)

                    val sentIntent = PendingIntent.getBroadcast(context, reminder.id, intent, pendingFlags)

                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, intent: Intent?) {
                            when (resultCode) {
                                Activity.RESULT_OK -> {
                                    Log.d("SmsSender", "SMS envoyé avec succès à ${reminder.clientName}")
                                    showToast("SMS envoyé avec succès à ${reminder.clientName}")
                                    CoroutineScope(Dispatchers.IO).launch {
                                        if (reminder.firebaseKey != null) {
                                            try {
                                                val api = com.comptapocket.sms.network.RetrofitClient.getApi(context)
                                                if (api != null) {
                                                    val updated = reminder.copy(smsEnvoye = true)
                                                    api.updateReminder(reminder.firebaseKey, updated)
                                                }
                                            } catch (e: Exception) {
                                                Log.e("SmsSender", "Erreur API", e)
                                            }
                                        }
                                        onSmsSent(reminder.id)
                                    }
                                }
                                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> showToast("Échec SMS générique pour ${reminder.clientName}")
                                SmsManager.RESULT_ERROR_NO_SERVICE -> showToast("Aucun réseau pour envoyer le SMS à ${reminder.clientName}")
                                else -> showToast("Erreur d'envoi ($resultCode) pour ${reminder.clientName}")
                            }
                            try {
                                context.unregisterReceiver(this)
                            } catch (e: Exception) {}
                        }
                    }

                    androidx.core.content.ContextCompat.registerReceiver(
                        context, receiver, IntentFilter(SENT_ACTION), androidx.core.content.ContextCompat.RECEIVER_EXPORTED
                    )

                    val parts = smsManager.divideMessage(message)
                    if (parts.size > 1) {
                        val sentIntents = ArrayList<PendingIntent?>()
                        for (i in parts.indices) {
                            sentIntents.add(if (i == parts.size - 1) sentIntent else null)
                        }
                        smsManager.sendMultipartTextMessage(cleanPhone, null, parts, sentIntents, null)
                    } else {
                        smsManager.sendTextMessage(cleanPhone, null, message, sentIntent, null)
                    }
                    smsSuccess = true
                    delay(3000)
                } catch (e: Exception) {
                    Log.e("SmsSender", "Erreur critique SMS ${reminder.clientName}", e)
                    showToast("Erreur SMS: ${e.localizedMessage}")
                }
            }

            // Si seul email était présent
            if (emailSuccess && reminder.telephone.isBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    onSmsSent(reminder.id)
                }
            }
        }
    }

    private fun buildDefaultMessage(reminder: ClientReminderEntity, cabinetName: String): String {
        val catLabel = ClientCategory.getLabel(reminder.category)
        val typeLabel = ReminderType.getLabel(reminder.reminderType)
        return "Bonjour ${reminder.clientName}, votre cabinet comptable $cabinetName vous rappelle que la date limite pour $typeLabel est fixée au ${reminder.dueDate}. Merci de finaliser vos démarches dans les délais."
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
