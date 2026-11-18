package com.comptapocket.sms.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comptapocket.sms.data.local.ClientCategory
import com.comptapocket.sms.data.local.ClientEntity
import com.comptapocket.sms.data.local.ClientReminderEntity
import com.comptapocket.sms.data.local.ReminderType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientDatabaseScreen(
    viewModel: ClientReminderViewModel,
    onNavigateToSettings: () -> Unit,
    onAddReminderForClient: (ClientEntity) -> Unit
) {
    val context = LocalContext.current
    val clients by viewModel.filteredClients.collectAsState()
    val allClients by viewModel.allClients.collectAsState()
    val allReminders by viewModel.allReminders.collectAsState()

    val searchQuery by viewModel.clientSearchQuery.collectAsState()
    val categoryFilter by viewModel.clientCategoryFilter.collectAsState()

    var showEditDialog by remember { mutableStateOf(false) }
    var editingClient by remember { mutableStateOf<ClientEntity?>(null) }
    var clientToDelete by remember { mutableStateOf<ClientEntity?>(null) }

    val totalCount = allClients.size
    val entreprisesCount = allClients.count { it.category == ClientCategory.ENTREPRISE }
    val physiquesCount = allClients.count { it.category == ClientCategory.PERSONNE_PHYSIQUE }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Base de données Clients", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("$totalCount client(s) répertorié(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editingClient = null
                    showEditDialog = true
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
            // Synthèse des clients
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    StatBadge("👥 Total", totalCount.toString(), MaterialTheme.colorScheme.primary)
                    StatBadge("🏢 Sociétés", entreprisesCount.toString(), Color(0xFF1E88E5))
                    StatBadge("👤 Pers. physiques", physiquesCount.toString(), Color(0xFF8E24AA))
                }
            }

            // Barre de recherche
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setClientSearchQuery(it) },
                placeholder = { Text("Rechercher client, téléphone, matricule, email...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setClientSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Effacer")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                singleLine = true
            )

            // Filtres rapides de catégorie
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = categoryFilter == "ALL",
                    onClick = { viewModel.setClientCategoryFilter("ALL") },
                    label = { Text("Tous ($totalCount)") }
                )
                FilterChip(
                    selected = categoryFilter == ClientCategory.ENTREPRISE,
                    onClick = { viewModel.setClientCategoryFilter(ClientCategory.ENTREPRISE) },
                    label = { Text("🏢 Sociétés ($entreprisesCount)") }
                )
                FilterChip(
                    selected = categoryFilter == ClientCategory.PERSONNE_PHYSIQUE,
                    onClick = { viewModel.setClientCategoryFilter(ClientCategory.PERSONNE_PHYSIQUE) },
                    label = { Text("👤 Pers. physiques ($physiquesCount)") }
                )
            }

            // Liste des clients
            if (clients.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Aucun client dans la base",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Cliquez sur 'Nouveau Client' pour enregistrer une société ou une personne physique dans votre annuaire comptable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(clients, key = { it.id }) { client ->
                        val clientReminders = allReminders.filter {
                            it.telephone == client.telephone || it.clientName.equals(client.clientName, ignoreCase = true)
                        }

                        ClientDirectoryCard(
                            client = client,
                            reminders = clientReminders,
                            onEdit = {
                                editingClient = client
                                showEditDialog = true
                            },
                            onDelete = {
                                clientToDelete = client
                            },
                            onAddReminder = {
                                onAddReminderForClient(client)
                            },
                            onCall = {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${client.telephone}"))
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
    }

    // Dialogue d'ajout ou de modification d'un client dans la base de données
    if (showEditDialog) {
        ClientDataDialog(
            client = editingClient,
            onDismiss = {
                showEditDialog = false
                editingClient = null
            },
            onSave = { name, category, phone, email, taxId, address, notes ->
                if (editingClient != null) {
                    viewModel.updateClientInDatabase(
                        id = editingClient!!.id,
                        oldName = editingClient!!.clientName,
                        oldPhone = editingClient!!.telephone,
                        newName = name,
                        newCategory = category,
                        newPhone = phone,
                        newEmail = email,
                        newTaxId = taxId,
                        address = address,
                        notes = notes
                    )
                    Toast.makeText(context, "Données du client mises à jour avec succès", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.addClientToDatabase(
                        clientName = name,
                        category = category,
                        telephone = phone,
                        email = email,
                        taxId = taxId,
                        address = address,
                        notes = notes
                    )
                    Toast.makeText(context, "Client enregistré dans la base de données", Toast.LENGTH_SHORT).show()
                }
                showEditDialog = false
                editingClient = null
            }
        )
    }

    // Confirmation de suppression
    clientToDelete?.let { client ->
        AlertDialog(
            onDismissRequest = { clientToDelete = null },
            title = { Text("Supprimer ce client ?") },
            text = {
                Text("Voulez-vous supprimer le client '${client.clientName}' de la base de données ainsi que toutes ses échéances programmées ?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteClientFromDatabase(client, deleteReminders = true)
                        Toast.makeText(context, "Client et échéances supprimés", Toast.LENGTH_SHORT).show()
                        clientToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                TextButton(onClick = { clientToDelete = null }) {
                    Text("Annuler")
                }
            }
        )
    }
}

@Composable
fun ClientDirectoryCard(
    client: ClientEntity,
    reminders: List<ClientReminderEntity>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddReminder: () -> Unit,
    onCall: () -> Unit
) {
    val isEntreprise = client.category == ClientCategory.ENTREPRISE
    val categoryColor = if (isEntreprise) Color(0xFF1976D2) else Color(0xFF7B1FA2)
    val categoryBg = if (isEntreprise) Color(0xFFE3F2FD) else Color(0xFFF3E5F5)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Ligne d'en-tête : Avatar + Nom + Catégorie
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
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
                            text = client.clientName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Bouton Appel rapide
                IconButton(onClick = onCall, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Phone, contentDescription = "Appeler", tint = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Coordonnées détaillées
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(client.telephone, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                if (!client.taxId.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("MF: ${client.taxId}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (!client.email.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(client.email, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Échéances / Rappels configurés pour ce client
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${reminders.size} rappel(s) enregistré(s) :",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (reminders.isEmpty()) {
                        Text("Aucun rappel actif. Cliquez sur 'Ajouter Rappel' pour planifier une échéance.", fontSize = 11.sp, color = Color.Gray)
                    } else {
                        reminders.forEach { rem ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when (rem.reminderType) {
                                            ReminderType.DECLARATION_MENSUELLE -> "📄 DM"
                                            ReminderType.CNSS -> "🛡️ CNSS"
                                            ReminderType.ACOMPTES_PROVISIONNELS -> "💰 AP"
                                            else -> "📌 ${rem.reminderType}"
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("(${rem.dueDate})", fontSize = 11.sp, color = Color.Gray)
                                }

                                if (rem.smsEnvoye) {
                                    Text("✅ Envoyé", fontSize = 11.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                                } else {
                                    Text("⏳ En attente", fontSize = 11.sp, color = Color(0xFFE65100), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Barre d'actions
            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onAddReminder,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ajouter Rappel", fontSize = 12.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Modifier client", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Supprimer client", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientDataDialog(
    client: ClientEntity? = null,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        category: String,
        phone: String,
        email: String?,
        taxId: String?,
        address: String?,
        notes: String?
    ) -> Unit
) {
    var name by remember { mutableStateOf(client?.clientName ?: "") }
    var category by remember { mutableStateOf(client?.category ?: ClientCategory.ENTREPRISE) }
    var phone by remember { mutableStateOf(client?.telephone ?: "") }
    var email by remember { mutableStateOf(client?.email ?: "") }
    var taxId by remember { mutableStateOf(client?.taxId ?: "") }
    var address by remember { mutableStateOf(client?.address ?: "") }
    var notes by remember { mutableStateOf(client?.notes ?: "") }

    var catDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (client == null) "Ajouter un Client à la Base" else "Modifier les Données du Client",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Catégorie
                ExposedDropdownMenuBox(
                    expanded = catDropdownExpanded,
                    onExpandedChange = { catDropdownExpanded = !catDropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = if (category == ClientCategory.ENTREPRISE) "🏢 Entreprise / Société" else "👤 Personne physique",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Catégorie fiscale *") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catDropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    ExposedDropdownMenu(
                        expanded = catDropdownExpanded,
                        onDismissRequest = { catDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("🏢 Entreprise / Société") },
                            onClick = {
                                category = ClientCategory.ENTREPRISE
                                catDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("👤 Personne physique") },
                            onClick = {
                                category = ClientCategory.PERSONNE_PHYSIQUE
                                catDropdownExpanded = false
                            }
                        )
                    }
                }

                // Nom / Raison sociale
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (category == ClientCategory.ENTREPRISE) "Raison sociale / Nom Société *" else "Nom & Prénom client *") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Téléphone Mobile
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Numéro Mobile (SMS) *") },
                    placeholder = { Text("ex: 98123456") },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone)
                )

                // Matricule fiscal
                OutlinedTextField(
                    value = taxId,
                    onValueChange = { taxId = it },
                    label = { Text("Matricule Fiscal (optionnel)") },
                    placeholder = { Text("ex: 1234567/A/M/000") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Email
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email (optionnel)") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email)
                )

                // Adresse
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Adresse / Ville (optionnel)") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Remarques / Notes
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes internes (optionnel)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && phone.isNotBlank()) {
                        onSave(name.trim(), category, phone.trim(), email.trim(), taxId.trim(), address.trim(), notes.trim())
                    }
                },
                enabled = name.isNotBlank() && phone.isNotBlank()
            ) {
                Text(if (client == null) "Enregistrer" else "Mettre à jour")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}
