package com.btv.data.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.btv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The "Mettre à jour" button, step by step. */
sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val percent: Int) : InstallState
    /** Android must first let bTV install apps (once per device). */
    data object NeedsPermission : InstallState
    /** Android's own "Update" screen is open: the user confirms there. */
    data object Installing : InstallState
    data class Failed(val message: String) : InstallState
}

/**
 * Downloads the APK of a GitHub Release and hands it to Android's installer.
 * A silent update is impossible outside an app store: Android always shows
 * its confirmation screen. The file is checked first - it must be bTV,
 * newer than the installed version - so a wrong download is never offered.
 */
object AppUpdater {
    private const val TAG = "BtvUpdate"
    private const val APK_MIME = "application/vnd.android.package-archive"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun update(context: Context, version: String) {
        if (_state.value is InstallState.Downloading) return
        val appContext = context.applicationContext
        if (!canInstall(appContext)) {
            _state.value = InstallState.NeedsPermission
            openInstallPermission(context)
            return
        }
        scope.launch {
            _state.value = InstallState.Downloading(0)
            try {
                val apk = download(appContext, version)
                checkPackage(appContext, apk)
                launchInstaller(context, apk)
                _state.value = InstallState.Installing
            } catch (error: Exception) {
                Log.w(TAG, "Update failed: ${error.javaClass.simpleName}: ${error.message}")
                _state.value = InstallState.Failed(
                    (error as? UpdateException)?.message ?: "Téléchargement impossible, réessaie plus tard."
                )
            }
        }
    }

    private fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    private fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            // Some TV systems have no such screen: the hint under the button explains where it is.
        }
    }

    private suspend fun download(context: Context, version: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "bTV-$version.apk")
        val request = Request.Builder()
            .url("https://github.com/${BuildConfig.GITHUB_REPO}/releases/download/v$version/bTV.apk")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            _state.value = InstallState.Downloading(percent)
                        }
                    }
                }
            }
        }
        target
    }

    private fun checkPackage(context: Context, apk: File) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archive: PackageInfo = pm.getPackageArchiveInfo(apk.path, 0)
            ?: throw UpdateException("Le fichier téléchargé est invalide.")
        if (archive.packageName != context.packageName) throw UpdateException("Le fichier téléchargé n'est pas bTV.")
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, 0)
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            throw UpdateException("Cette version est déjà installée.")
        }
    }

    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            throw UpdateException("Aucun installeur disponible sur cet appareil.")
        }
    }

    /** Back on the screen after Android's permission page: ready for another try. */
    fun reset() {
        if (_state.value !is InstallState.Downloading) _state.value = InstallState.Idle
    }

    private class UpdateException(message: String) : Exception(message)
}
