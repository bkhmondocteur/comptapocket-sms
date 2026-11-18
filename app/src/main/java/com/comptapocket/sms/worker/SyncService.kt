package com.comptapocket.sms.worker

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.comptapocket.sms.MainActivity
import com.comptapocket.sms.data.local.AppDatabase
import com.comptapocket.sms.data.local.ClientCategory
import com.comptapocket.sms.data.local.ClientReminderEntity
import com.comptapocket.sms.data.local.ReminderType
import com.comptapocket.sms.network.RetrofitClient
import com.comptapocket.sms.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SyncService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(1, notification)

        rescheduleAllPendingReminders()
        startPolling()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private fun rescheduleAllPendingReminders() {
        scope.launch {
            try {
                val dao = AppDatabase.getDatabase(applicationContext).clientReminderDao()
                val unsent = dao.getUnsentReminders()
                Log.d("SyncService", "Reprogrammation des alarmes pour ${unsent.size} rappels en attente")
                for (item in unsent) {
                    scheduleExactAlarm(applicationContext, item.id, item.sendDate, item.sendTime)
                }
            } catch (e: Exception) {
                Log.e("SyncService", "Erreur reprogrammation des alarmes", e)
            }
        }
    }

    private fun checkAndAutoSendDueReminders() {
        val prefs = getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
        val autoSendEnabled = prefs.getBoolean("auto_send_enabled", true)
        if (!autoSendEnabled) return

        scope.launch {
            try {
                val dao = AppDatabase.getDatabase(applicationContext).clientReminderDao()
                val unsent = dao.getUnsentReminders()
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val now = System.currentTimeMillis()

                val dueToSend = unsent.filter { item ->
                    try {
                        val parsed = format.parse("${item.sendDate} ${item.sendTime}")
                        parsed != null && parsed.time <= now
                    } catch (e: Exception) {
                        false
                    }
                }

                if (dueToSend.isNotEmpty()) {
                    Log.d("SyncService", "Envoi automatique de ${dueToSend.size} SMS échus")
                    val smsSender = SmsSender(applicationContext)
                    smsSender.sendReminders(dueToSend) { id ->
                        dao.markSmsAsSent(id, System.currentTimeMillis())
                        val sentItem = dueToSend.find { it.id == id }
                        if (sentItem != null) {
                            scope.launch {
                                scheduleNextPeriodicReminder(applicationContext, sentItem)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SyncService", "Erreur vérification envois automatiques", e)
            }
        }
    }

    private fun startPolling() {
        scope.launch {
            var counter = 0
            while (true) {
                try {
                    val api = RetrofitClient.getApi(applicationContext)
                    val dao = AppDatabase.getDatabase(applicationContext).clientReminderDao()
                    if (api != null) {
                        val response = api.getReminders()
                        if (response.isSuccessful && response.body() != null) {
                            val remindersMap = response.body()!!
                            for ((key, remote) in remindersMap) {
                                val existing = dao.getReminderByFirebaseKey(key)
                                if (existing == null) {
                                    val newEntity = remote.copy(id = 0, firebaseKey = key, synced = true)
                                    val newId = dao.insertReminder(newEntity).toInt()
                                    scheduleExactAlarm(applicationContext, newId, remote.sendDate, remote.sendTime)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SyncService", "Erreur réseau sync", e)
                }

                counter++
                if (counter % 3 == 0) {
                    checkAndAutoSendDueReminders()
                }

                delay(10000)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "COMPTA_SYNC_CHANNEL",
                "ComptaPocket Synchronisation & Envois",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "COMPTA_SYNC_CHANNEL")
            .setContentTitle("ComptaPocket SMS Actif")
            .setContentText("Gestion des rappels fiscaux et sociaux en arrière-plan")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun scheduleExactAlarm(context: Context, id: Int, sendDate: String, sendTime: String) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, SmsAlarmReceiver::class.java).apply {
                putExtra("reminder_id", id)
            }
            val reqCode = id * 100
            val pendingIntent = PendingIntent.getBroadcast(
                context, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)

            try {
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val parsed = format.parse("$sendDate $sendTime") ?: return
                val targetMillis = parsed.time
                val currentMillis = System.currentTimeMillis()

                if (targetMillis > currentMillis) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
                    } else {
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
                    }
                    Log.d("SyncService", "Alarme programmée pour id=$id le $sendDate à $sendTime")
                }
            } catch (e: Exception) {
                Log.e("SyncService", "Erreur programmation alarme id=$id", e)
            }
        }

        fun cancelAlarm(context: Context, id: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, SmsAlarmReceiver::class.java).apply {
                putExtra("reminder_id", id)
            }
            val reqCode = id * 100
            val pendingIntent = PendingIntent.getBroadcast(
                context, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }

        // Programmer automatiquement le cycle de rappel périodique suivant dès qu'un SMS est envoyé
        suspend fun scheduleNextPeriodicReminder(context: Context, currentReminder: ClientReminderEntity) {
            try {
                val database = AppDatabase.getDatabase(context)
                val dao = database.clientReminderDao()

                // Vérifier s'il y a déjà un rappel futur en attente pour ce client et ce type
                val existing = dao.getPendingReminderForClientAndType(
                    currentReminder.clientName,
                    currentReminder.telephone,
                    currentReminder.reminderType
                )
                if (existing != null) {
                    Log.d("SyncService", "Un rappel futur existe déjà pour ${currentReminder.clientName} (${currentReminder.reminderType})")
                    return
                }

                val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val cal = Calendar.getInstance()

                try {
                    cal.time = format.parse(currentReminder.dueDate) ?: Calendar.getInstance().time
                } catch (e: Exception) {
                    cal.time = Calendar.getInstance().time
                }

                val daysBefore = prefs.getInt("default_days_before", 3)
                val defaultTime = prefs.getString("default_send_time", "09:00") ?: "09:00"

                val nextDueDateStr: String = when (currentReminder.reminderType) {
                    ReminderType.DECLARATION_MENSUELLE -> {
                        // Mois prochain (+1 mois)
                        cal.add(Calendar.MONTH, 1)
                        val dueDay = if (currentReminder.category == ClientCategory.ENTREPRISE) {
                            prefs.getInt("due_day_entreprise", 28)
                        } else {
                            prefs.getInt("due_day_physique", 15)
                        }
                        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                        cal.set(Calendar.DAY_OF_MONTH, minOf(dueDay, maxDay))
                        format.format(cal.time)
                    }
                    ReminderType.CNSS -> {
                        // Trimestre suivant (+3 mois)
                        cal.add(Calendar.MONTH, 3)
                        val cnssDay = prefs.getInt("cnss_due_day", 15)
                        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                        cal.set(Calendar.DAY_OF_MONTH, minOf(cnssDay, maxDay))
                        format.format(cal.time)
                    }
                    ReminderType.ACOMPTES_PROVISIONNELS -> {
                        val day = if (currentReminder.category == ClientCategory.ENTREPRISE) {
                            prefs.getInt("acomptes_day_entreprise", 28)
                        } else {
                            prefs.getInt("acomptes_day_physique", 25)
                        }
                        val curMonth = cal.get(Calendar.MONTH)
                        when {
                            curMonth <= Calendar.JUNE -> cal.set(Calendar.MONTH, Calendar.SEPTEMBER)
                            curMonth <= Calendar.SEPTEMBER -> cal.set(Calendar.MONTH, Calendar.DECEMBER)
                            else -> {
                                cal.add(Calendar.YEAR, 1)
                                cal.set(Calendar.MONTH, Calendar.JUNE)
                            }
                        }
                        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                        cal.set(Calendar.DAY_OF_MONTH, minOf(day, maxDay))
                        format.format(cal.time)
                    }
                    else -> {
                        cal.add(Calendar.MONTH, 1)
                        format.format(cal.time)
                    }
                }

                val sendCal = Calendar.getInstance()
                sendCal.time = format.parse(nextDueDateStr) ?: cal.time
                sendCal.add(Calendar.DAY_OF_YEAR, -daysBefore)
                val nextSendDateStr = format.format(sendCal.time)

                val nextSmsText = generateTemplateSms(
                    prefs = prefs,
                    clientName = currentReminder.clientName,
                    category = currentReminder.category,
                    reminderType = currentReminder.reminderType,
                    dueDate = nextDueDateStr
                )

                val nextEntity = ClientReminderEntity(
                    clientName = currentReminder.clientName,
                    category = currentReminder.category,
                    telephone = currentReminder.telephone,
                    email = currentReminder.email,
                    taxId = currentReminder.taxId,
                    reminderType = currentReminder.reminderType,
                    dueDate = nextDueDateStr,
                    sendDate = nextSendDateStr,
                    sendTime = defaultTime,
                    texteSms = nextSmsText,
                    smsEnvoye = false,
                    language = currentReminder.language
                )

                val newId = dao.insertReminder(nextEntity).toInt()
                scheduleExactAlarm(context, newId, nextSendDateStr, defaultTime)
                Log.d("SyncService", "Rappel périodique programmé pour ${currentReminder.clientName} ($nextDueDateStr, envoi le $nextSendDateStr à $defaultTime)")
            } catch (e: Exception) {
                Log.e("SyncService", "Erreur lors de la programmation du rappel périodique suivant", e)
            }
        }

        fun generateTemplateSms(
            prefs: SharedPreferences,
            clientName: String,
            category: String,
            reminderType: String,
            dueDate: String
        ): String {
            val cabinetName = prefs.getString("cabinet_name", "Bureau de Comptabilité") ?: "Bureau de Comptabilité"
            val cabinetPhone = prefs.getString("cabinet_phone", "") ?: ""

            val typeKey = when (reminderType) {
                ReminderType.CNSS -> "template_cnss"
                ReminderType.ACOMPTES_PROVISIONNELS -> "template_acomptes"
                else -> "template_mensuelle"
            }

            val defaultTemplate = when (reminderType) {
                ReminderType.CNSS -> "Bonjour [Nom], rappel de votre bureau comptable [Cabinet] : la date limite de paiement des cotisations CNSS est fixée au [Date_Echeance]. Merci de régler dans les délais pour éviter toute pénalité."
                ReminderType.ACOMPTES_PROVISIONNELS -> "Bonjour [Nom], votre bureau comptable [Cabinet] vous informe que l'échéance de l'acompte provisionnel est le [Date_Echeance]. Merci de nous contacter pour validation et dépôt."
                else -> "Bonjour [Nom], votre bureau comptable [Cabinet] vous rappelle que la date limite de votre déclaration mensuelle est fixée au [Date_Echeance]. Merci de nous faire parvenir vos documents."
            }

            val template = prefs.getString(typeKey, "")?.takeIf { it.isNotBlank() } ?: defaultTemplate
            val catLabel = ClientCategory.getLabel(category)
            val typeLabel = ReminderType.getLabel(reminderType)

            var result = template
                .replace("[Nom]", clientName, ignoreCase = true)
                .replace("{Nom du Client}", clientName, ignoreCase = true)
                .replace("[Cabinet]", cabinetName, ignoreCase = true)
                .replace("{Nom du Cabinet}", cabinetName, ignoreCase = true)
                .replace("[Date_Echeance]", dueDate, ignoreCase = true)
                .replace("[Date]", dueDate, ignoreCase = true)
                .replace("{Date}", dueDate, ignoreCase = true)
                .replace("[Categorie]", catLabel, ignoreCase = true)
                .replace("[Type]", typeLabel, ignoreCase = true)

            if (cabinetPhone.isNotBlank() && !result.contains(cabinetPhone)) {
                result += " Contact: $cabinetPhone"
            }

            return result
        }
    }
}
