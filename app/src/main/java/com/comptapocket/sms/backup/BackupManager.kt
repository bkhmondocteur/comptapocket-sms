package com.comptapocket.sms.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gestionnaire des sauvegardes automatiques et glissantes de ComptaPocket SMS.
 * - Choix d'un dossier personnalisé via Storage Access Framework (SAF).
 * - Sauvegarde automatique à chaque ajout, modification ou suppression de client.
 * - Conservation de 5 sauvegardes maximum : la 6ème écrase automatiquement la plus ancienne.
 */
object BackupManager {

    private const val TAG = "BackupManager"
    const val PREFS_KEY_BACKUP_FOLDER_URI = "backup_folder_uri"
    const val PREFS_KEY_BACKUP_FOLDER_NAME = "backup_folder_name"
    const val PREFS_KEY_PROMPT_SHOWN = "backup_folder_prompt_shown_v107"
    const val MAX_BACKUP_FILES = 5

    /**
     * Vérifie si un dossier SAF personnalisé a été configuré par l'utilisateur.
     */
    fun isBackupFolderConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString(PREFS_KEY_BACKUP_FOLDER_URI, null)
        if (uriStr.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(uriStr)
            val doc = DocumentFile.fromTreeUri(context, uri)
            doc != null && doc.exists() && doc.canWrite()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Indique si l'invite de choix de dossier a déjà été traitée ou affichée à l'ouverture.
     */
    fun hasPromptBeenShown(context: Context): Boolean {
        val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREFS_KEY_PROMPT_SHOWN, false)
    }

    fun markPromptAsShown(context: Context) {
        val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREFS_KEY_PROMPT_SHOWN, true).apply()
    }

    /**
     * Enregistre l'URI du dossier choisi par l'utilisateur avec permission persistante.
     */
    fun saveBackupFolderUri(context: Context, uri: Uri): String {
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            Log.w(TAG, "Permission persistante non accordée : ${e.message}")
        }

        val dir = DocumentFile.fromTreeUri(context, uri)
        val displayName = dir?.name ?: "Dossier sélectionné"

        val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREFS_KEY_BACKUP_FOLDER_URI, uri.toString())
            .putString(PREFS_KEY_BACKUP_FOLDER_NAME, displayName)
            .putBoolean(PREFS_KEY_PROMPT_SHOWN, true)
            .apply()

        return displayName
    }

    /**
     * Retourne le nom d'affichage du dossier de sauvegarde configuré.
     */
    fun getBackupFolderDisplayName(context: Context): String {
        val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString(PREFS_KEY_BACKUP_FOLDER_NAME, null)
        return if (!name.isNullOrBlank()) {
            name
        } else {
            "Stockage de l'application (Dossier automatique backups/)"
        }
    }

    /**
     * Effectue une sauvegarde glissante :
     * - Conserve au maximum 5 sauvegardes.
     * - À partir de la 6ème, la plus ancienne est supprimée / écrasée.
     * - Sauvegarde dans le dossier SAF choisi par l'utilisateur (si configuré)
     *   ET maintient également une copie de sécurité dans le stockage local de l'application.
     */
    fun performRollingBackup(context: Context, jsonContent: String): Boolean {
        var success = false
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val newFileName = "comptapocket_backup_$timeStamp.json"

        // 1. Sauvegarde dans le dossier externe choisi via Storage Access Framework
        val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
        val treeUriStr = prefs.getString(PREFS_KEY_BACKUP_FOLDER_URI, null)

        if (!treeUriStr.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(treeUriStr)
                val dir = DocumentFile.fromTreeUri(context, treeUri)
                if (dir != null && dir.exists() && dir.isDirectory) {
                    val existingBackups: List<DocumentFile> = dir.listFiles().filter { doc: DocumentFile ->
                        val name = doc.name ?: ""
                        name.startsWith("comptapocket_backup_") && name.endsWith(".json")
                    }.sortedBy { it.lastModified() }

                    // Règle des 5 sauvegardes : la 6ème écrase la plus ancienne
                    if (existingBackups.size >= MAX_BACKUP_FILES) {
                        val toDeleteCount = existingBackups.size - (MAX_BACKUP_FILES - 1)
                        for (i in 0 until toDeleteCount) {
                            try {
                                existingBackups[i].delete()
                                Log.d(TAG, "Suppression de l'ancienne sauvegarde SAF : ${existingBackups[i].name}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Erreur suppression ancienne sauvegarde SAF", e)
                            }
                        }
                    }

                    val newFile = dir.createFile("application/json", newFileName)
                    if (newFile != null) {
                        context.contentResolver.openOutputStream(newFile.uri)?.use { os ->
                            os.write(jsonContent.toByteArray(Charsets.UTF_8))
                        }
                        success = true
                        Log.i(TAG, "Sauvegarde automatique SAF créée : $newFileName dans ${dir.name}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors de la sauvegarde SAF automatique", e)
            }
        }

        // 2. Toujours maintenir également la même rotation des 5 sauvegardes dans le stockage local
        try {
            val localDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")
            if (!localDir.exists()) {
                localDir.mkdirs()
            }

            val localFiles = localDir.listFiles { file ->
                file.name.startsWith("comptapocket_backup_") && file.name.endsWith(".json")
            }?.sortedBy { it.lastModified() } ?: emptyList()

            if (localFiles.size >= MAX_BACKUP_FILES) {
                val toDeleteCount = localFiles.size - (MAX_BACKUP_FILES - 1)
                for (i in 0 until toDeleteCount) {
                    try {
                        localFiles[i].delete()
                        Log.d(TAG, "Suppression de l'ancienne sauvegarde locale : ${localFiles[i].name}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Erreur suppression ancienne sauvegarde locale", e)
                    }
                }
            }

            val localFile = File(localDir, newFileName)
            localFile.writeText(jsonContent, Charsets.UTF_8)
            success = true
            Log.i(TAG, "Sauvegarde automatique locale créée : ${localFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la sauvegarde locale automatique", e)
        }

        return success
    }
}
