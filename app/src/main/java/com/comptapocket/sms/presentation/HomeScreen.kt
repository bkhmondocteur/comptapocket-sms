package com.comptapocket.sms.presentation

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.comptapocket.sms.backup.BackupManager
import com.comptapocket.sms.data.local.ClientCategory
import com.comptapocket.sms.data.local.ClientEntity
import com.comptapocket.sms.data.local.ClientReminderEntity
import com.comptapocket.sms.data.local.ReminderType
import com.comptapocket.sms.simulation.SimulationDateManager
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

    val clientCards by viewModel.filteredClientCards.collectAsState()
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

    // Invite au choix du dossier de sauvegarde dès l'ouverture de la mise à jour
    var showBackupFolderDialog by remember {
        mutableStateOf(
            !BackupManager.isBackupFolderConfigured(context) && !BackupManager.hasPromptBeenShown(context)
        )
    }

    val backupFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val folderName = BackupManager.saveBackupFolderUri(context, uri)
            Toast.makeText(context, "Dossier de sauvegarde sélectionné : $folderName", Toast.LENGTH_LONG).show()
            viewModel.triggerAutoBackup()
        } else {
            BackupManager.markPromptAsShown(context)
        }
    }

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

    // Statistics counts calculés sur la base de données et le cycle actif
    val allClientsList by viewModel.allClients.collectAsState()
    val entreprisesCount = allClientsList.count { it.category == ClientCategory.ENTREPRISE }
    val physiquesCount = allClientsList.count { it.category == ClientCategory.PERSONNE_PHYSIQUE }
    val pendingCount = activeReminders.count { !it.smsEnvoye }
    val sentCount = activeReminders.count { it.smsEnvoye }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "Logo ComptaPocket SMS",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                        )
                        Column {
                            Text(
                                text = "ComptaPocket SMS",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = cabinetName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
            FloatingActionButton(
                onClick = {
                    editingReminder = null
                    showClientDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Nouveau Client")
            }
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
                    StatBadge("🏢 Soc.", entreprisesCount.toString(), Color(0xFF1E88E5))
                    StatBadge("👤 Phys.", physiquesCount.toString(), Color(0xFF8E24AA))
                    StatBadge("⏳ Attente", pendingCount.toString(), Color(0xFFE65100))
                    StatBadge("✅ Envoyé", sentCount.toString(), Color(0xFF2E7D32))
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
                    text = "${clientCards.size} client(s) • ${reminders.size} déclaration(s)",
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

            // Liste des Cartes Clients (Chaque client a une seule carte regroupant toutes ses déclarations actives)
            if (clientCards.isEmpty()) {
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
                    items(clientCards, key = { it.clientKey }) { clientCard ->
                        ClientCard(
                            clientWithActions = clientCard,
                            onEditClient = {
                                val firstReminder = clientCard.reminders.firstOrNull()
                                editingReminder = firstReminder
                                showClientDialog = true
                            },
                            onDeleteClient = {
                                viewModel.deleteRemindersForClient(clientCard.clientName, clientCard.telephone)
                                Toast.makeText(context, "Client ${clientCard.clientName} supprimé", Toast.LENGTH_SHORT).show()
                            },
                            onSendSingleSms = { reminder ->
                                viewModel.sendRemindersNow(listOf(reminder)) {
                                    Toast.makeText(context, "Envoi terminé pour ${reminder.clientName} (${ReminderType.getShortLabel(reminder.reminderType)})", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onResetStatus = { reminderId ->
                                viewModel.resetSmsStatus(reminderId)
                                Toast.makeText(context, "Statut réinitialisé en attente", Toast.LENGTH_SHORT).show()
                            },
                            onQuickDateChange = { reminder ->
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

    // Dialogue de choix du dossier de sauvegarde dès l'ouverture de la mise à jour
    if (showBackupFolderDialog) {
        AlertDialog(
            onDismissRequest = {
                BackupManager.markPromptAsShown(context)
                showBackupFolderDialog = false
            },
            icon = {
                Icon(
                    Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text("📁 Dossier de Sauvegarde", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Bienvenue dans cette nouvelle mise à jour de ComptaPocket SMS !\n\nVeuillez choisir le dossier de votre choix pour y stocker vos sauvegardes automatiques.",
                        fontSize = 14.sp
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "🔄 Sauvegarde glissante automatique :",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "L'application effectue une sauvegarde automatique après chaque ajout, modification ou suppression d'un client. Le dossier contiendra au maximum 5 sauvegardes, et la 6ème sauvegarde écrasera automatiquement la plus ancienne.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBackupFolderDialog = false
                        BackupManager.markPromptAsShown(context)
                        backupFolderLauncher.launch(null)
                    }
                ) {
                    Text("Choisir un dossier")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        BackupManager.markPromptAsShown(context)
                        showBackupFolderDialog = false
                    }
                ) {
                    Text("Plus tard")
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
fun ClientCard(
    clientWithActions: ClientWithActions,
    onEditClient: () -> Unit,
    onDeleteClient: () -> Unit,
    onSendSingleSms: (ClientReminderEntity) -> Unit,
    onResetStatus: (Int) -> Unit,
    onQuickDateChange: (ClientReminderEntity) -> Unit
) {
    val isEntreprise = clientWithActions.category == ClientCategory.ENTREPRISE
    val categoryBg = if (isEntreprise) Color(0xFFE3F2FD) else Color(0xFFF3E5F5)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 1. En-tête Client : [Symbole catégorie seul] + [Nom & Coordonnées] + [Actions Modifier/Supprimer]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
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
                            text = clientWithActions.clientName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Phone,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = clientWithActions.telephone,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!clientWithActions.taxId.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "• MF: ${clientWithActions.taxId}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Actions Client (Modifier / Supprimer)
                Row {
                    IconButton(onClick = onEditClient, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Modifier client",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onDeleteClient, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Supprimer",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.height(6.dp))

            // 2. Déclarations et SMS d'échéances regroupés sur cette même carte
            clientWithActions.reminders.forEach { reminder ->
                DeclarationActionRow(
                    reminder = reminder,
                    onSendNow = { onSendSingleSms(reminder) },
                    onResetStatus = { onResetStatus(reminder.id) },
                    onQuickDateChange = { onQuickDateChange(reminder) }
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
fun DeclarationActionRow(
    reminder: ClientReminderEntity,
    onSendNow: () -> Unit,
    onResetStatus: () -> Unit,
    onQuickDateChange: () -> Unit
) {
    var expandedSms by remember { mutableStateOf(false) }

    val (typeBg, typeColor, typeLabel) = when (reminder.reminderType) {
        ReminderType.DECLARATION_MENSUELLE -> Triple(Color(0xFFE3F2FD), Color(0xFF0D47A1), "📄 DM")
        ReminderType.CNSS -> Triple(Color(0xFFE8F5E9), Color(0xFF1B5E20), "🛡️ CNSS")
        ReminderType.ACOMPTES_PROVISIONNELS -> Triple(Color(0xFFFFF8E1), Color(0xFFB78103), "💰 AP")
        else -> Triple(Color(0xFFF3E5F5), Color(0xFF4A148C), "📌 Autre")
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Symbole et Abréviation de la déclaration (📄 DM, 🛡️ CNSS, 💰 AP)
                Surface(
                    color = typeBg,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = typeLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = typeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Dates d'échéance légale et d'envoi programmé
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Limite : ${reminder.dueDate}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Envoi : ${reminder.sendDate} à ${reminder.sendTime}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onQuickDateChange, modifier = Modifier.size(18.dp)) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Changer date",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Statut Envoyé / En attente (compact, sans mot Mention)
                Surface(
                    color = if (reminder.smsEnvoye) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.clickable { onResetStatus() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (reminder.smsEnvoye) Icons.Default.CheckCircle else Icons.Default.Notifications,
                            contentDescription = null,
                            tint = if (reminder.smsEnvoye) Color(0xFF2E7D32) else Color(0xFFE65100),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (reminder.smsEnvoye) "Envoyé" else "En attente",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (reminder.smsEnvoye) Color(0xFF2E7D32) else Color(0xFFE65100)
                        )
                    }
                }

                // Bouton d'envoi immédiat du SMS si en attente
                if (!reminder.smsEnvoye) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = onSendNow, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Envoyer maintenant",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            // Aperçu pliable du message SMS
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedSms = !expandedSms },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (expandedSms) "Masquer le SMS" else "Voir le SMS...",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    if (expandedSms) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
            }

            if (expandedSms) {
                Spacer(modifier = Modifier.height(3.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = reminder.texteSms,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
        }
    }
}
