package com.comptapocket.sms.presentation

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comptapocket.sms.data.local.ClientCategory
import com.comptapocket.sms.data.local.ClientReminderEntity
import com.comptapocket.sms.data.local.ReminderType
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientDialog(
    initialReminder: ClientReminderEntity? = null,
    viewModel: ClientReminderViewModel,
    onDismiss: () -> Unit,
    onSave: (
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
    ) -> Unit
) {
    val context = LocalContext.current

    var clientName by remember { mutableStateOf(initialReminder?.clientName ?: "") }
    var category by remember { mutableStateOf(initialReminder?.category ?: ClientCategory.ENTREPRISE) }
    var reminderType by remember { mutableStateOf(initialReminder?.reminderType ?: ReminderType.DECLARATION_MENSUELLE) }
    var telephone by remember { mutableStateOf(initialReminder?.telephone ?: "") }
    var email by remember { mutableStateOf(initialReminder?.email ?: "") }
    var taxId by remember { mutableStateOf(initialReminder?.taxId ?: "") }

    var dueDate by remember {
        mutableStateOf(
            initialReminder?.dueDate ?: viewModel.computeDefaultDueDate(category, reminderType)
        )
    }

    var sendDate by remember {
        mutableStateOf(
            initialReminder?.sendDate ?: viewModel.computeDefaultSendDate(dueDate)
        )
    }

    var sendTime by remember { mutableStateOf(initialReminder?.sendTime ?: viewModel.getDefaultSendTime()) }
    var language by remember { mutableStateOf(initialReminder?.language ?: "fr") }
    var texteSms by remember { mutableStateOf(initialReminder?.texteSms ?: "") }

    // Toggle pour déplier la personnalisation manuelle des dates et du SMS
    var showManualCustomization by remember { mutableStateOf(initialReminder != null) }

    // Recalcul automatique des dates légales et du texte SMS dès que la catégorie ou le type change
    LaunchedEffect(category, reminderType) {
        if (initialReminder == null) {
            val effectiveType = if (reminderType == ReminderType.PACK_COMPLET) ReminderType.DECLARATION_MENSUELLE else reminderType
            val schedule = viewModel.computeInitialSchedule(category, effectiveType)
            dueDate = schedule.dueDate
            sendDate = schedule.sendDate
            sendTime = schedule.sendTime
            texteSms = viewModel.generateSmsText(
                clientName = clientName.ifBlank { "[Nom du Client]" },
                category = category,
                reminderType = effectiveType,
                dueDate = schedule.dueDate,
                language = language
            )
        }
    }

    // Mise à jour automatique de l'aperçu du SMS quand le nom change (si création)
    LaunchedEffect(clientName, dueDate, language) {
        if (initialReminder == null || texteSms.isBlank()) {
            val effectiveType = if (reminderType == ReminderType.PACK_COMPLET) ReminderType.DECLARATION_MENSUELLE else reminderType
            texteSms = viewModel.generateSmsText(
                clientName = clientName.ifBlank { "[Nom du Client]" },
                category = category,
                reminderType = effectiveType,
                dueDate = dueDate.ifBlank { "[Date Limite]" },
                language = language
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialReminder == null) "Ajouter un Client" else "Modifier le Client",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Menu déroulant 1 : Catégorie fiscale (Entreprise ou Personne physique)
                var catDropdownExpanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = catDropdownExpanded,
                    onExpandedChange = { catDropdownExpanded = !catDropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = if (category == ClientCategory.ENTREPRISE) "🏢 Entreprise (Société)" else "👤 Personne physique",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Catégorie fiscale du client *") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catDropdownExpanded) },
                        leadingIcon = {
                            Icon(if (category == ClientCategory.ENTREPRISE) Icons.Default.AccountBox else Icons.Default.Person, contentDescription = null)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    ExposedDropdownMenu(
                        expanded = catDropdownExpanded,
                        onDismissRequest = { catDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("🏢 Entreprise (Société)") },
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

                // Nom du client / Raison sociale
                OutlinedTextField(
                    value = clientName,
                    onValueChange = { clientName = it },
                    label = { Text(if (category == ClientCategory.ENTREPRISE) "Raison sociale / Société *" else "Nom & Prénom client *") },
                    leadingIcon = {
                        Icon(if (category == ClientCategory.ENTREPRISE) Icons.Default.AccountBox else Icons.Default.Person, contentDescription = null)
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Téléphone Mobile (SMS)
                OutlinedTextField(
                    value = telephone,
                    onValueChange = { telephone = it },
                    label = { Text("Numéro Mobile (SMS) *") },
                    placeholder = { Text("ex: 98123456 ou +216...") },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone)
                )

                // Menu déroulant 2 : Type d'échéance / Déclaration
                var typeDropdownExpanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = typeDropdownExpanded,
                    onExpandedChange = { typeDropdownExpanded = !typeDropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = when (reminderType) {
                            ReminderType.DECLARATION_MENSUELLE -> "📄 Déclaration mensuelle (TVA, RS...)"
                            ReminderType.CNSS -> "🛡️ Cotisations CNSS (Trimestrielle)"
                            ReminderType.ACOMPTES_PROVISIONNELS -> "💰 Acomptes provisionnels"
                            ReminderType.PACK_COMPLET -> "⭐ Pack Complet (Mensuelle + CNSS + Acomptes)"
                            ReminderType.AUTRE -> "📌 Autre rappel / échéance"
                            else -> reminderType
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type d'échéance à programmer *") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeDropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    ExposedDropdownMenu(
                        expanded = typeDropdownExpanded,
                        onDismissRequest = { typeDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("📄 Déclaration mensuelle (TVA, RS...)") },
                            onClick = {
                                reminderType = ReminderType.DECLARATION_MENSUELLE
                                typeDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🛡️ Cotisations CNSS (Trimestrielle)") },
                            onClick = {
                                reminderType = ReminderType.CNSS
                                typeDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("💰 Acomptes provisionnels") },
                            onClick = {
                                reminderType = ReminderType.ACOMPTES_PROVISIONNELS
                                typeDropdownExpanded = false
                            }
                        )
                        if (initialReminder == null) {
                            DropdownMenuItem(
                                text = { Text("⭐ Pack Complet (Mensuelle + CNSS + Acomptes)") },
                                onClick = {
                                    reminderType = ReminderType.PACK_COMPLET
                                    typeDropdownExpanded = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("📌 Autre rappel / échéance") },
                            onClick = {
                                reminderType = ReminderType.AUTRE
                                typeDropdownExpanded = false
                            }
                        )
                    }
                }

                // Carte récapitulative des dates automatiques calculées selon les réglages
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val schedulePreview = viewModel.computeInitialSchedule(
                            category,
                            if (reminderType == ReminderType.PACK_COMPLET) ReminderType.DECLARATION_MENSUELLE else reminderType
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Done, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Dates & Envois automatisés (Réglages)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (reminderType == ReminderType.PACK_COMPLET) {
                            Text(
                                "⭐ Pack Complet : 3 rappels périodiques (Mensuelle, CNSS et Acomptes) seront enregistrés en base. Les échéances actives seront envoyées immédiatement, puis renouvelées périodiquement.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (schedulePreview.shouldSendImmediately) {
                            Text(
                                "⚡ Envoi Immédiat : Client ajouté dans la période active (avant le 15 / dans l'intervalle). Le premier SMS sera envoyé immédiatement pour ce mois, puis les prochains rappels seront renouvelés périodiquement.",
                                fontSize = 12.sp,
                                color = Color(0xFFE65100),
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                "📅 Date limite légale : $dueDate\n⏰ Envoi programmé le : $sendDate à $sendTime\n(Les rappels suivants seront ensuite renouvelés périodiquement)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Section dépliable : Coordonnées secondaires & Personnalisation manuelle (Optionnel)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showManualCustomization = !showManualCustomization }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (showManualCustomization) "Masquer les options manuelles" else "⚙️ Personnalisation manuelle (Optionnel)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        if (showManualCustomization) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (showManualCustomization) {
                    // Email
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email (optionnel)") },
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email)
                    )

                    // Matricule fiscal
                    OutlinedTextField(
                        value = taxId,
                        onValueChange = { taxId = it },
                        label = { Text("Matricule Fiscal (optionnel)") },
                        placeholder = { Text("ex: 1234567/A/M/000") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Date limite manuelle
                    OutlinedTextField(
                        value = dueDate,
                        onValueChange = { dueDate = it },
                        label = { Text("Date limite personnalisée") },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                val cal = Calendar.getInstance()
                                DatePickerDialog(
                                    context,
                                    { _, year, month, day ->
                                        val picked = String.format("%04d-%02d-%02d", year, month + 1, day)
                                        dueDate = picked
                                        if (initialReminder == null) {
                                            sendDate = viewModel.computeDefaultSendDate(picked)
                                        }
                                    },
                                    cal.get(Calendar.YEAR),
                                    cal.get(Calendar.MONTH),
                                    cal.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            }) {
                                Icon(Icons.Default.DateRange, contentDescription = "Choisir date limite")
                            }
                        }
                    )

                    // Date et heure d'envoi SMS manuelles
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = sendDate,
                            onValueChange = { sendDate = it },
                            label = { Text("Date d'envoi") },
                            modifier = Modifier.weight(1.2f),
                            readOnly = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    val cal = Calendar.getInstance()
                                    DatePickerDialog(
                                        context,
                                        { _, year, month, day ->
                                            sendDate = String.format("%04d-%02d-%02d", year, month + 1, day)
                                        },
                                        cal.get(Calendar.YEAR),
                                        cal.get(Calendar.MONTH),
                                        cal.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                }) {
                                    Icon(Icons.Default.DateRange, contentDescription = "Choisir date envoi")
                                }
                            }
                        )

                        OutlinedTextField(
                            value = sendTime,
                            onValueChange = { sendTime = it },
                            label = { Text("Heure") },
                            modifier = Modifier.weight(0.8f),
                            readOnly = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    TimePickerDialog(
                                        context,
                                        { _, hour, minute ->
                                            sendTime = String.format("%02d:%02d", hour, minute)
                                        },
                                        9, 0, true
                                    ).show()
                                }) {
                                    Icon(Icons.Default.Notifications, contentDescription = "Choisir heure")
                                }
                            }
                        )
                    }

                    // Message SMS personnalisé
                    OutlinedTextField(
                        value = texteSms,
                        onValueChange = { texteSms = it },
                        label = { Text("Texte du SMS personnalisé") },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        supportingText = {
                            Text("${texteSms.length} car. | Balises: [Nom], [Date_Echeance], [Cabinet]")
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (clientName.isNotBlank() && telephone.isNotBlank()) {
                        onSave(
                            clientName.trim(),
                            category,
                            telephone.trim(),
                            email.trim(),
                            taxId.trim(),
                            reminderType,
                            dueDate,
                            sendDate,
                            sendTime,
                            texteSms.trim(),
                            language
                        )
                    }
                },
                enabled = clientName.isNotBlank() && telephone.isNotBlank()
            ) {
                Text(if (initialReminder == null) "Enregistrer le Client" else "Mettre à jour")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}
