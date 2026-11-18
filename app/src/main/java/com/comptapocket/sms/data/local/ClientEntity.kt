package com.comptapocket.sms.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Clients")
data class ClientEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val clientName: String,
    val category: String = ClientCategory.PERSONNE_PHYSIQUE, // ENTREPRISE ou PERSONNE_PHYSIQUE
    val telephone: String,
    val email: String? = null,
    val taxId: String? = null, // Matricule fiscal
    val address: String? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
