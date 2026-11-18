package com.comptapocket.sms.presentation

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ClientReminderViewModel? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)

    var cabinetName by remember { mutableStateOf(prefs.getString("cabinet_name", "Bureau de Comptabilité") ?: "Bureau de Comptabilité") }
    var cabinetPhone by remember { mutableStateOf(prefs.getString("cabinet_phone", "") ?: "") }
    var serverIp by remember { mutableStateOf(prefs.getString("server_ip", "") ?: "") }
    var showAboutDialog by remember { mutableStateOf(false) }

    // Option automatisation de l'envoi
    var autoSendEnabled by remember { mutableStateOf(prefs.getBoolean("auto_send_enabled", true)) }

    // Échéances fixes par catégorie
    var dueDayEntreprise by remember { mutableStateOf(prefs.getInt("due_day_entreprise", 28).toString()) }
    var dueDayPhysique by remember { mutableStateOf(prefs.getInt("due_day_physique", 15).toString()) }
    var cnssDueDay by remember { mutableStateOf(prefs.getInt("cnss_due_day", 15).toString()) }
    var acomptesDayEntreprise by remember { mutableStateOf(prefs.getInt("acomptes_day_entreprise", 28).toString()) }
    var acomptesDayPhysique by remember { mutableStateOf(prefs.getInt("acomptes_day_physique", 25).toString()) }

    var defaultDaysBefore by remember { mutableStateOf(prefs.getInt("default_days_before", 3).toString()) }
    var defaultSendTime by remember { mutableStateOf(prefs.getString("default_send_time", "09:00") ?: "09:00") }

    val defaultMensuelle = "Bonjour [Nom], votre bureau comptable [Cabinet] vous rappelle que la date limite de votre déclaration mensuelle est fixée au [Date_Echeance]. Merci de nous faire parvenir vos documents."
    val defaultCnss = "Bonjour [Nom], rappel de votre bureau comptable [Cabinet] : la date limite de paiement des cotisations CNSS est fixée au [Date_Echeance]. Merci de régler dans les délais pour éviter toute pénalité."
    val defaultAcomptes = "Bonjour [Nom], votre bureau comptable [Cabinet] vous informe que l'échéance de l'acompte provisionnel est le [Date_Echeance]. Merci de nous contacter pour validation et dépôt."

    var templateMensuelle by remember { mutableStateOf(prefs.getString("template_mensuelle", defaultMensuelle) ?: defaultMensuelle) }
    var templateCnss by remember { mutableStateOf(prefs.getString("template_cnss", defaultCnss) ?: defaultCnss) }
    var templateAcomptes by remember { mutableStateOf(prefs.getString("template_acomptes", defaultAcomptes) ?: defaultAcomptes) }

    var smtpEmail by remember { mutableStateOf(prefs.getString("smtp_email", "") ?: "") }
    var smtpPassword by remember { mutableStateOf(prefs.getString("smtp_password", "") ?: "") }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    val json = viewModel?.exportDatabaseToJson() ?: "{}"
                    os.write(json.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Sauvegarde réussie dans le fichier sélectionné !", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Erreur sauvegarde: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
                if (!json.isNullOrBlank()) {
                    viewModel?.importDatabaseFromJson(json) { success, message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Erreur restauration: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveSettings() {
        prefs.edit()
            .putString("cabinet_name", cabinetName.trim())
            .putString("cabinet_phone", cabinetPhone.trim())
            .putString("server_ip", serverIp.trim())
            .putBoolean("auto_send_enabled", autoSendEnabled)
            .putInt("due_day_entreprise", dueDayEntreprise.toIntOrNull() ?: 28)
            .putInt("due_day_physique", dueDayPhysique.toIntOrNull() ?: 15)
            .putInt("cnss_due_day", cnssDueDay.toIntOrNull() ?: 15)
            .putInt("acomptes_day_entreprise", acomptesDayEntreprise.toIntOrNull() ?: 28)
            .putInt("acomptes_day_physique", acomptesDayPhysique.toIntOrNull() ?: 25)
            .putInt("default_days_before", defaultDaysBefore.toIntOrNull() ?: 3)
            .putString("default_send_time", defaultSendTime.trim())
            .putString("template_mensuelle", templateMensuelle.trim())
            .putString("template_cnss", templateCnss.trim())
            .putString("template_acomptes", templateAcomptes.trim())
            .putString("smtp_email", smtpEmail.trim())
            .putString("smtp_password", smtpPassword.trim())
            .apply()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres ComptaPocket", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        saveSettings()
                        Toast.makeText(context, "Paramètres enregistrés avec succès", Toast.LENGTH_SHORT).show()
                        onBack()
                    }) {
                        Icon(Icons.Default.Done, contentDescription = "Enregistrer")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1 : Automatisation de l'envoi des SMS
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Automatiser l'envoi des SMS",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Envoie automatiquement les SMS aux dates et heures fixées ci-dessous sans intervention manuelle.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoSendEnabled,
                        onCheckedChange = { autoSendEnabled = it }
                    )
                }
            }

            // Section 2 : Configuration du bureau comptable
            Text("Bureau de Comptabilité / Expert-Comptable", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = cabinetName,
                onValueChange = { cabinetName = it },
                label = { Text("Nom du Bureau / Raison sociale") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = cabinetPhone,
                onValueChange = { cabinetPhone = it },
                label = { Text("Numéro de contact du cabinet") },
                modifier = Modifier.fillMaxWidth()
            )

            Divider()

            // Section 3 : Échéances légales fixes par catégorie
            Text("Échéances Fixes (Législation par Catégorie)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Ces dates sont fixes pour chaque catégorie. Dès l'enregistrement d'un client, ses rappels sont programmés automatiquement selon ces valeurs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Déclaration Mensuelle
            Text("1. Déclarations Mensuelles (TVA, Retenues...) :", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = dueDayEntreprise,
                    onValueChange = { dueDayEntreprise = it.filter { c -> c.isDigit() } },
                    label = { Text("🏢 Entreprises (Jour)") },
                    supportingText = { Text("ex: 28 du mois") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )

                OutlinedTextField(
                    value = dueDayPhysique,
                    onValueChange = { dueDayPhysique = it.filter { c -> c.isDigit() } },
                    label = { Text("👤 Pers. Physiques (Jour)") },
                    supportingText = { Text("ex: 15 du mois") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
            }

            // CNSS
            Text("2. Cotisations CNSS (Trimestrielles) :", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            OutlinedTextField(
                value = cnssDueDay,
                onValueChange = { cnssDueDay = it.filter { c -> c.isDigit() } },
                label = { Text("🛡️ Échéance CNSS (Jour du mois suivant le trimestre)") },
                supportingText = { Text("ex: 15 (15 Janv, 15 Avril, 15 Juil, 15 Oct)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            )

            // Acomptes provisionnels
            Text("3. Acomptes Provisionnels (Juin, Septembre, Décembre) :", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = acomptesDayEntreprise,
                    onValueChange = { acomptesDayEntreprise = it.filter { c -> c.isDigit() } },
                    label = { Text("🏢 Entreprises (Jour)") },
                    supportingText = { Text("ex: 28") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )

                OutlinedTextField(
                    value = acomptesDayPhysique,
                    onValueChange = { acomptesDayPhysique = it.filter { c -> c.isDigit() } },
                    label = { Text("👤 Pers. Physiques (Jour)") },
                    supportingText = { Text("ex: 25") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
            }

            // Programmation de l'envoi automatique
            Text("4. Programmation des Envois SMS :", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = defaultDaysBefore,
                    onValueChange = { defaultDaysBefore = it.filter { c -> c.isDigit() } },
                    label = { Text("Délai d'envoi avant échéance") },
                    supportingText = { Text("ex: 3 jours avant") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )

                OutlinedTextField(
                    value = defaultSendTime,
                    onValueChange = { defaultSendTime = it },
                    label = { Text("Heure d'envoi") },
                    supportingText = { Text("ex: 09:00") },
                    modifier = Modifier.weight(1f)
                )
            }

            // Bouton de réalignement automatique pour tous les clients existants
            if (viewModel != null) {
                OutlinedButton(
                    onClick = {
                        saveSettings()
                        viewModel.realignAllClientsWithFixedSettings()
                        Toast.makeText(context, "Tous les rappels clients ont été réalignés sur les dates des réglages !", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("🔄 Réaligner tous les clients sur ces dates")
                }
            }

            Divider()

            // Section Sauvegarde & Restauration de la Base de Données Clients
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "💾 Sauvegarde & Restauration de la Base",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Exportez tous vos clients et leurs échéances dans un fichier de sauvegarde JSON sécurisé, ou restaurez une sauvegarde précédente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                                createDocumentLauncher.launch("comptapocket_backup_$timeStamp.json")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sauvegarder", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                openDocumentLauncher.launch(arrayOf("application/json", "*/*"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restaurer", fontSize = 13.sp)
                        }
                    }
                }
            }

            Divider()

            // À propos & Version de l'application
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("À propos & Mises à jour", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Consulter la version actuelle, les nouveautés et vérifier/télécharger les mises à jour hébergées sur GitHub.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showAboutDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ouvrir À propos & Mises à jour", fontSize = 13.sp)
                    }
                }
            }

            Divider()

            // Modèles SMS personnalisables
            Text("Modèles des SMS (Templates)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Balises reconnues : [Nom], [Cabinet], [Date_Echeance], [Categorie], [Type]",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = templateMensuelle,
                onValueChange = { templateMensuelle = it },
                label = { Text("Modèle Déclaration mensuelle") },
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )

            OutlinedTextField(
                value = templateCnss,
                onValueChange = { templateCnss = it },
                label = { Text("Modèle Cotisations CNSS") },
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )

            OutlinedTextField(
                value = templateAcomptes,
                onValueChange = { templateAcomptes = it },
                label = { Text("Modèle Acomptes provisionnels") },
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )

            Divider()

            // Configuration Email SMTP
            Text("Configuration Email (Gmail SMTP)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = smtpEmail,
                onValueChange = { smtpEmail = it },
                label = { Text("Adresse Gmail expéditeur") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = smtpPassword,
                onValueChange = { smtpPassword = it },
                label = { Text("Mot de passe d'application Google (16 lettres)") },
                modifier = Modifier.fillMaxWidth()
            )

            Divider()

            // Synchronisation Réseau
            Text("Synchronisation Réseau Local (Optionnel)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = serverIp,
                onValueChange = { serverIp = it },
                label = { Text("Adresse IP du PC (Serveur ComptaPocket)") },
                placeholder = { Text("ex: 192.168.1.15:3000") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    saveSettings()
                    Toast.makeText(context, "Paramètres enregistrés avec succès", Toast.LENGTH_SHORT).show()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Enregistrer les Paramètres")
            }
        }
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
}
