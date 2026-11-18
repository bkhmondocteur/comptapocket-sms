package com.comptapocket.sms.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

object ClientCategory {
    const val ENTREPRISE = "ENTREPRISE"
    const val PERSONNE_PHYSIQUE = "PERSONNE_PHYSIQUE"

    fun getLabel(cat: String): String = when (cat) {
        ENTREPRISE -> "Entreprise (Société)"
        PERSONNE_PHYSIQUE -> "Personne physique"
        else -> cat
    }

    fun getSymbol(cat: String): String = when (cat) {
        ENTREPRISE -> "🏢"
        PERSONNE_PHYSIQUE -> "👤"
        else -> "👤"
    }
}

object ReminderType {
    const val DECLARATION_MENSUELLE = "DECLARATION_MENSUELLE"
    const val CNSS = "CNSS"
    const val ACOMPTES_PROVISIONNELS = "ACOMPTES_PROVISIONNELS"
    const val PACK_COMPLET = "PACK_COMPLET"
    const val AUTRE = "AUTRE"

    fun getLabel(type: String): String = when (type) {
        DECLARATION_MENSUELLE -> "DM - Déclaration mensuelle"
        CNSS -> "Cotisations CNSS"
        ACOMPTES_PROVISIONNELS -> "AP - Acompte provisionnel"
        PACK_COMPLET -> "Pack Complet (Toutes)"
        AUTRE -> "Autre échéance"
        else -> type
    }

    fun getShortLabel(type: String): String = when (type) {
        DECLARATION_MENSUELLE -> "📄 DM"
        CNSS -> "🛡️ CNSS"
        ACOMPTES_PROVISIONNELS -> "💰 AP"
        else -> "📌 Autre"
    }
}

@Entity(tableName = "ClientReminders")
data class ClientReminderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val clientName: String,
    val category: String = ClientCategory.PERSONNE_PHYSIQUE, // ENTREPRISE ou PERSONNE_PHYSIQUE
    val telephone: String,
    val email: String? = null,
    val taxId: String? = null, // Matricule fiscal
    val reminderType: String = ReminderType.DECLARATION_MENSUELLE,
    val dueDate: String, // Format AAAA-MM-JJ (date limite légale)
    val sendDate: String, // Format AAAA-MM-JJ (date programmée pour envoyer le SMS)
    val sendTime: String = "09:00", // Format HH:MM
    val texteSms: String,
    val smsEnvoye: Boolean = false,
    val sentTimestamp: Long? = null,
    val language: String = "fr", // "fr", "ar", "en"
    val firebaseKey: String? = null,
    val emailEnvoye: Boolean = false,
    val synced: Boolean = false
)
