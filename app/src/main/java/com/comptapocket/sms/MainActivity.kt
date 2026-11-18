package com.comptapocket.sms

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.comptapocket.sms.data.local.ClientEntity
import com.comptapocket.sms.firebase.FirebaseSyncManager
import com.comptapocket.sms.presentation.ClientDatabaseScreen
import com.comptapocket.sms.presentation.ClientReminderViewModel
import com.comptapocket.sms.presentation.HomeScreen
import com.comptapocket.sms.presentation.LicenseScreen
import com.comptapocket.sms.presentation.SettingsScreen
import com.comptapocket.sms.worker.SyncService
import com.google.firebase.messaging.FirebaseMessaging

enum class MainNavigationTab {
    REMINDERS,
    CLIENT_DATABASE
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsGranted = permissions[Manifest.permission.SEND_SMS] ?: false
        if (smsGranted) {
            Toast.makeText(this, "Permission SMS accordée. Rappels automatiques activés.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "La permission SMS est requise pour l'envoi des rappels.", Toast.LENGTH_LONG).show()
        }
        checkExactAlarmAndBatteryExemptions()
    }

    private fun checkExactAlarmAndBatteryExemptions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur ouverture SCHEDULE_EXACT_ALARM: ${e.message}")
                }
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur ouverture REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: ${e.message}")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkExactAlarmAndBatteryExemptions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Démarrage du service d'arrière-plan pour synchronisation et alarmes
        val serviceIntent = Intent(this, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        checkAndRequestPermissions()

        val prefs = getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)

        // Initialisation du réveil Cloud via Firebase Cloud Messaging (FCM)
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.i(TAG, "Token FCM initialisé: $token")
                    prefs.edit().putString("fcm_token", token).apply()
                    FirebaseSyncManager.saveDeviceToken(applicationContext, token)
                } else {
                    Log.w(TAG, "Échec récupération FCM token: ${task.exception?.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur initialisation FCM: ${e.message}")
        }

        setContent {
            var isLicensed by remember {
                mutableStateOf(prefs.getBoolean("is_licensed", false))
            }
            var cabinetName by remember {
                mutableStateOf(prefs.getString("cabinet_name", "") ?: "")
            }

            var currentTab by remember { mutableStateOf(MainNavigationTab.REMINDERS) }
            var showSettings by remember { mutableStateOf(false) }
            var clientForReminder by remember { mutableStateOf<ClientEntity?>(null) }

            val colorScheme = lightColorScheme(
                primary = Color(0xFF0D47A1), // Bleu marine comptable
                onPrimary = Color.White,
                primaryContainer = Color(0xFFD6E4FF),
                onPrimaryContainer = Color(0xFF001B3E),
                secondary = Color(0xFFB78103), // Or / Médaille
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFFFDF9E),
                onSecondaryContainer = Color(0xFF261900),
                background = Color(0xFFF8F9FE),
                surface = Color.White,
                surfaceVariant = Color(0xFFEDF1F8)
            )

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isLicensed) {
                        val viewModel: ClientReminderViewModel = viewModel()

                        if (showSettings) {
                            SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
                        } else {
                            Scaffold(
                                bottomBar = {
                                    NavigationBar {
                                        NavigationBarItem(
                                            icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                                            label = { Text("Échéances & SMS") },
                                            selected = currentTab == MainNavigationTab.REMINDERS,
                                            onClick = { currentTab = MainNavigationTab.REMINDERS }
                                        )
                                        NavigationBarItem(
                                            icon = { Icon(Icons.Default.Person, contentDescription = null) },
                                            label = { Text("Base Clients") },
                                            selected = currentTab == MainNavigationTab.CLIENT_DATABASE,
                                            onClick = { currentTab = MainNavigationTab.CLIENT_DATABASE }
                                        )
                                    }
                                }
                            ) { innerPadding ->
                                Box(modifier = Modifier.padding(innerPadding)) {
                                    when (currentTab) {
                                        MainNavigationTab.REMINDERS -> {
                                            HomeScreen(
                                                viewModel = viewModel,
                                                onNavigateToSettings = { showSettings = true },
                                                initialClientForReminder = clientForReminder,
                                                onClearInitialClient = { clientForReminder = null }
                                            )
                                        }
                                        MainNavigationTab.CLIENT_DATABASE -> {
                                            ClientDatabaseScreen(
                                                viewModel = viewModel,
                                                onNavigateToSettings = { showSettings = true },
                                                onAddReminderForClient = { client ->
                                                    clientForReminder = client
                                                    currentTab = MainNavigationTab.REMINDERS
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        LicenseScreen(
                            onLicenseValid = { name ->
                                prefs.edit()
                                    .putBoolean("is_licensed", true)
                                    .putString("cabinet_name", name)
                                    .apply()
                                cabinetName = name
                                isLicensed = true
                                Toast.makeText(this@MainActivity, "Bienvenue au cabinet $name", Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.SEND_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
}
