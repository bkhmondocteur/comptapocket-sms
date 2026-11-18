package com.comptapocket.sms.firebase

import android.content.Context
import android.os.Build
import android.util.Log
import com.comptapocket.sms.data.local.ClientReminderEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale

object FirebaseSyncManager {

    private const val TAG = "FirebaseSyncManager"
    const val COLLECTION_DEVICES = "comptapocket_devices"
    const val COLLECTION_REMINDERS = "comptapocket_reminders"
    const val DEVICE_DOC_ID = "accountant_device"

    /**
     * Enregistre le Token FCM du smartphone dans Firestore.
     * Permet à Vercel Cloud d'envoyer le push réveil haute priorité au téléphone.
     */
    fun saveDeviceToken(context: Context, token: String) {
        try {
            val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
            val cabinetName = prefs.getString("cabinet_name", "Bureau de Comptabilité") ?: "Bureau de Comptabilité"

            val deviceData = hashMapOf(
                "fcmToken" to token,
                "cabinetName" to cabinetName,
                "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "androidVersion" to Build.VERSION.RELEASE,
                "lastUpdated" to System.currentTimeMillis()
            )

            val db = FirebaseFirestore.getInstance()
            db.collection(COLLECTION_DEVICES).document(DEVICE_DOC_ID)
                .set(deviceData, SetOptions.merge())
                .addOnSuccessListener {
                    Log.i(TAG, "Token FCM synchronisé avec succès sur Firestore !")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Échec de synchronisation du token FCM: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur saveDeviceToken: ${e.message}", e)
        }
    }

    /**
     * Synchronise un rappel de déclaration vers Firestore pour déclenchement Vercel Cloud.
     */
    suspend fun syncReminderToCloud(context: Context, reminder: ClientReminderEntity) {
        try {
            val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("fcm_token", "") ?: ""

            // Calcul du timestamp d'envoi à partir de sendDate ("AAAA-MM-JJ") et sendTime ("HH:MM")
            val epochMs = try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val timeStr = if (reminder.sendTime.length == 5) reminder.sendTime else "09:00"
                sdf.parse("${reminder.sendDate} $timeStr")?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }

            val docKey = reminder.firebaseKey ?: "reminder_${reminder.id}_${System.currentTimeMillis()}"

            val data = hashMapOf(
                "localId" to reminder.id,
                "clientName" to reminder.clientName,
                "category" to reminder.category,
                "telephone" to reminder.telephone,
                "email" to (reminder.email ?: ""),
                "taxId" to (reminder.taxId ?: ""),
                "reminderType" to reminder.reminderType,
                "dueDate" to reminder.dueDate,
                "sendDate" to reminder.sendDate,
                "sendTime" to reminder.sendTime,
                "texteSms" to reminder.texteSms,
                "language" to reminder.language,
                "smsEnvoye" to reminder.smsEnvoye,
                "reminderTimestamp" to epochMs,
                "targetFcmToken" to token,
                "updatedAt" to System.currentTimeMillis(),
                "source" to "android"
            )

            val db = FirebaseFirestore.getInstance()
            db.collection(COLLECTION_REMINDERS).document(docKey)
                .set(data, SetOptions.merge())
                .await()

            Log.i(TAG, "Rappel #${reminder.id} (${reminder.clientName} - ${reminder.reminderType}) synchronisé sur Firestore ($docKey)")
        } catch (e: Exception) {
            Log.w(TAG, "Erreur syncReminderToCloud: ${e.message}")
        }
    }

    /**
     * Met à jour le statut du SMS dans Firestore dès qu'il est envoyé.
     */
    fun markSmsSentInCloud(docKey: String) {
        try {
            if (docKey.isBlank()) return
            val db = FirebaseFirestore.getInstance()
            db.collection(COLLECTION_REMINDERS).document(docKey)
                .update("smsEnvoye", true, "sentAt", System.currentTimeMillis())
                .addOnSuccessListener {
                    Log.i(TAG, "Statut SMS mis à jour dans le Cloud pour $docKey")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Erreur markSmsSentInCloud: ${e.message}")
        }
    }
}
