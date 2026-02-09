package com.ndkarte.app

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Manages Google Drive sync for GPX files.
 *
 * Uses Google Sign-In for OAuth 2.0 authentication and the Drive REST
 * API for file upload/download. Tracks sync state in a local JSON file
 * to avoid redundant transfers.
 */
class SyncManager(private val activity: Activity) {

    private val executor = Executors.newSingleThreadExecutor()
    private var driveService: Drive? = null
    private var signInClient: GoogleSignInClient? = null
    private var syncState: SyncState? = null

    private val gpxDir: File get() = File(activity.filesDir, GPX_DIR)
    private val syncStateFile: File get() = File(activity.filesDir, SYNC_STATE_FILE)

    /** Initialize Google Sign-In client. */
    fun initialize() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()

        signInClient = GoogleSignIn.getClient(activity, gso)
        syncState = SyncState.load(syncStateFile)

        // Try silent sign-in first
        val account = GoogleSignIn.getLastSignedInAccount(activity)
        if (account != null && hasRequiredScopes(account)) {
            buildDriveService(account)
            Log.i(TAG, "Signed in silently as ${account.email}")
        }
    }

    /** Get the sign-in intent to launch the Google account chooser. */
    fun getSignInIntent(): Intent? = signInClient?.signInIntent

    /**
     * Handle the result from the Google Sign-In activity.
     * Call this from Activity.onActivityResult().
     */
    fun handleSignInResult(data: Intent?) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.result
            if (account != null) {
                buildDriveService(account)
                Log.i(TAG, "Signed in as ${account.email}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in failed", e)
        }
    }

    /** Whether the user is signed in and Drive service is available. */
    val isSignedIn: Boolean get() = driveService != null

    /**
     * Sync GPX files with Google Drive.
     *
     * Downloads remote files not present locally, uploads local files
     * not present remotely, and skips files that haven't changed.
     * Runs on a background thread.
     */
    fun sync(callback: (SyncResult) -> Unit) {
        val drive = driveService
        if (drive == null) {
            callback(SyncResult(0, 0, "Not signed in"))
            return
        }

        executor.execute {
            try {
                val result = performSync(drive)
                activity.runOnUiThread { callback(result) }
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
                activity.runOnUiThread {
                    callback(SyncResult(0, 0, e.message))
                }
            }
        }
    }

    /** Sign out and clear credentials. */
    fun signOut() {
        signInClient?.signOut()
        driveService = null
        Log.i(TAG, "Signed out")
    }

    // -- Internal --

    private fun buildDriveService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            activity,
            Collections.singleton(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("NDKarte")
            .build()
    }

    private fun hasRequiredScopes(account: GoogleSignInAccount): Boolean {
        return GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))
    }

    private fun performSync(drive: Drive): SyncResult {
        gpxDir.mkdirs()
        val state = syncState ?: SyncState()
        var uploaded = 0
        var downloaded = 0

        // Ensure app folder exists in Drive
        val folderId = getOrCreateAppFolder(drive)

        // List remote GPX files
        val remoteFiles = listRemoteGpxFiles(drive, folderId)

        // Download files that are remote-only or newer
        for (remote in remoteFiles) {
            val localFile = File(gpxDir, remote.name)
            val localEntry = state.files[remote.name]

            val needsDownload = !localFile.exists() ||
                (localEntry != null && remote.modifiedTime > localEntry.remoteModified)

            if (needsDownload) {
                downloadFile(drive, remote.id, localFile)
                state.files[remote.name] = SyncFileEntry(
                    remoteId = remote.id,
                    remoteModified = remote.modifiedTime,
                    localModified = localFile.lastModified()
                )
                downloaded++
                Log.d(TAG, "Downloaded: ${remote.name}")
            }
        }

        // Upload files that are local-only or newer
        val remoteNames = remoteFiles.map { it.name }.toSet()
        val localFiles = gpxDir.listFiles()
            ?.filter { it.extension == "gpx" } ?: emptyList()

        for (localFile in localFiles) {
            val entry = state.files[localFile.name]
            val needsUpload = localFile.name !in remoteNames ||
                (entry != null && localFile.lastModified() > entry.localModified)

            if (needsUpload) {
                val remoteId = if (localFile.name in remoteNames) {
                    val existing = remoteFiles.first { it.name == localFile.name }
                    updateFile(drive, existing.id, localFile)
                    existing.id
                } else {
                    uploadFile(drive, folderId, localFile)
                }

                state.files[localFile.name] = SyncFileEntry(
                    remoteId = remoteId,
                    remoteModified = System.currentTimeMillis(),
                    localModified = localFile.lastModified()
                )
                uploaded++
                Log.d(TAG, "Uploaded: ${localFile.name}")
            }
        }

        state.lastSync = System.currentTimeMillis()
        state.save(syncStateFile)
        syncState = state

        Log.i(TAG, "Sync complete: $uploaded uploaded, $downloaded downloaded")
        return SyncResult(uploaded, downloaded, null)
    }

    private fun getOrCreateAppFolder(drive: Drive): String {
        val query = "mimeType='application/vnd.google-apps.folder'" +
            " and name='$DRIVE_FOLDER_NAME'" +
            " and trashed=false"

        val result = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()

        if (result.files.isNotEmpty()) {
            return result.files[0].id
        }

        val folderMetadata = com.google.api.services.drive.model.File().apply {
            name = DRIVE_FOLDER_NAME
            mimeType = "application/vnd.google-apps.folder"
        }

        val folder = drive.files().create(folderMetadata)
            .setFields("id")
            .execute()

        Log.i(TAG, "Created Drive folder: $DRIVE_FOLDER_NAME")
        return folder.id
    }

    private fun listRemoteGpxFiles(drive: Drive, folderId: String): List<RemoteFile> {
        val query = "'$folderId' in parents and trashed=false and name contains '.gpx'"

        val result = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name, modifiedTime)")
            .execute()

        return result.files.map { file ->
            RemoteFile(
                id = file.id,
                name = file.name,
                modifiedTime = file.modifiedTime?.value ?: 0L
            )
        }
    }

    private fun downloadFile(drive: Drive, fileId: String, dest: File) {
        val outputStream = ByteArrayOutputStream()
        drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
        dest.writeBytes(outputStream.toByteArray())
    }

    private fun uploadFile(drive: Drive, folderId: String, localFile: File): String {
        val fileMetadata = com.google.api.services.drive.model.File().apply {
            name = localFile.name
            parents = listOf(folderId)
        }

        val content = ByteArrayContent("application/gpx+xml", localFile.readBytes())
        val uploaded = drive.files().create(fileMetadata, content)
            .setFields("id")
            .execute()

        return uploaded.id
    }

    private fun updateFile(drive: Drive, fileId: String, localFile: File) {
        val content = ByteArrayContent("application/gpx+xml", localFile.readBytes())
        drive.files().update(fileId, null, content).execute()
    }

    // -- Data classes --

    data class RemoteFile(val id: String, val name: String, val modifiedTime: Long)
    data class SyncResult(val uploaded: Int, val downloaded: Int, val error: String?)

    data class SyncFileEntry(
        val remoteId: String,
        val remoteModified: Long,
        val localModified: Long
    )

    data class SyncState(
        val files: MutableMap<String, SyncFileEntry> = mutableMapOf(),
        var lastSync: Long = 0L
    ) {
        fun save(file: File) {
            val root = JSONObject()
            root.put("lastSync", lastSync)
            val filesObj = JSONObject()
            for ((name, entry) in files) {
                filesObj.put(name, JSONObject().apply {
                    put("remoteId", entry.remoteId)
                    put("remoteModified", entry.remoteModified)
                    put("localModified", entry.localModified)
                })
            }
            root.put("files", filesObj)
            file.writeText(root.toString(2))
        }

        companion object {
            fun load(file: File): SyncState {
                if (!file.exists()) return SyncState()
                try {
                    val root = JSONObject(file.readText())
                    val state = SyncState(lastSync = root.optLong("lastSync", 0L))
                    val filesObj = root.optJSONObject("files") ?: return state
                    for (name in filesObj.keys()) {
                        val entry = filesObj.getJSONObject(name)
                        state.files[name] = SyncFileEntry(
                            remoteId = entry.getString("remoteId"),
                            remoteModified = entry.getLong("remoteModified"),
                            localModified = entry.getLong("localModified")
                        )
                    }
                    return state
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load sync state", e)
                    return SyncState()
                }
            }
        }
    }

    companion object {
        private const val TAG = "NDKarte.Sync"
        private const val GPX_DIR = "gpx"
        private const val SYNC_STATE_FILE = "sync_state.json"
        private const val DRIVE_FOLDER_NAME = "NDKarte"
    }
}
