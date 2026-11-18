package com.comptapocket.sms.presentation

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comptapocket.sms.data.local.AppDatabase
import com.comptapocket.sms.data.local.ClientCategory
import com.comptapocket.sms.data.local.ClientEntity
import com.comptapocket.sms.data.local.ClientReminderEntity
import com.comptapocket.sms.data.local.ReminderType
import com.comptapocket.sms.network.RetrofitClient
import com.comptapocket.sms.simulation.SimulationDateManager
import com.comptapocket.sms.sms.SmsSender
import com.comptapocket.sms.worker.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class ScheduleResult(
    val dueDate: String,
    val sendDate: String,
    val sendTime: String,
    val shouldSendImmediately: Boolean
)

class ClientReminderViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getDatabase(application).clientReminderDao()
    private val smsSender = SmsSender(application)
    private val prefs = application.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)

    init {
        SimulationDateManager.init(application)
    }

    // ==========================================
    // 1. GESTION DE LA LISTE DES RAPPELS SMS
    // ==========================================
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _categoryFilter = MutableStateFlow("ALL") // "ALL", "ENTREPRISE", "PERSONNE_PHYSIQUE"
    val categoryFilter: StateFlow<String> = _categoryFilter.asStateFlow()

    private val _typeFilter = MutableStateFlow("ALL") // "ALL", "DECLARATION_MENSUELLE", "CNSS", "ACOMPTES_PROVISIONNELS"
    val typeFilter: StateFlow<String> = _typeFilter.asStateFlow()

    private val _statusFilter = MutableStateFlow("ALL") // "ALL", "PENDING", "SENT"
    val statusFilter: StateFlow<String> = _statusFilter.asStateFlow()

    private val rawReminders = dao.getAllReminders()

    // 1. Rappels actifs du cycle :
    // - Réagit instantanément à tout changement de date simulée ou réelle.
    // - Dès que le nouveau mois commence, les SMS envoyés du mois précédent sont enlevés.
    // - Les SMS de CNSS et AP n'apparaissent dès que le mois de l'échéance entre en vigueur.
    val activeReminders: StateFlow<List<ClientReminderEntity>> = combine(
        rawReminders,
        SimulationDateManager.simulatedDateFlow
    ) { list, _ ->
        val now = SimulationDateManager.getTodayCalendar()
        val currentYear = now.get(Calendar.YEAR)
        val currentMonth = now.get(Calendar.MONTH) + 1 // 1-12
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        list.filter { item ->
            var itemYear = currentYear
            var itemMonth = currentMonth
            try {
                val parsed = dateFormat.parse(item.dueDate) ?: dateFormat.parse(item.sendDate)
                if (parsed != null) {
                    val cal = Calendar.getInstance().apply { time = parsed }
                    itemYear = cal.get(Calendar.YEAR)
                    itemMonth = cal.get(Calendar.MONTH) + 1
                }
            } catch (e: Exception) {}

            // Règle 1 : Lorsque le nouveau mois commence, les SMS envoyés du mois précédent sont enlevés
            val isFromPastMonth = (itemYear < currentYear) || (itemYear == currentYear && itemMonth < currentMonth)
            if (item.smsEnvoye && isFromPastMonth) {
                return@filter false
            }

            // Règle 2 : Cotisations CNSS et AP n'apparaissent dès que le mois de l'échéance entre en vigueur
            val isFutureMonth = (itemYear > currentYear) || (itemYear == currentYear && itemMonth > currentMonth)
            if (isFutureMonth && (item.reminderType == ReminderType.CNSS || item.reminderType == ReminderType.ACOMPTES_PROVISIONNELS)) {
                return@filter false
            }

            true
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredReminders: StateFlow<List<ClientReminderEntity>> = combine(
        activeReminders,
        _searchQuery,
        _categoryFilter,
        _typeFilter,
        _statusFilter
    ) { list, query, category, type, status ->
        list.filter { item ->
            val matchesQuery = query.isBlank() ||
                    item.clientName.contains(query, ignoreCase = true) ||
                    item.telephone.contains(query, ignoreCase = true) ||
                    (item.taxId?.contains(query, ignoreCase = true) == true)

            val matchesCategory = category == "ALL" || item.category == category
            val matchesType = type == "ALL" || item.reminderType == type
            val matchesStatus = when (status) {
                "PENDING" -> !item.smsEnvoye
                "SENT" -> item.smsEnvoye
                else -> true
            }

            matchesQuery && matchesCategory && matchesType && matchesStatus
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allReminders: StateFlow<List<ClientReminderEntity>> = rawReminders.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setCategoryFilter(cat: String) {
        _categoryFilter.value = cat
    }

    fun setTypeFilter(type: String) {
        _typeFilter.value = type
    }

    fun setStatusFilter(status: String) {
        _statusFilter.value = status
    }

    // ==========================================
    // 2. GESTION DE LA BASE DE DONNÉES DES CLIENTS
    // ==========================================
    val rawClients = dao.getAllClients()

    private val _clientSearchQuery = MutableStateFlow("")
    val clientSearchQuery: StateFlow<String> = _clientSearchQuery.asStateFlow()

    private val _clientCategoryFilter = MutableStateFlow("ALL")
    val clientCategoryFilter: StateFlow<String> = _clientCategoryFilter.asStateFlow()

    val allClients: StateFlow<List<ClientEntity>> = rawClients.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val filteredClients: StateFlow<List<ClientEntity>> = combine(
        rawClients,
        _clientSearchQuery,
        _clientCategoryFilter
    ) { clients, query, cat ->
        clients.filter { c ->
            val matchesQuery = query.isBlank() ||
                    c.clientName.contains(query, ignoreCase = true) ||
                    c.telephone.contains(query, ignoreCase = true) ||
                    (c.taxId?.contains(query, ignoreCase = true) == true) ||
                    (c.email?.contains(query, ignoreCase = true) == true)

            val matchesCat = cat == "ALL" || c.category == cat

            matchesQuery && matchesCat
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setClientSearchQuery(q: String) {
        _clientSearchQuery.value = q
    }

    fun setClientCategoryFilter(cat: String) {
        _clientCategoryFilter.value = cat
    }

    init {
        // Synchronisation initiale : Si la table Clients est vide, y importer les clients uniques des rappels
        viewModelScope.launch(Dispatchers.IO) {
            val list = dao.getUnsentReminders()
            for (r in list) {
                val found = dao.getClientByPhone(r.telephone) ?: dao.getClientByName(r.clientName)
                if (found == null) {
                    dao.insertClient(
                        ClientEntity(
                            clientName = r.clientName,
                            category = r.category,
                            telephone = r.telephone,
                            email = r.email,
                            taxId = r.taxId
                        )
                    )
                }
            }
        }
    }

    // Ajouter un client dans la base de données
    fun addClientToDatabase(
        clientName: String,
        category: String,
        telephone: String,
        email: String?,
        taxId: String?,
        address: String? = null,
        notes: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val newClient = ClientEntity(
                clientName = clientName.trim(),
                category = category,
                telephone = telephone.trim(),
                email = email?.trim()?.takeIf { it.isNotBlank() },
                taxId = taxId?.trim()?.takeIf { it.isNotBlank() },
                address = address?.trim()?.takeIf { it.isNotBlank() },
                notes = notes?.trim()?.takeIf { it.isNotBlank() }
            )
            dao.insertClient(newClient)
        }
    }

    // Modifier les données d'un client dans la base de données (avec mise à jour en cascade de ses rappels)
    fun updateClientInDatabase(
        id: Int,
        oldName: String,
        oldPhone: String,
        newName: String,
        newCategory: String,
        newPhone: String,
        newEmail: String?,
        newTaxId: String?,
        address: String? = null,
        notes: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = ClientEntity(
                id = id,
                clientName = newName.trim(),
                category = newCategory,
                telephone = newPhone.trim(),
                email = newEmail?.trim()?.takeIf { it.isNotBlank() },
                taxId = newTaxId?.trim()?.takeIf { it.isNotBlank() },
                address = address?.trim()?.takeIf { it.isNotBlank() },
                notes = notes?.trim()?.takeIf { it.isNotBlank() }
            )
            dao.updateClient(updated)

            // Mettre à jour en cascade tous les rappels existants pour ce client
            dao.updateRemindersForClient(
                oldName = oldName,
                oldPhone = oldPhone,
                newName = newName.trim(),
                newCategory = newCategory,
                newPhone = newPhone.trim(),
                newEmail = newEmail?.trim()?.takeIf { it.isNotBlank() },
                newTaxId = newTaxId?.trim()?.takeIf { it.isNotBlank() }
            )

            // Recalculer le texte des SMS pour les rappels non envoyés afin d'intégrer le nouveau nom/catégorie
            val reminders = dao.getRemindersForClient(newPhone.trim(), newName.trim())
            for (rem in reminders) {
                if (!rem.smsEnvoye) {
                    val updatedText = generateSmsText(newName.trim(), newCategory, rem.reminderType, rem.dueDate, rem.language)
                    dao.updateReminder(rem.copy(texteSms = updatedText))
                }
            }
        }
    }

    // Supprimer un client de la base de données
    fun deleteClientFromDatabase(client: ClientEntity, deleteReminders: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteClient(client.id)
            if (deleteReminders) {
                dao.deleteRemindersForClient(client.clientName, client.telephone)
            }
        }
    }

    suspend fun getRemindersForClient(client: ClientEntity): List<ClientReminderEntity> {
        return withContext(Dispatchers.IO) {
            dao.getRemindersForClient(client.telephone, client.clientName)
        }
    }

    // ==========================================
    // 3. LOGIQUE D'ÉCHÉANCES & ENVOIS AUTOMATIQUES
    // ==========================================
    fun generateSmsText(
        clientName: String,
        category: String,
        reminderType: String,
        dueDate: String,
        language: String = "fr"
    ): String {
        return SyncService.generateTemplateSms(prefs, clientName, category, reminderType, dueDate)
    }

    fun computeInitialSchedule(category: String, reminderType: String): ScheduleResult {
        val today = SimulationDateManager.getTodayCalendar()
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val todayDateStr = format.format(today.time)
        val currentTimeStr = timeFormat.format(today.time)
        val defaultTime = getDefaultSendTime()
        val daysBefore = prefs.getInt("default_days_before", 3)

        return when (reminderType) {
            ReminderType.DECLARATION_MENSUELLE -> {
                val dueDay = if (category == ClientCategory.ENTREPRISE) {
                    prefs.getInt("due_day_entreprise", 28)
                } else {
                    prefs.getInt("due_day_physique", 15)
                }
                val currentDay = today.get(Calendar.DAY_OF_MONTH)

                if (currentDay <= dueDay) {
                    val cal = today.clone() as Calendar
                    val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, minOf(dueDay, maxDay))
                    val due = format.format(cal.time)
                    ScheduleResult(
                        dueDate = due,
                        sendDate = todayDateStr,
                        sendTime = currentTimeStr,
                        shouldSendImmediately = true
                    )
                } else {
                    val cal = (today.clone() as Calendar).apply {
                        add(Calendar.MONTH, 1)
                    }
                    val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, minOf(dueDay, maxDay))
                    val due = format.format(cal.time)
                    val sendCal = (cal.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_YEAR, -daysBefore)
                    }
                    ScheduleResult(
                        dueDate = due,
                        sendDate = format.format(sendCal.time),
                        sendTime = defaultTime,
                        shouldSendImmediately = false
                    )
                }
            }
            ReminderType.CNSS -> {
                val cnssDay = prefs.getInt("cnss_due_day", 15)
                val currentYear = today.get(Calendar.YEAR)
                val candidates = listOf(
                    Calendar.APRIL to currentYear,
                    Calendar.JULY to currentYear,
                    Calendar.OCTOBER to currentYear,
                    Calendar.JANUARY to currentYear + 1
                )

                var matchedCal: Calendar? = null
                for ((qMonth, qYear) in candidates) {
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, qYear)
                        set(Calendar.MONTH, qMonth)
                        set(Calendar.DAY_OF_MONTH, cnssDay)
                        set(Calendar.HOUR_OF_DAY, 23)
                        set(Calendar.MINUTE, 59)
                    }
                    if (cal.timeInMillis >= today.timeInMillis) {
                        matchedCal = cal
                        break
                    }
                }
                val finalTarget: Calendar = matchedCal ?: Calendar.getInstance().apply {
                    set(Calendar.YEAR, currentYear + 1)
                    set(Calendar.MONTH, Calendar.JANUARY)
                    set(Calendar.DAY_OF_MONTH, cnssDay)
                }

                val due = format.format(finalTarget.time)
                val plannedSendCal = Calendar.getInstance().apply {
                    time = finalTarget.time
                    add(Calendar.DAY_OF_YEAR, -daysBefore)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                }

                if (today.timeInMillis >= plannedSendCal.timeInMillis && today.timeInMillis <= finalTarget.timeInMillis) {
                    ScheduleResult(
                        dueDate = due,
                        sendDate = todayDateStr,
                        sendTime = currentTimeStr,
                        shouldSendImmediately = true
                    )
                } else {
                    ScheduleResult(
                        dueDate = due,
                        sendDate = format.format(plannedSendCal.time),
                        sendTime = defaultTime,
                        shouldSendImmediately = false
                    )
                }
            }
            ReminderType.ACOMPTES_PROVISIONNELS -> {
                val day = if (category == ClientCategory.ENTREPRISE) {
                    prefs.getInt("acomptes_day_entreprise", 28)
                } else {
                    prefs.getInt("acomptes_day_physique", 25)
                }
                val currentYear = today.get(Calendar.YEAR)
                val candidates = listOf(
                    Calendar.JUNE to currentYear,
                    Calendar.SEPTEMBER to currentYear,
                    Calendar.DECEMBER to currentYear,
                    Calendar.JUNE to currentYear + 1
                )

                var matchedCal: Calendar? = null
                for ((aMonth, aYear) in candidates) {
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, aYear)
                        set(Calendar.MONTH, aMonth)
                        set(Calendar.DAY_OF_MONTH, day)
                        set(Calendar.HOUR_OF_DAY, 23)
                        set(Calendar.MINUTE, 59)
                    }
                    if (cal.timeInMillis >= today.timeInMillis) {
                        matchedCal = cal
                        break
                    }
                }
                val finalTarget: Calendar = matchedCal ?: Calendar.getInstance().apply {
                    set(Calendar.YEAR, currentYear + 1)
                    set(Calendar.MONTH, Calendar.JUNE)
                    set(Calendar.DAY_OF_MONTH, day)
                }

                val due = format.format(finalTarget.time)
                val plannedSendCal = Calendar.getInstance().apply {
                    time = finalTarget.time
                    add(Calendar.DAY_OF_YEAR, -daysBefore)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                }

                if (today.timeInMillis >= plannedSendCal.timeInMillis && today.timeInMillis <= finalTarget.timeInMillis) {
                    ScheduleResult(
                        dueDate = due,
                        sendDate = todayDateStr,
                        sendTime = currentTimeStr,
                        shouldSendImmediately = true
                    )
                } else {
                    ScheduleResult(
                        dueDate = due,
                        sendDate = format.format(plannedSendCal.time),
                        sendTime = defaultTime,
                        shouldSendImmediately = false
                    )
                }
            }
            else -> {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, 7)
                val due = format.format(cal.time)
                val send = computeDefaultSendDate(due)
                ScheduleResult(
                    dueDate = due,
                    sendDate = send,
                    sendTime = defaultTime,
                    shouldSendImmediately = false
                )
            }
        }
    }

    fun computeDefaultDueDate(category: String, reminderType: String): String {
        return computeInitialSchedule(category, reminderType).dueDate
    }

    fun computeDefaultSendDate(dueDate: String, daysBefore: Int? = null): String {
        try {
            val days = daysBefore ?: prefs.getInt("default_days_before", 3)
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val date = format.parse(dueDate) ?: return dueDate
            val cal = Calendar.getInstance()
            cal.time = date
            cal.add(Calendar.DAY_OF_YEAR, -days)
            val today = SimulationDateManager.getTodayCalendar()
            if (cal.timeInMillis < today.timeInMillis) {
                return format.format(today.time)
            }
            return format.format(cal.time)
        } catch (e: Exception) {
            return dueDate
        }
    }

    fun getDefaultSendTime(): String {
        return prefs.getString("default_send_time", "09:00") ?: "09:00"
    }

    // Ajouter un rappel pour un client (et s'assurer de son existence dans la base de données Clients)
    fun addReminder(
        clientName: String,
        category: String,
        telephone: String,
        email: String,
        taxId: String,
        reminderType: String,
        dueDate: String,
        sendDate: String,
        sendTime: String,
        texteSms: String,
        language: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. S'assurer que le client est enregistré dans la table Clients
            val existingClient = dao.getClientByPhone(telephone.trim()) ?: dao.getClientByName(clientName.trim())
            if (existingClient == null) {
                dao.insertClient(
                    ClientEntity(
                        clientName = clientName.trim(),
                        category = category,
                        telephone = telephone.trim(),
                        email = email.trim().takeIf { it.isNotBlank() },
                        taxId = taxId.trim().takeIf { it.isNotBlank() }
                    )
                )
            }

            // 2. Traitement des échéances
            val typesToAdd = if (reminderType == ReminderType.PACK_COMPLET) {
                listOf(
                    ReminderType.DECLARATION_MENSUELLE,
                    ReminderType.CNSS,
                    ReminderType.ACOMPTES_PROVISIONNELS
                )
            } else {
                listOf(reminderType)
            }

            for (type in typesToAdd) {
                val schedule = computeInitialSchedule(category, type)

                val itemDueDate = if (dueDate.isNotBlank() && reminderType != ReminderType.PACK_COMPLET) {
                    dueDate
                } else {
                    schedule.dueDate
                }

                val itemSendDate = if (sendDate.isNotBlank() && reminderType != ReminderType.PACK_COMPLET) {
                    sendDate
                } else {
                    schedule.sendDate
                }

                val itemSendTime = if (sendTime.isNotBlank() && reminderType != ReminderType.PACK_COMPLET) {
                    sendTime
                } else {
                    schedule.sendTime
                }

                val itemText = if (reminderType == ReminderType.PACK_COMPLET || texteSms.isBlank()) {
                    generateSmsText(clientName, category, type, itemDueDate, language)
                } else {
                    texteSms
                }

                var firebaseKey: String? = null
                val newEntity = ClientReminderEntity(
                    clientName = clientName,
                    category = category,
                    telephone = telephone,
                    email = email.takeIf { it.isNotBlank() },
                    taxId = taxId.takeIf { it.isNotBlank() },
                    reminderType = type,
                    dueDate = itemDueDate,
                    sendDate = itemSendDate,
                    sendTime = itemSendTime,
                    texteSms = itemText,
                    smsEnvoye = false,
                    language = language
                )

                try {
                    val api = RetrofitClient.getApi(getApplication())
                    if (api != null) {
                        val res = api.createReminder(newEntity)
                        if (res.isSuccessful) {
                            firebaseKey = res.body()?.id
                        }
                    }
                } catch (e: Exception) {}

                val finalItem = newEntity.copy(firebaseKey = firebaseKey, synced = firebaseKey != null)
                val newId = dao.insertReminder(finalItem).toInt()
                val savedEntity = finalItem.copy(id = newId)

                if (schedule.shouldSendImmediately && (reminderType == ReminderType.PACK_COMPLET || sendDate.isBlank())) {
                    smsSender.sendReminders(listOf(savedEntity)) { sentId ->
                        launch {
                            dao.markSmsAsSent(sentId, System.currentTimeMillis())
                            SyncService.scheduleNextPeriodicReminder(getApplication(), savedEntity)
                        }
                    }
                } else {
                    SyncService.scheduleExactAlarm(getApplication(), newId, itemSendDate, itemSendTime)
                }
            }
        }
    }

    // Réaligner tous les rappels non envoyés sur les dates des réglages
    fun realignAllClientsWithFixedSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = dao.getUnsentReminders()
            for (item in list) {
                val schedule = computeInitialSchedule(item.category, item.reminderType)
                val newText = generateSmsText(item.clientName, item.category, item.reminderType, schedule.dueDate, item.language)
                val updated = item.copy(
                    dueDate = schedule.dueDate,
                    sendDate = schedule.sendDate,
                    sendTime = schedule.sendTime,
                    texteSms = newText
                )
                dao.updateReminder(updated)
                SyncService.scheduleExactAlarm(getApplication(), item.id, schedule.sendDate, schedule.sendTime)
            }
        }
    }

    // Modifier un rappel individuel
    fun updateReminder(
        id: Int,
        clientName: String,
        category: String,
        telephone: String,
        email: String,
        taxId: String,
        reminderType: String,
        dueDate: String,
        sendDate: String,
        sendTime: String,
        texteSms: String,
        language: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = dao.getReminderById(id)
            val firebaseKey = existing?.firebaseKey

            val updated = ClientReminderEntity(
                id = id,
                clientName = clientName,
                category = category,
                telephone = telephone,
                email = email.takeIf { it.isNotBlank() },
                taxId = taxId.takeIf { it.isNotBlank() },
                reminderType = reminderType,
                dueDate = dueDate,
                sendDate = sendDate,
                sendTime = sendTime,
                texteSms = texteSms,
                smsEnvoye = false,
                language = language,
                firebaseKey = firebaseKey
            )

            var synced = false
            if (firebaseKey != null) {
                try {
                    val api = RetrofitClient.getApi(getApplication())
                    if (api != null) {
                        val res = api.updateReminder(firebaseKey, updated)
                        if (res.isSuccessful) synced = true
                    }
                } catch (e: Exception) {}
            }

            dao.updateReminder(updated.copy(synced = synced))
            SyncService.scheduleExactAlarm(getApplication(), id, sendDate, sendTime)
        }
    }

    fun updateIndividualSendDate(id: Int, newDate: String, newTime: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = dao.getReminderById(id) ?: return@launch
            val updated = existing.copy(
                sendDate = newDate,
                sendTime = newTime,
                smsEnvoye = false
            )
            dao.updateReminder(updated)
            SyncService.scheduleExactAlarm(getApplication(), id, newDate, newTime)
        }
    }

    fun batchUpdateSendDate(
        targetCategory: String?,
        newSendDate: String,
        newSendTime: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val allList = dao.getUnsentReminders()
            val targets = if (targetCategory == null || targetCategory == "ALL") {
                allList
            } else {
                allList.filter { it.category == targetCategory }
            }

            for (item in targets) {
                val updated = item.copy(
                    sendDate = newSendDate,
                    sendTime = newSendTime,
                    smsEnvoye = false
                )
                dao.updateReminder(updated)
                SyncService.scheduleExactAlarm(getApplication(), item.id, newSendDate, newSendTime)
            }
        }
    }

    fun batchShiftSendDays(
        targetCategory: String?,
        daysShift: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val allList = dao.getUnsentReminders()
            val targets = if (targetCategory == null || targetCategory == "ALL") {
                allList
            } else {
                allList.filter { it.category == targetCategory }
            }

            for (item in targets) {
                try {
                    val parsed = format.parse(item.sendDate) ?: continue
                    val cal = Calendar.getInstance()
                    cal.time = parsed
                    cal.add(Calendar.DAY_OF_YEAR, daysShift)
                    val newDate = format.format(cal.time)

                    val updated = item.copy(sendDate = newDate, smsEnvoye = false)
                    dao.updateReminder(updated)
                    SyncService.scheduleExactAlarm(getApplication(), item.id, newDate, item.sendTime)
                } catch (e: Exception) {}
            }
        }
    }

    fun deleteReminder(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            SyncService.cancelAlarm(getApplication(), id)
            dao.deleteReminder(id)
        }
    }

    // ==========================================
    // 3. SAUVEGARDE ET RESTAURATION DE LA BASE
    // ==========================================
    fun exportDatabaseToJson(): String {
        val root = org.json.JSONObject()
        val clientsArray = org.json.JSONArray()
        for (c in allClients.value) {
            val obj = org.json.JSONObject().apply {
                put("id", c.id)
                put("clientName", c.clientName)
                put("category", c.category)
                put("telephone", c.telephone)
                put("email", c.email ?: "")
                put("taxId", c.taxId ?: "")
                put("address", c.address ?: "")
                put("notes", c.notes ?: "")
                put("createdAt", c.createdAt)
            }
            clientsArray.put(obj)
        }
        val remindersArray = org.json.JSONArray()
        for (r in allReminders.value) {
            val obj = org.json.JSONObject().apply {
                put("id", r.id)
                put("clientName", r.clientName)
                put("category", r.category)
                put("telephone", r.telephone)
                put("email", r.email ?: "")
                put("taxId", r.taxId ?: "")
                put("reminderType", r.reminderType)
                put("dueDate", r.dueDate)
                put("sendDate", r.sendDate)
                put("sendTime", r.sendTime)
                put("texteSms", r.texteSms)
                put("smsEnvoye", r.smsEnvoye)
                put("sentTimestamp", r.sentTimestamp ?: 0L)
                put("language", r.language)
            }
            remindersArray.put(obj)
        }
        root.put("version", 2)
        root.put("exportDate", System.currentTimeMillis())
        root.put("clients", clientsArray)
        root.put("reminders", remindersArray)
        return root.toString(2)
    }

    fun importDatabaseFromJson(jsonString: String, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val root = org.json.JSONObject(jsonString)
                val clientsArray = root.optJSONArray("clients") ?: org.json.JSONArray()
                val remindersArray = root.optJSONArray("reminders") ?: org.json.JSONArray()

                for (i in 0 until clientsArray.length()) {
                    val obj = clientsArray.getJSONObject(i)
                    val client = ClientEntity(
                        id = 0,
                        clientName = obj.optString("clientName"),
                        category = obj.optString("category", ClientCategory.ENTREPRISE),
                        telephone = obj.optString("telephone"),
                        email = obj.optString("email").takeIf { it.isNotBlank() },
                        taxId = obj.optString("taxId").takeIf { it.isNotBlank() },
                        address = obj.optString("address").takeIf { it.isNotBlank() },
                        notes = obj.optString("notes").takeIf { it.isNotBlank() },
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                    val existing = dao.getClientByPhone(client.telephone)
                    if (existing == null) {
                        dao.insertClient(client)
                    } else {
                        dao.updateClient(client.copy(id = existing.id))
                    }
                }

                for (j in 0 until remindersArray.length()) {
                    val obj = remindersArray.getJSONObject(j)
                    val reminder = ClientReminderEntity(
                        id = 0,
                        clientName = obj.optString("clientName"),
                        category = obj.optString("category", ClientCategory.ENTREPRISE),
                        telephone = obj.optString("telephone"),
                        email = obj.optString("email").takeIf { it.isNotBlank() },
                        taxId = obj.optString("taxId").takeIf { it.isNotBlank() },
                        reminderType = obj.optString("reminderType", ReminderType.DECLARATION_MENSUELLE),
                        dueDate = obj.optString("dueDate"),
                        sendDate = obj.optString("sendDate"),
                        sendTime = obj.optString("sendTime", "09:00"),
                        texteSms = obj.optString("texteSms"),
                        smsEnvoye = obj.optBoolean("smsEnvoye", false),
                        sentTimestamp = if (obj.has("sentTimestamp")) obj.optLong("sentTimestamp") else null,
                        language = obj.optString("language", "fr")
                    )
                    dao.insertReminder(reminder)
                }

                withContext(Dispatchers.Main) {
                    onComplete(true, "${clientsArray.length()} client(s) et ${remindersArray.length()} rappel(s) restaurés avec succès !")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onComplete(false, "Erreur lors de la restauration : ${e.message}")
                }
            }
        }
    }

    fun resetSmsStatus(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.resetSmsStatus(id)
            val item = dao.getReminderById(id)
            if (item != null) {
                SyncService.scheduleExactAlarm(getApplication(), id, item.sendDate, item.sendTime)
            }
        }
    }

    fun sendRemindersNow(reminders: List<ClientReminderEntity>, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            smsSender.sendReminders(reminders) { id ->
                launch {
                    dao.markSmsAsSent(id, System.currentTimeMillis())
                    val sentItem = reminders.find { it.id == id }
                    if (sentItem != null) {
                        SyncService.scheduleNextPeriodicReminder(getApplication(), sentItem)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }
}
