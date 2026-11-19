package com.comptapocket.sms.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClientReminderDao {

    // ==========================================
    // 1. BASE DE DONNÉES DES CLIENTS
    // ==========================================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClient(client: ClientEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllClients(clients: List<ClientEntity>): List<Long>

    @Update
    suspend fun updateClient(client: ClientEntity)

    @Query("DELETE FROM Clients WHERE id = :id")
    suspend fun deleteClient(id: Int)

    @Query("SELECT * FROM Clients ORDER BY clientName ASC")
    fun getAllClients(): Flow<List<ClientEntity>>

    @Query("SELECT * FROM Clients ORDER BY clientName ASC")
    suspend fun getAllClientsList(): List<ClientEntity>

    @Query("SELECT * FROM ClientReminders")
    suspend fun getAllRemindersList(): List<ClientReminderEntity>

    @Query("SELECT * FROM ClientReminders WHERE ((:clientName != '' AND TRIM(LOWER(clientName)) = TRIM(LOWER(:clientName))) OR (:clientName = '' AND telephone = :telephone)) AND category = :category AND reminderType = :type AND dueDate LIKE :yearMonthPattern LIMIT 1")
    suspend fun getReminderByClientTypeAndMonth(
        clientName: String,
        telephone: String,
        category: String,
        type: String,
        yearMonthPattern: String
    ): ClientReminderEntity?

    @Query("SELECT * FROM Clients WHERE TRIM(LOWER(clientName)) = TRIM(LOWER(:name)) AND category = :category LIMIT 1")
    suspend fun getClientByNameAndCategory(name: String, category: String): ClientEntity?

    @Query("SELECT * FROM Clients WHERE id = :id LIMIT 1")
    suspend fun getClientById(id: Int): ClientEntity?

    @Query("SELECT * FROM Clients WHERE telephone = :phone LIMIT 1")
    suspend fun getClientByPhone(phone: String): ClientEntity?

    @Query("SELECT * FROM Clients WHERE clientName = :name LIMIT 1")
    suspend fun getClientByName(name: String): ClientEntity?

    // Mettre à jour en cascade les coordonnées dans les rappels lorsqu'un client est modifié
    @Query("UPDATE ClientReminders SET clientName = :newName, category = :newCategory, telephone = :newPhone, email = :newEmail, taxId = :newTaxId WHERE ((:oldName != '' AND TRIM(LOWER(clientName)) = TRIM(LOWER(:oldName))) OR (:oldName = '' AND telephone = :oldPhone)) AND category = :oldCategory")
    suspend fun updateRemindersForClient(
        oldName: String,
        oldPhone: String,
        oldCategory: String,
        newName: String,
        newCategory: String,
        newPhone: String,
        newEmail: String?,
        newTaxId: String?
    )

    // Supprimer tous les rappels d'un client
    @Query("DELETE FROM ClientReminders WHERE ((:name != '' AND TRIM(LOWER(clientName)) = TRIM(LOWER(:name))) OR (:name = '' AND telephone = :phone)) AND category = :category")
    suspend fun deleteRemindersForClient(name: String, phone: String, category: String)


    // ==========================================
    // 2. RAPPELS ET ÉCHÉANCES SMS
    // ==========================================
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ClientReminderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reminders: List<ClientReminderEntity>): List<Long>

    @Update
    suspend fun updateReminder(reminder: ClientReminderEntity)

    @Update
    suspend fun updateAll(reminders: List<ClientReminderEntity>)

    @Query("SELECT * FROM ClientReminders WHERE id = :id LIMIT 1")
    suspend fun getReminderById(id: Int): ClientReminderEntity?

    @Query("SELECT * FROM ClientReminders WHERE firebaseKey = :key LIMIT 1")
    suspend fun getReminderByFirebaseKey(key: String): ClientReminderEntity?

    @Query("SELECT * FROM ClientReminders ORDER BY sendDate ASC, sendTime ASC, clientName ASC")
    fun getAllReminders(): Flow<List<ClientReminderEntity>>

    @Query("SELECT * FROM ClientReminders WHERE category = :category ORDER BY sendDate ASC, sendTime ASC, clientName ASC")
    fun getRemindersByCategory(category: String): Flow<List<ClientReminderEntity>>

    @Query("SELECT * FROM ClientReminders WHERE ((:name != '' AND TRIM(LOWER(clientName)) = TRIM(LOWER(:name))) OR (:name = '' AND telephone = :phone)) AND category = :category ORDER BY dueDate ASC")
    suspend fun getRemindersForClient(phone: String, name: String, category: String): List<ClientReminderEntity>

    @Query("SELECT * FROM ClientReminders WHERE smsEnvoye = 0 ORDER BY sendDate ASC, sendTime ASC")
    suspend fun getUnsentReminders(): List<ClientReminderEntity>

    @Query("SELECT * FROM ClientReminders WHERE sendDate <= :date AND smsEnvoye = 0 ORDER BY sendDate ASC, sendTime ASC")
    suspend fun getRemindersDueForSend(date: String): List<ClientReminderEntity>

    @Query("UPDATE ClientReminders SET smsEnvoye = 1, sentTimestamp = :timestamp WHERE id = :id")
    suspend fun markSmsAsSent(id: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE ClientReminders SET smsEnvoye = 1 WHERE id = :id AND smsEnvoye = 0")
    suspend fun claimReminderForSending(id: Int): Int

    @Query("UPDATE ClientReminders SET smsEnvoye = 0, sentTimestamp = NULL WHERE id = :id")
    suspend fun resetSmsStatus(id: Int)

    @Query("SELECT * FROM ClientReminders WHERE clientName = :clientName AND telephone = :telephone AND reminderType = :type AND smsEnvoye = 0 LIMIT 1")
    suspend fun getPendingReminderForClientAndType(clientName: String, telephone: String, type: String): ClientReminderEntity?

    @Query("DELETE FROM ClientReminders WHERE id = :id")
    suspend fun deleteReminder(id: Int)

    @Query("DELETE FROM ClientReminders")
    suspend fun deleteAll()

    // Requêtes de mise à jour collective de la date d'envoi
    @Query("UPDATE ClientReminders SET sendDate = :newSendDate, sendTime = :newSendTime WHERE smsEnvoye = 0")
    suspend fun updateSendDateForAll(newSendDate: String, newSendTime: String)

    @Query("UPDATE ClientReminders SET sendDate = :newSendDate, sendTime = :newSendTime WHERE category = :category AND smsEnvoye = 0")
    suspend fun updateSendDateByCategory(category: String, newSendDate: String, newSendTime: String)

    @Query("UPDATE ClientReminders SET sendDate = :newSendDate, sendTime = :newSendTime WHERE id IN (:ids)")
    suspend fun updateSendDateForIds(ids: List<Int>, newSendDate: String, newSendTime: String)
}
