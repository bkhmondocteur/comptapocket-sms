package com.comptapocket.sms.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import com.comptapocket.sms.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsSentReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SMS_SENT = "com.comptapocket.sms.SMS_SENT"
        private const val TAG = "SmsSentReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getIntExtra("reminder_id", -1)
        if (reminderId == -1) return

        val resultCode = resultCode
        Log.i(TAG, "Confirmation modem GSM reçue pour Rappel #$reminderId | Code résultat: $resultCode")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getDatabase(context.applicationContext).clientReminderDao()
                if (com.comptapocket.sms.simulation.SimulationDateManager.isSimulationActive()) {
                    Log.i(TAG, "Mode simulation actif : conservation du statut envoyé pour Rappel #$reminderId")
                    dao.markSmsAsSent(reminderId)
                    return@launch
                }

                if (resultCode == Activity.RESULT_OK) {
                    Log.i(TAG, "SUCCÈS RÉSEAU : SMS transmis à l'antenne opérateur pour Rappel #$reminderId")
                    dao.markSmsAsSent(reminderId)
                } else {
                    val errorDetail = when (resultCode) {
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Échec générique (Erreur opérateur ou SIM)"
                        SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio éteinte (Mode avion ou réseau coupé)"
                        SmsManager.RESULT_ERROR_NULL_PDU -> "PDU nul"
                        SmsManager.RESULT_ERROR_NO_SERVICE -> "Aucun service / Hors couverture"
                        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Limite d'envoi SMS atteinte"
                        else -> "Code d'erreur réseau $resultCode"
                    }
                    Log.e(TAG, "ÉCHEC MODEM GSM pour Rappel #$reminderId : $errorDetail")
                    // Réinitialiser le statut dans la base pour permettre une nouvelle tentative
                    dao.resetSmsStatus(reminderId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors de la mise à jour après callback SMS: ${e.message}", e)
            } finally {
                try {
                    pendingResult.finish()
                } catch (_: Exception) {}
            }
        }
    }
}
