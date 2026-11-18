package com.comptapocket.sms.presentation

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comptapocket.sms.data.local.ClientCategory
import com.comptapocket.sms.data.local.ClientReminderEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchDateDialog(
    allReminders: List<ClientReminderEntity>,
    onDismiss: () -> Unit,
    onApplyExactDate: (targetCategory: String?, newDate: String, newTime: String) -> Unit,
    onApplyShiftDays: (targetCategory: String?, shiftDays: Int) -> Unit
) {
    val context = LocalContext.current

    // Périmètre : "ALL", "ENTREPRISE", "PERSONNE_PHYSIQUE"
    var selectedScope by remember { mutableStateOf("ALL") }

    // Mode : "EXACT_DATE" ou "SHIFT_DAYS"
    var selectedMode by remember { mutableStateOf("EXACT_DATE") }

    val cal = Calendar.getInstance()
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val defaultDate = format.format(cal.time)

    var exactDate by remember { mutableStateOf(defaultDate) }
    var exactTime by remember { mutableStateOf("09:00") }
    var shiftDays by remember { mutableStateOf("1") }

    // Calcul du nombre de fiches ciblées
    val targetCount = remember(selectedScope, allReminders) {
        val pending = allReminders.filter { !it.smsEnvoye }
        when (selectedScope) {
            ClientCategory.ENTREPRISE -> pending.count { it.category == ClientCategory.ENTREPRISE }
            ClientCategory.PERSONNE_PHYSIQUE -> pending.count { it.category == ClientCategory.PERSONNE_PHYSIQUE }
            else -> pending.size
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Modification collective des dates d'envoi", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1 : Périmètre cible
                Text(
                    "1. Choisir la catégorie cible :",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Option Tous
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedScope == "ALL",
                            onClick = { selectedScope = "ALL" }
                        )
                        Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                        Text("Tous les clients à la fois", fontWeight = FontWeight.SemiBold)
                    }

                    // Option Entreprises
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedScope == ClientCategory.ENTREPRISE,
                            onClick = { selectedScope = ClientCategory.ENTREPRISE }
                        )
                        Icon(Icons.Default.AccountBox, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                        Text("Entreprises uniquement (Sociétés)")
                    }

                    // Option Personnes physiques
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedScope == ClientCategory.PERSONNE_PHYSIQUE,
                            onClick = { selectedScope = ClientCategory.PERSONNE_PHYSIQUE }
                        )
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                        Text("Personnes physiques uniquement")
                    }
                }

                // Badge du nombre de clients concernés
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🎯 Clients concernés en attente : $targetCount",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Divider()

                // Section 2 : Type de modification
                Text(
                    "2. Méthode d'ajustement de la date :",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedMode == "EXACT_DATE",
                        onClick = { selectedMode = "EXACT_DATE" },
                        label = { Text("Nouvelle date fixe") },
                        modifier = Modifier.weight(1f)
                    )

                    FilterChip(
                        selected = selectedMode == "SHIFT_DAYS",
                        onClick = { selectedMode = "SHIFT_DAYS" },
                        label = { Text("Décaler de N jours") },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (selectedMode == "EXACT_DATE") {
                    Text("Définir la nouvelle date et heure d'envoi pour toute la sélection :", style = MaterialTheme.typography.bodySmall)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = exactDate,
                            onValueChange = { exactDate = it },
                            label = { Text("Date d'envoi") },
                            modifier = Modifier.weight(1.2f),
                            readOnly = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    val now = Calendar.getInstance()
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            exactDate = String.format("%04d-%02d-%02d", y, m + 1, d)
                                        },
                                        now.get(Calendar.YEAR),
                                        now.get(Calendar.MONTH),
                                        now.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                }) {
                                    Icon(Icons.Default.DateRange, contentDescription = null)
                                }
                            }
                        )

                        OutlinedTextField(
                            value = exactTime,
                            onValueChange = { exactTime = it },
                            label = { Text("Heure") },
                            modifier = Modifier.weight(0.8f),
                            readOnly = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    TimePickerDialog(
                                        context,
                                        { _, h, min ->
                                            exactTime = String.format("%02d:%02d", h, min)
                                        },
                                        9, 0, true
                                    ).show()
                                }) {
                                    Icon(Icons.Default.Notifications, contentDescription = null)
                                }
                            }
                        )
                    }
                } else {
                    Text("Reporter ou avancer la date d'envoi actuelle :", style = MaterialTheme.typography.bodySmall)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = shiftDays,
                            onValueChange = { str -> shiftDays = str.filter { it.isDigit() || it == '-' } },
                            label = { Text("Nombre de jours") },
                            placeholder = { Text("ex: 2 pour +2j, -1 pour -1j") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )

                        Text("jours", fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(onClick = { shiftDays = "1" }, label = { Text("+1 jour") })
                        AssistChip(onClick = { shiftDays = "2" }, label = { Text("+2 jours") })
                        AssistChip(onClick = { shiftDays = "3" }, label = { Text("+3 jours") })
                        AssistChip(onClick = { shiftDays = "-1" }, label = { Text("-1 jour") })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val scopeParam = if (selectedScope == "ALL") null else selectedScope
                    if (selectedMode == "EXACT_DATE") {
                        onApplyExactDate(scopeParam, exactDate, exactTime)
                    } else {
                        val days = shiftDays.toIntOrNull() ?: 0
                        onApplyShiftDays(scopeParam, days)
                    }
                    onDismiss()
                },
                enabled = targetCount > 0
            ) {
                Text("Appliquer ($targetCount)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}
