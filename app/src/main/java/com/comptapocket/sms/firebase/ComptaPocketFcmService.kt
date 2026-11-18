package com.comptapocket.sms.firebase

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.comptapocket.sms.data.local.AppDatabase
import com.comptapocket.sms.data.local.ClientCategory
import com.comptapocket.sms.data.local.ClientReminderEntity
import com.comptapocket.sms.data.local.ReminderType
import com.comptapocket.sms.sms.SmsSender
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ComptaPocketFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "Nouveau Token FCM généré: $token")

        val prefs = applicationContext.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()

        // Synchronisation automatique vers Firestore
        FirebaseSyncManager.saveDeviceToken(applicationContext, token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.i(TAG, "Push FCM reçu ! De: ${remoteMessage.from}, ID: ${remoteMessage.messageId}")

        val data = remoteMessage.data
        if (data.isEmpty()) {
            Log.w(TAG, "Message FCM reçu sans données utiles (data payload vide)")
            return
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ComptaPocket:FcmWakeLock"
        )
        wakeLock?.acquire(45_000L) // 45 secondes de WakeLock garanti pour réveil complet du processeur

        try {
            val action = data["action"] ?: "SEND_SMS"
            val phone = data["telephone"] ?: data["phone"] ?: ""
            val clientName = data["clientName"] ?: data["nomClient"] ?: "Client"
            val category = data["category"] ?: ClientCategory.PERSONNE_PHYSIQUE
            val reminderType = data["reminderType"] ?: ReminderType.DECLARATION_MENSUELLE
            val message = data["message"] ?: data["texteSms"] ?: ""
            val dueDate = data["dueDate"] ?: ""
            val sendDate = data["sendDate"] ?: ""
            val sendTime = data["sendTime"] ?: "09:00"
            val language = data["language"] ?: "fr"
            val cloudKey = data["firebaseKey"] ?: remoteMessage.messageId ?: ""
            val reminderId = (data["reminderId"] ?: data["id"])?.toIntOrNull() ?: 0

            Log.i(TAG, "Ordre FCM reçu: action=$action, client=$clientName, tel=$phone, id=$reminderId")

            if (phone.isBlank()) {
                Log.w(TAG, "Numéro de téléphone absent dans le payload FCM.")
                return
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(applicationContext)
                    val dao = db.clientReminderDao()

                    // 1. Recherche par ID local ou par clé Cloud
                    var existing = if (reminderId > 0) dao.getReminderById(reminderId) else null
                    if (existing == null && cloudKey.isNotBlank()) {
                        existing = dao.getReminderByFirebaseKey(cloudKey)
                    }

                    // 2. Vérification anti-doublon stricte
                    if (existing != null) {
                        if (existing.smsEnvoye) {
                            Log.i(TAG, "Rappel #${existing.id} déjà marqué envoyé en base locale. Annulation.")
                            return@launch
                        }

                        // Verrou atomique SQLite
                        val claimed = dao.claimReminderForSending(existing.id)
                        if (claimed == 0) {
                            Log.i(TAG, "Rappel #${existing.id} déjà verrouillé par un autre processus.")
                            return@launch
                        }
                    }

                    // 3. Préparer l'entité à expédier
                    val reminderToSend = existing ?: run {
                        val newEntity = ClientReminderEntity(
                            id = 0,
                            clientName = clientName,
                            category = category,
                            telephone = phone,
                            reminderType = reminderType,
                            dueDate = dueDate,
                            sendDate = sendDate,
                            sendTime = sendTime,
                            texteSms = message,
                            smsEnvoye = true, // Marqué envoyé d'emblée pour verrou
                            language = language,
                            firebaseKey = cloudKey,
                            synced = true
                        )
                        val insertedId = dao.insertReminder(newEntity).toInt()
                        newEntity.copy(id = insertedId)
                    }

                    // 4. Déclenchement de l'envoi physique par la carte SIM
                    val smsSender = SmsSender(applicationContext)
                    smsSender.sendReminders(listOf(reminderToSend.copy(smsEnvoye = false))) { sentId ->
                        dao.markSmsAsSent(sentId)
                        if (cloudKey.isNotBlank()) {
                            FirebaseSyncManager.markSmsSentInCloud(cloudKey)
                        }
                    }

                    Log.i(TAG, "Envoi SMS via carte SIM orchestré avec succès pour $clientName ($phone)")
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur lors du traitement FCM SMS: ${e.message}", e)
                }
            }
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }

    companion object {
        private const val TAG = "ComptaPocketFcmService"
    }
}
