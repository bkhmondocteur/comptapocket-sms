package com.comptapocket.sms.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.comptapocket.sms.data.local.AppDatabase
import com.comptapocket.sms.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getIntExtra("reminder_id", -1)
        if (reminderId == -1) {
            Log.e("SmsAlarmReceiver", "ID de rappel manquant")
            return
        }

        val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
        val autoSendEnabled = prefs.getBoolean("auto_send_enabled", true)
        if (!autoSendEnabled) {
            Log.d("SmsAlarmReceiver", "Envoi automatique désactivé dans les réglages pour rappel $reminderId")
            return
        }

        Log.d("SmsAlarmReceiver", "Alarme déclenchée pour le rappel $reminderId")
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = AppDatabase.getDatabase(context)
                val dao = database.clientReminderDao()

                val reminder = dao.getReminderById(reminderId)
                if (reminder != null && !reminder.smsEnvoye) {
                    val smsSender = SmsSender(context)
                    smsSender.sendReminders(listOf(reminder)) { id ->
                        dao.markSmsAsSent(id, System.currentTimeMillis())
                        SyncService.scheduleNextPeriodicReminder(context, reminder)
                    }
                    Log.d("SmsAlarmReceiver", "SMS envoyé avec succès pour l'échéance $reminderId (${reminder.clientName})")
                } else {
                    Log.d("SmsAlarmReceiver", "Rappel $reminderId introuvable ou déjà envoyé.")
                }
            } catch (e: Exception) {
                Log.e("SmsAlarmReceiver", "Erreur lors de l'alarme SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
