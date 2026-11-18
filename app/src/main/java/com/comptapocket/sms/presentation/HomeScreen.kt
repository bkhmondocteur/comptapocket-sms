package com.comptapocket.sms.presentation

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comptapocket.sms.R
import com.comptapocket.sms.data.local.ClientCategory
import com.comptapocket.sms.data.local.ClientEntity
import com.comptapocket.sms.data.local.ClientReminderEntity
import com.comptapocket.sms.data.local.ReminderType
import com.comptapocket.sms.update.UpdateInfo
import com.comptapocket.sms.update.UpdateManager
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: ClientReminderViewModel,
    onNavigateToSettings: () -> Unit,
    initialClientForReminder: ClientEntity? = null,
    onClearInitialClient: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
    val cabinetName = prefs.getString("cabinet_name", "Cabinet Comptable") ?: "Cabinet Comptable"

    val reminders by viewModel.filteredReminders.collectAsState()
    val activeReminders by viewModel.activeReminders.collectAsState()
    val allReminders by viewModel.allReminders.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val categoryFilter by viewModel.categoryFilter.collectAsState()
    val typeFilter by viewModel.typeFilter.collectAsState()

    var showClientDialog by remember { mutableStateOf(false) }
    var editingReminder by remember { mutableStateOf<ClientReminderEntity?>(null) }
    var showBatchDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var autoUpdateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    // Vérification automatique des mises à jour sur GitHub au démarrage
    LaunchedEffect(Unit) {
        try {
            val update = UpdateManager.checkUpdate()
            if (update.hasUpdate) {
                autoUpdateInfo = update
            }
        } catch (_: Exception) {
            // Silencieux si hors-ligne
        }
    }

    // Dialog for individual quick date change
    var quickDateChangeTarget by remember { mutableStateOf<ClientReminderEntity?>(null) }

    // Dialog for mass sending confirmation
    var showSendConfirmDialog by remember { mutableStateOf(false) }

    // Déclenchement automatique pour l'ajout d'un rappel depuis la Base de données Clients
    LaunchedEffect(initialClientForReminder) {
        if (initialClientForReminder != null) {
            editingReminder = ClientReminderEntity(
                id = 0,
                clientName = initialClientForReminder.clientName,
                category = initialClientForReminder.category,
                telephone = initialClientForReminder.telephone,
                email = initialClientForReminder.email ?: "",
                taxId = initialClientForReminder.taxId ?: "",
                reminderType = ReminderType.DECLARATION_MENSUELLE,
                dueDate = "",
                sendDate = "",
                sendTime = "09:00",
                texteSms = "",
                smsEnvoye = false
            )
            showClientDialog = true
            onClearInitialClient()
        }
    }

    // Statistics counts calculés sur le cycle actif
    val entreprisesCount = activeReminders.count { it.category == ClientCategory.ENTREPRISE }
    val physiquesCount = activeReminders.count { it.category == ClientCategory.PERSONNE_PHYSIQUE }
    val pendingCount = activeReminders.count { !it.smsEnvoye }
    val sentCount = activeReminders.count { it.smsEnvoye }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "Logo ComptaPocket SMS",
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                        )
                        Column {
                            Text("ComptaPocket SMS", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text(cabinetName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    // Bouton modification collective de date
                    IconButton(onClick = { showBatchDialog = true }) {
                        Icon(Icons.Default.DateRange, contentDescription = "Dates groupées", tint = MaterialTheme.colorScheme.primary)
                    }

                    // Bouton Envoi Groupé des SMS en attente
                    IconButton(onClick = {
                        if (pendingCount > 0) {
                            showSendConfirmDialog = true
                        } else {
                            Toast.makeText(context, "Aucun SMS en attente d'envoi.", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Send, contentDescription = "Envoyer tout", tint = if (pendingCount > 0) MaterialTheme.colorScheme.primary else Color.Gray)
                    }

                    // Bouton À propos & Mises à jour
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "À propos", tint = MaterialTheme.colorScheme.primary)
                    }

                    // Paramètres
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editingReminder = null
                    showClientDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Nouveau Client") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Dashboard des statistiques en résumé (Entreprises, Personnes physiques, En attente, Envoyés)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    StatBadge("🏢 Sociétés", entreprisesCount.toString(), Color(0xFF1E88E5))
                    StatBadge("👤 Pers. phys.", physiquesCount.toString(), Color(0xFF8E24AA))
                    StatBadge("⏳ En attente", pendingCount.toString(), Color(0xFFE65100))
                    StatBadge("✅ Envoyés", sentCount.toString(), Color(0xFF2E7D32))
                }
            }

            // Barre de recherche
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Rechercher un client, téléphone ou matricule...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Effacer")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                singleLine = true
            )

            // Menus déroulants de filtrage dynamique (Catégorie & Type de SMS)
            var categoryDropdownExpanded by remember { mutableStateOf(false) }
            var typeDropdownExpanded by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Menu déroulant 1 : Catégorie
                ExposedDropdownMenuBox(
                    expanded = categoryDropdownExpanded,
                    onExpandedChange = { categoryDropdownExpanded = !categoryDropdownExpanded },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = when (categoryFilter) {
                            ClientCategory.PERSONNE_PHYSIQUE -> "👤 Physiques"
                            ClientCategory.ENTREPRISE -> "🏢 Sociétés"
                            else -> "👥 Toutes"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Catégorie") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryDropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = categoryDropdownExpanded,
                        onDismissRequest = { categoryDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("👥 Toutes les catégories") },
                            onClick = {
                                viewModel.setCategoryFilter("ALL")
                                categoryDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🏢 Sociétés / Entreprises") },
                            onClick = {
                                viewModel.setCategoryFilter(ClientCategory.ENTREPRISE)
                                categoryDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("👤 Personnes physiques") },
                            onClick = {
                                viewModel.setCategoryFilter(ClientCategory.PERSONNE_PHYSIQUE)
                                categoryDropdownExpanded = false
                            }
                        )
                    }
                }

                // Menu déroulant 2 : Type de déclaration / SMS (avec abréviations DM, AP, CNSS)
                ExposedDropdownMenuBox(
                    expanded = typeDropdownExpanded,
                    onExpandedChange = { typeDropdownExpanded = !typeDropdownExpanded },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = when (typeFilter) {
                            ReminderType.DECLARATION_MENSUELLE -> "📄 DM"
                            ReminderType.CNSS -> "🛡️ CNSS"
                            ReminderType.ACOMPTES_PROVISIONNELS -> "💰 AP"
                            ReminderType.AUTRE -> "📌 Autre"
                            else -> "📑 Tous"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type du SMS") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeDropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = typeDropdownExpanded,
                        onDismissRequest = { typeDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("📑 Tous les types") },
                            onClick = {
                                viewModel.setTypeFilter("ALL")
                                typeDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("📄 DM - Déclaration mensuelle") },
                            onClick = {
                                viewModel.setTypeFilter(ReminderType.DECLARATION_MENSUELLE)
                                typeDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🛡️ Cotisations CNSS") },
                            onClick = {
                                viewModel.setTypeFilter(ReminderType.CNSS)
                                typeDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("💰 AP - Acompte provisionnel") },
                            onClick = {
                                viewModel.setTypeFilter(ReminderType.ACOMPTES_PROVISIONNELS)
                                typeDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("📌 Autre rappel / échéance") },
                            onClick = {
                                viewModel.setTypeFilter(ReminderType.AUTRE)
                                typeDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // Bouton action rapide pour modifier la date d'envoi collective
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${reminders.size} résultat(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = { showBatchDialog = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Modifier date d'envoi collective", fontSize = 12.sp)
                }
            }

            // Liste des rappels
            if (reminders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.List,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Aucun client ou rappel trouvé",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Cliquez sur le bouton '+' pour enregistrer un client (Personne physique ou Entreprise) et programmer le rappel de ses déclarations.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(reminders, key = { it.id }) { reminder ->
                        ReminderCard(
                            reminder = reminder,
                            onEdit = {
                                editingReminder = reminder
                                showClientDialog = true
                            },
                            onDelete = {
                                viewModel.deleteReminder(reminder.id)
                                Toast.makeText(context, "Client supprimé", Toast.LENGTH_SHORT).show()
                            },
                            onSendNow = {
                                viewModel.sendRemindersNow(listOf(reminder)) {
                                    Toast.makeText(context, "Envoi terminé pour ${reminder.clientName}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onResetStatus = {
                                viewModel.resetSmsStatus(reminder.id)
                                Toast.makeText(context, "Statut réinitialisé en attente", Toast.LENGTH_SHORT).show()
                            },
                            onQuickDateChange = {
                                quickDateChangeTarget = reminder
                            }
                        )
                    }
                }
            }
        }
    }

    // Dialogue d'ajout ou d'édition d'un client
    if (showClientDialog) {
        ClientDialog(
            initialReminder = editingReminder,
            viewModel = viewModel,
            onDismiss = {
                showClientDialog = false
                editingReminder = null
            },
            onSave = { name, cat, phone, email, taxId, type, due, sendD, sendT, sms, lang ->
                if (editingReminder != null) {
                    viewModel.updateReminder(
                        id = editingReminder!!.id,
                        clientName = name,
                        category = cat,
                        telephone = phone,
                        email = email,
                        taxId = taxId,
                        reminderType = type,
                        dueDate = due,
                        sendDate = sendD,
                        sendTime = sendT,
                        texteSms = sms,
                        language = lang
                    )
                    Toast.makeText(context, "Client mis à jour", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.addReminder(
                        clientName = name,
                        category = cat,
                        telephone = phone,
                        email = email,
                        taxId = taxId,
                        reminderType = type,
                        dueDate = due,
                        sendDate = sendD,
                        sendTime = sendT,
                        texteSms = sms,
                        language = lang
                    )
                    Toast.makeText(context, "Client enregistré et rappel programmé", Toast.LENGTH_SHORT).show()
                }
                showClientDialog = false
                editingReminder = null
            }
        )
    }

    // Dialogue de modification collective des dates d'envoi
    if (showBatchDialog) {
        BatchDateDialog(
            allReminders = allReminders,
            onDismiss = { showBatchDialog = false },
            onApplyExactDate = { targetCat, newDate, newTime ->
                viewModel.batchUpdateSendDate(targetCat, newDate, newTime)
                val label = if (targetCat == null) "tous les clients" else ClientCategory.getLabel(targetCat)
                Toast.makeText(context, "Date d'envoi modifiée pour $label ($newDate à $newTime)", Toast.LENGTH_LONG).show()
            },
            onApplyShiftDays = { targetCat, days ->
                viewModel.batchShiftSendDays(targetCat, days)
                Toast.makeText(context, "Dates d'envoi décalées de $days jour(s)", Toast.LENGTH_LONG).show()
            }
        )
    }

    // Dialogue rapide individuel pour changer la date d'envoi d'un client précis
    quickDateChangeTarget?.let { target ->
        val cal = Calendar.getInstance()
        var tempDate by remember { mutableStateOf(target.sendDate) }
        var tempTime by remember { mutableStateOf(target.sendTime) }

        AlertDialog(
            onDismissRequest = { quickDateChangeTarget = null },
            title = { Text("Modifier la date d'envoi pour : ${target.clientName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Date limite légale : ${target.dueDate}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)

                    OutlinedTextField(
                        value = tempDate,
                        onValueChange = { tempDate = it },
                        label = { Text("Date d'envoi du SMS") },
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                DatePickerDialog(
                                    context,
                                    { _, y, m, d ->
                                        tempDate = String.format("%04d-%02d-%02d", y, m + 1, d)
                                    },
                                    cal.get(Calendar.YEAR),
                                    cal.get(Calendar.MONTH),
                                    cal.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            }) {
                                Icon(Icons.Default.DateRange, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = tempTime,
                        onValueChange = { tempTime = it },
                        label = { Text("Heure d'envoi") },
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                TimePickerDialog(
                                    context,
                                    { _, h, min ->
                                        tempTime = String.format("%02d:%02d", h, min)
                                    },
                                    9, 0, true
                                ).show()
                            }) {
                                Icon(Icons.Default.Notifications, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateIndividualSendDate(target.id, tempDate, tempTime)
                    Toast.makeText(context, "Date d'envoi mise à jour", Toast.LENGTH_SHORT).show()
                    quickDateChangeTarget = null
                }) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { quickDateChangeTarget = null }) {
                    Text("Annuler")
                }
            }
        )
    }

    // Confirmation d'envoi groupé
    if (showSendConfirmDialog) {
        val pendingList = allReminders.filter { !it.smsEnvoye }
        AlertDialog(
            onDismissRequest = { showSendConfirmDialog = false },
            title = { Text("Confirmation d'envoi massif de SMS") },
            text = {
                Text("Voulez-vous envoyer immédiatement les SMS à tous les ${pendingList.size} clients en attente ?\n\nLes SMS seront transmis via la carte SIM du téléphone.")
            },
            confirmButton = {
                Button(onClick = {
                    showSendConfirmDialog = false
                    Toast.makeText(context, "Démarrage de l'envoi de ${pendingList.size} SMS...", Toast.LENGTH_SHORT).show()
                    viewModel.sendRemindersNow(pendingList) {
                        Toast.makeText(context, "Tous les SMS en attente ont été envoyés avec succès !", Toast.LENGTH_LONG).show()
                    }
                }) {
                    Text("Envoyer maintenant (${pendingList.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSendConfirmDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    // Dialogue À propos & Mises à jour
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    // Alerte automatique si une nouvelle version est détectée sur GitHub
    if (autoUpdateInfo != null) {
        val update = autoUpdateInfo!!
        AlertDialog(
            onDismissRequest = { autoUpdateInfo = null },
            icon = {
                Icon(
                    Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text("Mise à jour disponible 🚀", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column {
                    Text(
                        "Une nouvelle version v${update.latestVersion} de ComptaPocket SMS est disponible au téléchargement.",
                        fontSize = 14.sp
                    )
                    if (update.changelog.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Nouveautés :", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(update.changelog, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        UpdateManager.startDownload(context, update.downloadUrl)
                        autoUpdateInfo = null
                    }
                ) {
                    Text("Télécharger (APK)")
                }
            },
            dismissButton = {
                TextButton(onClick = { autoUpdateInfo = null }) {
                    Text("Plus tard")
                }
            }
        )
    }
}

@Composable
fun StatBadge(label: String, count: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = color)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ReminderCard(
    reminder: ClientReminderEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSendNow: () -> Unit,
    onResetStatus: () -> Unit,
    onQuickDateChange: () -> Unit
) {
    var expandedSms by remember { mutableStateOf(false) }

    val isEntreprise = reminder.category == ClientCategory.ENTREPRISE
    val categoryColor = if (isEntreprise) Color(0xFF1976D2) else Color(0xFF7B1FA2)
    val categoryBg = if (isEntreprise) Color(0xFFE3F2FD) else Color(0xFFF3E5F5)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Ligne d'en-tête : [Symbole catégorie seul] + [Nom Client & Badge Abrégé DM/CNSS/AP] + [Badge Statut Envoyé / En attente]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Symbole de la catégorie UNIQUEMENT sans texte (🏢 pour Société, 👤 pour Personne physique)
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(categoryBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isEntreprise) "🏢" else "👤",
                            fontSize = 20.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = reminder.clientName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        // Symbole et abréviation UNIQUEMENT : 📄 DM, 🛡️ CNSS, 💰 AP
                        val (typeBg, typeColor, typeLabel) = when (reminder.reminderType) {
                            ReminderType.DECLARATION_MENSUELLE -> Triple(Color(0xFFE3F2FD), Color(0xFF0D47A1), "📄 DM")
                            ReminderType.CNSS -> Triple(Color(0xFFE8F5E9), Color(0xFF1B5E20), "🛡️ CNSS")
                            ReminderType.ACOMPTES_PROVISIONNELS -> Triple(Color(0xFFFFF8E1), Color(0xFFB78103), "💰 AP")
                            else -> Triple(Color(0xFFF3E5F5), Color(0xFF4A148C), "📌 Autre")
                        }
                        Surface(
                            color = typeBg,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = typeLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = typeColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Statut Envoi Badge compact SANS le mot "Mention", juste "En attente" ou "Envoyé"
                Surface(
                    color = if (reminder.smsEnvoye) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.clickable { onResetStatus() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (reminder.smsEnvoye) Icons.Default.CheckCircle else Icons.Default.Notifications,
                            contentDescription = null,
                            tint = if (reminder.smsEnvoye) Color(0xFF2E7D32) else Color(0xFFE65100),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (reminder.smsEnvoye) "Envoyé" else "En attente",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (reminder.smsEnvoye) Color(0xFF2E7D32) else Color(0xFFE65100)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Informations coordonnées
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(reminder.telephone, fontSize = 13.sp)
                }

                if (!reminder.taxId.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("MF: ${reminder.taxId}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bloc des dates : Date limite légale VS Date programmée d'envoi du SMS
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("📅 Date limite légale :", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(reminder.dueDate, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⏰ Envoi SMS programmé :", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = onQuickDateChange, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "Changer date d'envoi", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text("${reminder.sendDate} à ${reminder.sendTime}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Aperçu du message SMS pliable
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedSms = !expandedSms },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (expandedSms) "Masquer le SMS" else "Voir le message SMS...",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    if (expandedSms) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (expandedSms) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = reminder.texteSms,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            // Barre d'actions en bas de chaque carte
            Spacer(modifier = Modifier.height(10.dp))
            Divider()
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bouton individuel pour changer la date d'envoi
                TextButton(onClick = onQuickDateChange, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Date d'envoi", fontSize = 12.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Envoyer maintenant
                    IconButton(onClick = onSendNow) {
                        Icon(Icons.Default.Send, contentDescription = "Envoyer immédiatement", tint = MaterialTheme.colorScheme.primary)
                    }

                    // Modifier
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Modifier", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // Supprimer
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
