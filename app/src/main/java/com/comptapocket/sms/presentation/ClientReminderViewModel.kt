package com.comptapocket.sms.presentation

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comptapocket.sms.backup.BackupManager
import com.comptapocket.sms.data.local.AppDatabase
import com.comptapocket.sms.data.local.ClientCategory
import com.comptapocket.sms.data.local.ClientEntity
import com.comptapocket.sms.data.local.ClientReminderEntity
import com.comptapocket.sms.data.local.ReminderType
import com.comptapocket.sms.firebase.FirebaseSyncManager
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

data class ClientWithActions(
    val clientKey: String,
    val clientName: String,
    val category: String,
    val telephone: String,
    val email: String? = null,
    val taxId: String? = null,
    val reminders: List<ClientReminderEntity> = emptyList()
)

class ClientReminderViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getDatabase(application).clientReminderDao()
    private val smsSender = SmsSender(application)
    private val prefs = application.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
    private val syncMutex = Mutex()

    init {
        SimulationDateManager.init(application)
        viewModelScope.launch {
            SimulationDateManager.simulatedDateFlow.collect {
                syncCycleForActiveDate()
                checkAndAutoSendDueReminders()
            }
        }
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
    // - Pour la DM : Le statut envoyé reste affiché jusqu'à l'apparition du statut en attente du mois prochain.
    // - Pour AP & CNSS : Le statut envoyé reste affiché jusqu'à la date d'échéance légale de paiement.
    val activeReminders: StateFlow<List<ClientReminderEntity>> = combine(
        rawReminders,
        SimulationDateManager.simulatedDateFlow
    ) { list, _ ->
        val todayStr = SimulationDateManager.getTodayDateFormatted() // "yyyy-MM-dd"
        val now = SimulationDateManager.getTodayCalendar()
        val currentYear = now.get(Calendar.YEAR)
        val currentMonth = now.get(Calendar.MONTH) + 1 // 1-12
        val currentYearMonth = String.format("%04d-%02d", currentYear, currentMonth)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        // 1. Déterminer pour chaque client si la DM du mois en cours a été envoyée
        val currentMonthDmSentByClient = mutableMapOf<String, Boolean>()
        for (item in list) {
            if (item.reminderType == ReminderType.DECLARATION_MENSUELLE) {
                var itemYear = currentYear
                var itemMonth = currentMonth
                try {
                    val parsed = dateFormat.parse(item.dueDate) ?: dateFormat.parse(item.sendDate)
                    if (parsed != null) {
                        val cal = Calendar.getInstance().apply { time = parsed }
                        itemYear = cal.get(Calendar.YEAR)
                        itemMonth = cal.get(Calendar.MONTH) + 1
                    }
                } catch (_: Exception) {}
                val ym = String.format("%04d-%02d", itemYear, itemMonth)
                if (ym == currentYearMonth) {
                    val clientKey = "${item.clientName.trim().lowercase()}|${item.category}"
                    if (item.smsEnvoye) {
                        currentMonthDmSentByClient[clientKey] = true
                    } else if (!currentMonthDmSentByClient.containsKey(clientKey)) {
                        currentMonthDmSentByClient[clientKey] = false
                    }
                }
            }
        }

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

            val itemYearMonth = String.format("%04d-%02d", itemYear, itemMonth)

            when (item.reminderType) {
                ReminderType.DECLARATION_MENSUELLE -> {
                    // Pour la DM :
                    // 1. La DM du mois en cours (currentYearMonth) est TOUJOURS affichée (en attente ou envoyée).
                    // 2. La DM du mois suivant (M+1) N'APPARAÎT QUE SI la DM du mois en cours a DÉJÀ ÉTÉ ENVOYÉE.
                    // 3. Dès que le mois suivant commence, la DM du mois précédent disparaît (car itemYearMonth < currentYearMonth).
                    val isCurrentMonth = (itemYearMonth == currentYearMonth)
                    val isNextMonth = (itemYear == currentYear && itemMonth == currentMonth + 1) ||
                            (currentMonth == 12 && itemYear == currentYear + 1 && itemMonth == 1)

                    val clientKey = "${item.clientName.trim().lowercase()}|${item.category}"

                    val hasCurrentMonthDm = currentMonthDmSentByClient.containsKey(clientKey)
                    val isCurrentDmSent = currentMonthDmSentByClient[clientKey] == true

                    if (isCurrentMonth) {
                        true
                    } else if (isNextMonth) {
                        // Pour les clients ajoutés après l'échéance du mois en cours, la DM du mois suivant
                        // est directement leur échéance active sans attendre un envoi du mois échu !
                        !item.smsEnvoye && (isCurrentDmSent || !hasCurrentMonthDm)
                    } else {
                        false
                    }
                }
                ReminderType.CNSS -> {
                    // Pour la cotisation CNSS :
                    // N'apparaît dès que le mois de l'échéance entre en vigueur (trimestres : Janvier=1, Avril=4, Juillet=7, Octobre=10)
                    // Et reste affiché avec la mention "Envoyé" jusqu'à la date d'échéance du paiement (dueDate).
                    if (item.smsEnvoye) {
                        todayStr <= item.dueDate && (currentMonth in listOf(1, 4, 7, 10) || itemYearMonth == currentYearMonth)
                    } else {
                        currentMonth in listOf(1, 4, 7, 10) && itemYearMonth == currentYearMonth && todayStr <= item.dueDate
                    }
                }
                ReminderType.ACOMPTES_PROVISIONNELS -> {
                    // Pour l'Acompte Provisionnel (AP) :
                    // N'apparaît dès que le mois de l'échéance entre en vigueur (mois 6, 9, 12)
                    // Et reste affiché avec la mention "Envoyé" jusqu'à la date d'échéance du paiement (dueDate).
                    if (item.smsEnvoye) {
                        todayStr <= item.dueDate && (currentMonth in listOf(6, 9, 12) || itemYearMonth == currentYearMonth)
                    } else {
                        currentMonth in listOf(6, 9, 12) && itemYearMonth == currentYearMonth && todayStr <= item.dueDate
                    }
                }
                else -> {
                    val isCurrentCycle = (itemYearMonth == currentYearMonth)
                    val notExpired = todayStr <= item.dueDate
                    isCurrentCycle || notExpired
                }
            }
        }.distinctBy {
            val monthKey = if (it.dueDate.length >= 7) it.dueDate.substring(0, 7) else it.dueDate
            "${it.clientName.trim().lowercase()}|${it.category}|${it.reminderType}|$monthKey"
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

    // Regroupement par Carte Client unique : chaque client répertorié dans la base a UNE SEULE carte contenant toutes ses actions actives (DM, CNSS, AP)
    val filteredClientCards: StateFlow<List<ClientWithActions>> = combine(
        dao.getAllClients(),
        filteredReminders,
        _searchQuery,
        _categoryFilter
    ) { clientsList, remindersList, query, catFilter ->
        val remindersByClient = remindersList.groupBy { item ->
            val cleanName = item.clientName.trim().lowercase()
            val cleanPhone = item.telephone.trim()
            val idKey = if (cleanName.isNotBlank()) cleanName else cleanPhone
            "$idKey|${item.category}"
        }

        val allClientsCombined = clientsList.toMutableList()
        val existingKeys = clientsList.map {
            val id = if (it.clientName.isNotBlank()) it.clientName.trim().lowercase() else it.telephone.trim()
            "$id|${it.category}"
        }.toSet()

        for ((key, rems) in remindersByClient) {
            if (key !in existingKeys && rems.isNotEmpty()) {
                val first = rems.first()
                allClientsCombined.add(
                    ClientEntity(
                        id = 0,
                        clientName = first.clientName,
                        category = first.category,
                        telephone = first.telephone,
                        email = first.email,
                        taxId = first.taxId
                    )
                )
            }
        }

        allClientsCombined.filter { client ->
            val matchesQuery = query.isBlank() ||
                    client.clientName.contains(query, ignoreCase = true) ||
                    client.telephone.contains(query, ignoreCase = true) ||
                    (client.taxId?.contains(query, ignoreCase = true) == true) ||
                    (client.email?.contains(query, ignoreCase = true) == true)

            val matchesCategory = catFilter == "ALL" || client.category == catFilter

            matchesQuery && matchesCategory
        }.map { client ->
            val idKey = if (client.clientName.isNotBlank()) client.clientName.trim().lowercase() else client.telephone.trim()
            val clientKey = "$idKey|${client.category}"
            val clientReminders = (remindersByClient[clientKey] ?: emptyList())
                .sortedWith(compareBy({ it.dueDate }, { it.reminderType }))

            ClientWithActions(
                clientKey = clientKey,
                clientName = client.clientName,
                category = client.category,
                telephone = client.telephone,
                email = client.email,
                taxId = client.taxId,
                reminders = clientReminders
            )
        }.sortedBy { it.clientName }
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
                val found = dao.getClientByName(r.clientName.trim())
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
            syncCycleForActiveDate()
            triggerAutoBackup()
        }
    }

    // Modifier les données d'un client dans la base de données (avec mise à jour en cascade de ses rappels)
    fun updateClientInDatabase(
        id: Int,
        oldName: String,
        oldPhone: String,
        oldCategory: String,
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

            // Mettre à jour en cascade tous les rappels existants pour ce client dans sa catégorie
            dao.updateRemindersForClient(
                oldName = oldName,
                oldPhone = oldPhone,
                oldCategory = oldCategory,
                newName = newName.trim(),
                newCategory = newCategory,
                newPhone = newPhone.trim(),
                newEmail = newEmail?.trim()?.takeIf { it.isNotBlank() },
                newTaxId = newTaxId?.trim()?.takeIf { it.isNotBlank() }
            )

            // Recalculer le texte des SMS pour les rappels non envoyés afin d'intégrer le nouveau nom/catégorie
            val reminders = dao.getRemindersForClient(newPhone.trim(), newName.trim(), newCategory)
            for (rem in reminders) {
                if (!rem.smsEnvoye) {
                    val updatedText = generateSmsText(newName.trim(), newCategory, rem.reminderType, rem.dueDate, rem.language)
                    dao.updateReminder(rem.copy(texteSms = updatedText))
                }
            }
            triggerAutoBackup()
        }
    }

    // Supprimer un client de la base de données
    fun deleteClientFromDatabase(client: ClientEntity, deleteReminders: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteClient(client.id)
            if (deleteReminders) {
                dao.deleteRemindersForClient(client.clientName, client.telephone, client.category)
            }
            triggerAutoBackup()
        }
    }

    suspend fun getRemindersForClient(client: ClientEntity): List<ClientReminderEntity> {
        return withContext(Dispatchers.IO) {
            dao.getRemindersForClient(client.telephone, client.clientName, client.category)
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
                    val plannedSendCal = (cal.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_YEAR, -daysBefore)
                    }
                    val plannedSendDateStr = format.format(plannedSendCal.time)

                    if (todayDateStr >= plannedSendDateStr) {
                        ScheduleResult(
                            dueDate = due,
                            sendDate = todayDateStr,
                            sendTime = currentTimeStr,
                            shouldSendImmediately = true
                        )
                    } else {
                        ScheduleResult(
                            dueDate = due,
                            sendDate = plannedSendDateStr,
                            sendTime = defaultTime,
                            shouldSendImmediately = false
                        )
                    }
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
            val existingClient = dao.getClientByName(clientName.trim())
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

                val formatDay = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val todayDateStr = formatDay.format(SimulationDateManager.getTodayCalendar().time)
                val isExpired = todayDateStr > itemDueDate

                // Règle : Si le client est ajouté après la date d'échéance de 1 jour ou plus,
                // ce n'est pas la peine d'envoyer un SMS !
                if (!isExpired && schedule.shouldSendImmediately && (reminderType == ReminderType.PACK_COMPLET || sendDate.isBlank())) {
                    smsSender.sendReminders(listOf(savedEntity)) { sentId ->
                        launch {
                            val markTime = if (SimulationDateManager.isSimulationActive()) {
                                SimulationDateManager.getTodayCalendar().timeInMillis
                            } else {
                                System.currentTimeMillis()
                            }
                            dao.markSmsAsSent(sentId, markTime)
                            SyncService.scheduleNextPeriodicReminder(getApplication(), savedEntity)
                        }
                    }
                } else if (!isExpired) {
                    SyncService.scheduleExactAlarm(getApplication(), newId, itemSendDate, itemSendTime)
                }
            }
            syncCycleForActiveDate()
            triggerAutoBackup()
        }
    }

    // Synchroniser dynamiquement le cycle pour la date active (réelle ou simulée)
    // Garantit que pour le mois actif, tous les clients ont leurs rappels DM préparés,
    // ainsi que CNSS (si mois 1, 4, 7, 10) et AP (si mois 6, 9, 12).
    // Mutex + Nettoyage automatique des doublons pour garantir l'absence de doublons.
    fun syncCycleForActiveDate() {
        viewModelScope.launch(Dispatchers.IO) {
            syncMutex.withLock {
                val now = SimulationDateManager.getTodayCalendar()
                val currentYear = now.get(Calendar.YEAR)
                val currentMonth = now.get(Calendar.MONTH) + 1 // 1..12
                val yearMonthPattern = String.format("%04d-%02d-%%", currentYear, currentMonth)

                // 0. Nettoyage préventif des doublons éventuels dans la table ClientReminders
                val allCurrentReminders = dao.getAllRemindersList()
                val seenReminders = mutableSetOf<String>()
                for (r in allCurrentReminders) {
                    val monthKey = if (r.dueDate.length >= 7) r.dueDate.substring(0, 7) else r.dueDate
                    val idKey = if (r.clientName.isNotBlank()) r.clientName.trim().lowercase() else r.telephone.trim()
                    val uniqueKey = "$idKey|${r.category}|${r.reminderType}|$monthKey"
                    if (uniqueKey in seenReminders) {
                        dao.deleteReminder(r.id)
                    } else {
                        seenReminders.add(uniqueKey)
                    }
                }

                // 1. S'assurer que les clients enregistrés sont bien dans la table Clients
                var rawClients = dao.getAllClientsList()
                if (rawClients.isEmpty()) {
                    val allReminders = dao.getAllRemindersList()
                    val distinctClients = allReminders.distinctBy {
                        val id = if (it.clientName.isNotBlank()) it.clientName.trim().lowercase() else it.telephone.trim()
                        "$id|${it.category}"
                    }
                    for (r in distinctClients) {
                        if (r.clientName.isNotBlank() || r.telephone.isNotBlank()) {
                            dao.insertClient(
                                ClientEntity(
                                    clientName = r.clientName.trim(),
                                    category = r.category,
                                    telephone = r.telephone.trim(),
                                    email = r.email,
                                    taxId = r.taxId
                                )
                            )
                        }
                    }
                    rawClients = dao.getAllClientsList()
                }

                // 1.bis Réconciliation et correction des incohérences de catégorie / date d'échéance
                for (r in dao.getAllRemindersList()) {
                    if (r.reminderType == ReminderType.DECLARATION_MENSUELLE) {
                        val isDue28 = r.dueDate.endsWith("-28") || r.dueDate.endsWith("/28")
                        val isDue15 = r.dueDate.endsWith("-15") || r.dueDate.endsWith("/15")

                        if (isDue28 && r.category == ClientCategory.PERSONNE_PHYSIQUE) {
                            val matchingEntreprise = rawClients.firstOrNull { it.category == ClientCategory.ENTREPRISE && it.telephone == r.telephone }
                            if (matchingEntreprise != null) {
                                dao.updateReminder(r.copy(
                                    category = ClientCategory.ENTREPRISE,
                                    clientName = if (r.clientName.isBlank()) matchingEntreprise.clientName else r.clientName
                                ))
                            } else {
                                val correctedDue = r.dueDate.substring(0, 8) + "15"
                                dao.updateReminder(r.copy(dueDate = correctedDue))
                            }
                        } else if (isDue15 && r.category == ClientCategory.ENTREPRISE) {
                            val matchingPhysique = rawClients.firstOrNull { it.category == ClientCategory.PERSONNE_PHYSIQUE && it.telephone == r.telephone }
                            if (matchingPhysique != null) {
                                dao.updateReminder(r.copy(
                                    category = ClientCategory.PERSONNE_PHYSIQUE,
                                    clientName = if (r.clientName.isBlank()) matchingPhysique.clientName else r.clientName
                                ))
                            } else {
                                val correctedDue = r.dueDate.substring(0, 8) + "28"
                                dao.updateReminder(r.copy(dueDate = correctedDue))
                            }
                        }
                    }
                }

                // Déduplication stricte des clients pour ne jamais générer deux fois pour le même client
                val clientsList = rawClients.distinctBy {
                    val id = if (it.clientName.isNotBlank()) it.clientName.trim().lowercase() else it.telephone.trim()
                    "$id|${it.category}"
                }

                if (clientsList.isEmpty()) return@withLock

                val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val defaultTime = getDefaultSendTime()
                val daysBefore = prefs.getInt("default_days_before", 3)

                val dueDayEntreprise = prefs.getInt("due_day_entreprise", 28)
                val dueDayPhysique = prefs.getInt("due_day_physique", 15)
                val cnssDueDay = prefs.getInt("cnss_due_day", 15)
                val acomptesDayEntreprise = prefs.getInt("acomptes_day_entreprise", 28)
                val acomptesDayPhysique = prefs.getInt("acomptes_day_physique", 25)

                for (client in clientsList) {
                    val isEntreprise = client.category == ClientCategory.ENTREPRISE
                    val clientCleanName = client.clientName.trim()
                    val clientCleanPhone = client.telephone.trim()

                    // --- 1. DÉCLARATION MENSUELLE (DM) : Tous les mois ---
                    val dmDueDay = if (isEntreprise) dueDayEntreprise else dueDayPhysique
                    val dmCal = (now.clone() as Calendar).apply {
                        val maxDay = getActualMaximum(Calendar.DAY_OF_MONTH)
                        set(Calendar.DAY_OF_MONTH, minOf(dmDueDay, maxDay))
                    }
                    val dmDueDateStr = format.format(dmCal.time)
                    val dmSendCal = (dmCal.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_YEAR, -daysBefore)
                    }
                    val dmSendDateStr = format.format(dmSendCal.time)

                    val existingDm = dao.getReminderByClientTypeAndMonth(
                        clientCleanName, clientCleanPhone, client.category, ReminderType.DECLARATION_MENSUELLE, yearMonthPattern
                    )
                    val todayDateStr = format.format(now.time)
                    if (existingDm == null) {
                        if (todayDateStr > dmDueDateStr) {
                            // Règle formelle : Si le client est ajouté après la date d'échéance de 1 jour ou plus,
                            // ce n'est pas la peine d'envoyer un SMS pour cette échéance passée.
                            // On programme directement l'échéance du mois suivant !
                            val nextDmCal = (now.clone() as Calendar).apply {
                                add(Calendar.MONTH, 1)
                                val maxDay = getActualMaximum(Calendar.DAY_OF_MONTH)
                                set(Calendar.DAY_OF_MONTH, minOf(dmDueDay, maxDay))
                            }
                            val nextDmDueDateStr = format.format(nextDmCal.time)
                            val nextDmSendCal = (nextDmCal.clone() as Calendar).apply {
                                add(Calendar.DAY_OF_YEAR, -daysBefore)
                            }
                            val nextDmSendDateStr = format.format(nextDmSendCal.time)
                            val nextPattern = String.format("%04d-%02d-%%", nextDmCal.get(Calendar.YEAR), nextDmCal.get(Calendar.MONTH) + 1)
                            val existingNextDm = dao.getReminderByClientTypeAndMonth(
                                clientCleanName, clientCleanPhone, client.category, ReminderType.DECLARATION_MENSUELLE, nextPattern
                            )
                            if (existingNextDm == null) {
                                val text = generateSmsText(clientCleanName, client.category, ReminderType.DECLARATION_MENSUELLE, nextDmDueDateStr)
                                dao.insertReminder(
                                    ClientReminderEntity(
                                        clientName = clientCleanName,
                                        category = client.category,
                                        telephone = clientCleanPhone,
                                        email = client.email,
                                        taxId = client.taxId,
                                        reminderType = ReminderType.DECLARATION_MENSUELLE,
                                        dueDate = nextDmDueDateStr,
                                        sendDate = nextDmSendDateStr,
                                        sendTime = defaultTime,
                                        texteSms = text,
                                        smsEnvoye = false
                                    )
                                )
                            }
                        } else {
                            val text = generateSmsText(clientCleanName, client.category, ReminderType.DECLARATION_MENSUELLE, dmDueDateStr)
                            dao.insertReminder(
                                ClientReminderEntity(
                                    clientName = clientCleanName,
                                    category = client.category,
                                    telephone = clientCleanPhone,
                                    email = client.email,
                                    taxId = client.taxId,
                                    reminderType = ReminderType.DECLARATION_MENSUELLE,
                                    dueDate = dmDueDateStr,
                                    sendDate = dmSendDateStr,
                                    sendTime = defaultTime,
                                    texteSms = text,
                                    smsEnvoye = false
                                )
                            )
                        }
                    }

                    // --- 2. COTISATIONS CNSS : Trimestrielle (Janvier=1, Avril=4, Juillet=7, Octobre=10) ---
                    if (currentMonth in listOf(1, 4, 7, 10)) {
                        val cnssCal = (now.clone() as Calendar).apply {
                            val maxDay = getActualMaximum(Calendar.DAY_OF_MONTH)
                            set(Calendar.DAY_OF_MONTH, minOf(cnssDueDay, maxDay))
                        }
                        val cnssDueDateStr = format.format(cnssCal.time)
                        val cnssSendCal = (cnssCal.clone() as Calendar).apply {
                            add(Calendar.DAY_OF_YEAR, -daysBefore)
                        }
                        val cnssSendDateStr = format.format(cnssSendCal.time)

                        val existingCnss = dao.getReminderByClientTypeAndMonth(
                            clientCleanName, clientCleanPhone, client.category, ReminderType.CNSS, yearMonthPattern
                        )
                        // Si le client est ajouté après la date d'échéance de la CNSS (ex: 16/01 pour échéance 15/01),
                        // ce n'est pas la peine d'envoyer un SMS pour cette échéance passée.
                        if (existingCnss == null && todayDateStr <= cnssDueDateStr) {
                            val text = generateSmsText(clientCleanName, client.category, ReminderType.CNSS, cnssDueDateStr)
                            dao.insertReminder(
                                ClientReminderEntity(
                                    clientName = clientCleanName,
                                    category = client.category,
                                    telephone = clientCleanPhone,
                                    email = client.email,
                                    taxId = client.taxId,
                                    reminderType = ReminderType.CNSS,
                                    dueDate = cnssDueDateStr,
                                    sendDate = cnssSendDateStr,
                                    sendTime = defaultTime,
                                    texteSms = text,
                                    smsEnvoye = false
                                )
                            )
                        }
                    }

                    // --- 3. ACOMPTES PROVISIONNELS (AP) : Juin=6, Septembre=9, Décembre=12 ---
                    if (currentMonth in listOf(6, 9, 12)) {
                        val apDay = if (isEntreprise) acomptesDayEntreprise else acomptesDayPhysique
                        val apCal = (now.clone() as Calendar).apply {
                            val maxDay = getActualMaximum(Calendar.DAY_OF_MONTH)
                            set(Calendar.DAY_OF_MONTH, minOf(apDay, maxDay))
                        }
                        val apDueDateStr = format.format(apCal.time)
                        val apSendCal = (apCal.clone() as Calendar).apply {
                            add(Calendar.DAY_OF_YEAR, -daysBefore)
                        }
                        val apSendDateStr = format.format(apSendCal.time)

                        val existingAp = dao.getReminderByClientTypeAndMonth(
                            clientCleanName, clientCleanPhone, client.category, ReminderType.ACOMPTES_PROVISIONNELS, yearMonthPattern
                        )
                        // Si le client est ajouté après la date d'échéance de l'AP (ex: 26/12 pour échéance 25/12),
                        // ce n'est pas la peine d'envoyer un SMS pour cette échéance passée.
                        if (existingAp == null && todayDateStr <= apDueDateStr) {
                            val text = generateSmsText(clientCleanName, client.category, ReminderType.ACOMPTES_PROVISIONNELS, apDueDateStr)
                            dao.insertReminder(
                                ClientReminderEntity(
                                    clientName = clientCleanName,
                                    category = client.category,
                                    telephone = clientCleanPhone,
                                    email = client.email,
                                    taxId = client.taxId,
                                    reminderType = ReminderType.ACOMPTES_PROVISIONNELS,
                                    dueDate = apDueDateStr,
                                    sendDate = apSendDateStr,
                                    sendTime = defaultTime,
                                    texteSms = text,
                                    smsEnvoye = false
                                )
                            )
                        }
                    }
                }
            }
            syncAllUnsentToCloud()
            checkAndAutoSendDueReminders()
        }
    }

    /**
     * Synchronise tous les rappels non encore envoyés vers Firestore pour prise en charge Cloud (Vercel FCM)
     */
    fun syncAllUnsentToCloud() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = dao.getUnsentReminders()
                for (item in list) {
                    FirebaseSyncManager.syncReminderToCloud(getApplication(), item)
                }
            } catch (_: Exception) {}
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
            triggerAutoBackup()
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
            triggerAutoBackup()
        }
    }

    fun deleteRemindersForClient(clientName: String, telephone: String, category: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val list = if (category.isNotBlank()) {
                dao.getRemindersForClient(telephone, clientName, category)
            } else {
                dao.getAllRemindersList().filter {
                    (it.telephone == telephone || it.clientName.equals(clientName, ignoreCase = true))
                }
            }
            for (r in list) {
                SyncService.cancelAlarm(getApplication(), r.id)
            }
            if (category.isNotBlank()) {
                dao.deleteRemindersForClient(clientName, telephone, category)
                val client = dao.getClientByNameAndCategory(clientName, category)
                    ?: dao.getAllClientsList().firstOrNull { it.telephone == telephone && it.category == category }
                if (client != null) {
                    dao.deleteClient(client.id)
                }
            } else {
                dao.deleteRemindersForClient(clientName, telephone, ClientCategory.PERSONNE_PHYSIQUE)
                dao.deleteRemindersForClient(clientName, telephone, ClientCategory.ENTREPRISE)
                val client = dao.getClientByPhone(telephone) ?: dao.getClientByName(clientName)
                if (client != null) {
                    dao.deleteClient(client.id)
                }
            }
            triggerAutoBackup()
        }
    }

    // ==========================================
    // 3. SAUVEGARDE ET RESTAURATION DE LA BASE
    // ==========================================
    suspend fun getDatabaseJson(): String = withContext(Dispatchers.IO) {
        val root = org.json.JSONObject()
        val clientsArray = org.json.JSONArray()
        val clients = dao.getAllClientsList()
        for (c in clients) {
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
        val reminders = dao.getAllRemindersList()
        for (r in reminders) {
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
        root.toString(2)
    }

    /**
     * Déclenche une sauvegarde automatique glissante (5 fichiers max, le 6ème écrase le plus ancien)
     * à chaque ajout, modification ou suppression d'un client.
     */
    fun triggerAutoBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = getDatabaseJson()
                val success = BackupManager.performRollingBackup(getApplication(), json)
                if (success) {
                    Log.i("ClientReminderVM", "Sauvegarde automatique glissante (5 max) effectuée avec succès")
                }
            } catch (e: Exception) {
                Log.e("ClientReminderVM", "Erreur lors de la sauvegarde automatique glissante", e)
            }
        }
    }

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
                triggerAutoBackup()
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
                    val markTime = if (SimulationDateManager.isSimulationActive()) {
                        SimulationDateManager.getTodayCalendar().timeInMillis
                    } else {
                        System.currentTimeMillis()
                    }
                    dao.markSmsAsSent(id, markTime)
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

    /**
     * Vérification et déclenchement automatique des envois pour tous les rappels échus
     * selon la date active (simulée ou réelle).
     */
    fun checkAndAutoSendDueReminders() {
        val autoSendEnabled = prefs.getBoolean("auto_send_enabled", true)
        if (!autoSendEnabled) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val unsent = dao.getUnsentReminders()
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val formatDay = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val nowCal = SimulationDateManager.getTodayCalendar()
                val todayDateStr = formatDay.format(nowCal.time)
                val nowMillis = if (SimulationDateManager.isSimulationActive()) {
                    nowCal.timeInMillis
                } else {
                    System.currentTimeMillis()
                }

                val dueToSend = unsent.filter { item ->
                    try {
                        // Règle formelle : Si la date du jour dépasse la date d'échéance légale de 1 jour ou plus (today > dueDate),
                        // ce n'est pas la peine d'envoyer un SMS !
                        if (todayDateStr > item.dueDate) {
                            return@filter false
                        }

                        val parsed = format.parse("${item.sendDate} ${item.sendTime}")
                        parsed != null && parsed.time <= nowMillis
                    } catch (e: Exception) {
                        false
                    }
                }

                if (dueToSend.isNotEmpty()) {
                    Log.d("ClientReminderVM", "Envoi automatique de ${dueToSend.size} rappels échus (date effective : ${format.format(nowCal.time)})")
                    smsSender.sendReminders(dueToSend) { id ->
                        launch {
                            val markTime = if (SimulationDateManager.isSimulationActive()) {
                                SimulationDateManager.getTodayCalendar().timeInMillis
                            } else {
                                System.currentTimeMillis()
                            }
                            dao.markSmsAsSent(id, markTime)
                            val sentItem = dueToSend.find { it.id == id }
                            if (sentItem != null) {
                                SyncService.scheduleNextPeriodicReminder(getApplication(), sentItem)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ClientReminderVM", "Erreur vérification envois automatiques", e)
            }
        }
    }
}
