package com.comptapocket.sms.presentation

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comptapocket.sms.simulation.SimulationDateManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun SimulationDateDialog(
    onDismiss: () -> Unit,
    onDateChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val simulatedCalendar by SimulationDateManager.simulatedDateFlow.collectAsState()
    val isSimulationActive = simulatedCalendar != null

    val todayCal = SimulationDateManager.getTodayCalendar()
    val displayFormat = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.FRENCH)
    val formattedCurrent = displayFormat.format(todayCal.time).replaceFirstChar { it.uppercase() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("🧪 Mode Simulation de Date", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Statut de la date
                Surface(
                    color = if (isSimulationActive) Color(0xFFFFF3E0) else Color(0xFFE8F5E9),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isSimulationActive) "⚠️ SIMULATION DATE ACTIVE" else "✅ DATE RÉELLE DU SYSTÈME",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (isSimulationActive) Color(0xFFE65100) else Color(0xFF2E7D32)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formattedCurrent,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            color = if (isSimulationActive) Color(0xFFBF360C) else Color(0xFF1B5E20)
                        )
                    }
                }

                Text(
                    text = "Choisissez une date pour tester le comportement de ComptaPocket SMS (déclarations mensuelles, cotisations CNSS, acomptes provisionnels, bascule de mois).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Bouton Sélecteur Calendrier libre
                Button(
                    onClick = {
                        val c = SimulationDateManager.getTodayCalendar()
                        DatePickerDialog(
                            context,
                            { _, y, m, d ->
                                SimulationDateManager.setSimulatedDate(y, m, d)
                                onDateChanged()
                            },
                            c.get(Calendar.YEAR),
                            c.get(Calendar.MONTH),
                            c.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Choisir une date précise...")
                }

                // Sauts rapides (+1j, -1j, +1 mois, -1 mois)
                Text("Sauts rapides :", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            SimulationDateManager.shiftDays(-1)
                            onDateChanged()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("-1 jour", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            SimulationDateManager.shiftDays(1)
                            onDateChanged()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("+1 jour", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            SimulationDateManager.shiftMonths(1)
                            onDateChanged()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("+1 mois", fontSize = 12.sp)
                    }
                }

                Divider()

                // Raccourcis vers les échéances clés
                Text("Échéances clés à tester :", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                // Déclarations Mensuelles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SuggestionChip(
                        onClick = {
                            val c = SimulationDateManager.getTodayCalendar()
                            SimulationDateManager.setSimulatedDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH), 15)
                            onDateChanged()
                        },
                        label = { Text("15 du mois (DM Physique)", fontSize = 11.sp) }
                    )
                    SuggestionChip(
                        onClick = {
                            val c = SimulationDateManager.getTodayCalendar()
                            SimulationDateManager.setSimulatedDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH), 28)
                            onDateChanged()
                        },
                        label = { Text("28 du mois (DM Société)", fontSize = 11.sp) }
                    )
                }

                // Trimestres CNSS (Janvier, Avril, Juillet, Octobre)
                Text("Trimestres CNSS :", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val y = todayCal.get(Calendar.YEAR)
                    listOf(
                        "15 Jan" to (Calendar.JANUARY to 15),
                        "15 Avr" to (Calendar.APRIL to 15),
                        "15 Juil" to (Calendar.JULY to 15),
                        "15 Oct" to (Calendar.OCTOBER to 15)
                    ).forEach { (lbl, pair) ->
                        OutlinedButton(
                            onClick = {
                                SimulationDateManager.setSimulatedDate(y, pair.first, pair.second)
                                onDateChanged()
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(lbl, fontSize = 10.sp)
                        }
                    }
                }

                // Acomptes Provisionnels AP (Juin, Septembre, Décembre)
                Text("Acomptes Provisionnels (AP) :", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val y = todayCal.get(Calendar.YEAR)
                    listOf(
                        "28 Juin (AP1)" to (Calendar.JUNE to 28),
                        "28 Sept (AP2)" to (Calendar.SEPTEMBER to 28),
                        "28 Déc (AP3)" to (Calendar.DECEMBER to 28)
                    ).forEach { (lbl, pair) ->
                        OutlinedButton(
                            onClick = {
                                SimulationDateManager.setSimulatedDate(y, pair.first, pair.second)
                                onDateChanged()
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(lbl, fontSize = 10.sp)
                        }
                    }
                }

                // Réinitialisation vers date réelle
                if (isSimulationActive) {
                    Divider()
                    TextButton(
                        onClick = {
                            SimulationDateManager.resetToRealDate()
                            onDateChanged()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Revenir à la date réelle du système", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Fermer")
            }
        }
    )
}
