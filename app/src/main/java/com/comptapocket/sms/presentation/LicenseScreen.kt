package com.comptapocket.sms.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comptapocket.sms.R
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(onLicenseValid: (String) -> Unit) {
    var cabinetName by remember { mutableStateOf("") }
    var licenseKey by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.app_logo),
            contentDescription = "Logo ComptaPocket SMS",
            modifier = Modifier
                .size(130.dp)
                .clip(CircleShape)
                .padding(bottom = 8.dp)
        )

        Text(
            "ComptaPocket SMS",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            "Rappels d'échéances fiscales & sociales",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(28.dp))

        OutlinedTextField(
            value = cabinetName,
            onValueChange = { cabinetName = it },
            label = { Text("Nom du Bureau Comptable / Expert-Comptable") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = licenseKey,
            onValueChange = { licenseKey = it },
            label = { Text("Clé d'activation (XXXX-XXXX-XXXX-XXXX)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (validateLicense(cabinetName, licenseKey)) {
                    errorMessage = ""
                    onLicenseValid(cabinetName.trim().uppercase())
                } else {
                    errorMessage = "Clé de licence invalide pour ce bureau comptable."
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text("Activer ComptaPocket", fontSize = 17.sp)
        }

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(errorMessage, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
    }
}

fun validateLicense(name: String, enteredKey: String): Boolean {
    val clean = name.trim().uppercase()
    val cleanKey = enteredKey.trim().uppercase()
    if (clean.isEmpty() || cleanKey.isEmpty()) return false

    val salts = listOf("COMPTA_SEC_2026", "MEDWIN_SEC_2026")
    for (salt in salts) {
        try {
            val raw = clean + salt
            val bytes = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
            val md5Hex = bytes.joinToString("") { "%02x".format(it) }.uppercase()
            val keyBase = md5Hex.take(16)
            val expectedKey = "${keyBase.substring(0, 4)}-${keyBase.substring(4, 8)}-${keyBase.substring(8, 12)}-${keyBase.substring(12, 16)}"
            if (cleanKey == expectedKey) return true
        } catch (e: Exception) {}
    }
    return false
}
