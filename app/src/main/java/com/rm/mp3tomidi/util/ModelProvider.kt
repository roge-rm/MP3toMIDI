package com.rm.mp3tomidi.util

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A downloadable model file, identified by its expected checksum. */
data class ModelSpec(
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
)

/**
 * Downloads large model files into app-private storage on first use and verifies them by
 * checksum, so they don't have to be bundled in the APK (see tools/demucs_export/README.md
 * for why -- a single 6-stem Demucs export is ~235MB). Once downloaded, everything after this
 * runs fully offline.
 */
object ModelProvider {

    suspend fun ensureAvailable(
        context: Context,
        spec: ModelSpec,
        onProgress: suspend (fraction: Float) -> Unit,
    ): File = ensureAvailable(File(context.filesDir, "models"), spec, onProgress)

    /** Core logic, factored out from the [Context]-based overload so it's unit-testable on the JVM. */
    suspend fun ensureAvailable(
        modelsDir: File,
        spec: ModelSpec,
        onProgress: suspend (fraction: Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        val destination = File(modelsDir, spec.fileName)
        if (destination.exists() && sha256Of(destination) == spec.sha256) {
            return@withContext destination
        }

        val tempFile = File(modelsDir, "${spec.fileName}.download")
        download(spec.downloadUrl, tempFile, onProgress)

        val actualHash = sha256Of(tempFile)
        check(actualHash == spec.sha256) {
            tempFile.delete()
            "Downloaded model checksum mismatch for ${spec.fileName}: expected ${spec.sha256}, got $actualHash"
        }

        destination.delete()
        check(tempFile.renameTo(destination)) { "Failed to finalize downloaded model ${spec.fileName}" }
        destination
    }

    private suspend fun download(url: String, destination: File, onProgress: suspend (Float) -> Unit) {
        var currentUrl = url
        var redirects = 0
        var connection: HttpURLConnection

        while (true) {
            connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.connect()

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: error("Redirect from $currentUrl had no Location header")
                connection.disconnect()
                redirects++
                check(redirects <= MAX_REDIRECTS) { "Too many redirects downloading $url" }
                currentUrl = location
                continue
            }
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code downloading $url" }
            break
        }

        val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
        var lastReportedPercent = -1

        connection.inputStream.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var totalRead = 0L
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    if (totalBytes != null) {
                        val percent = floor(100f * totalRead / totalBytes).toInt()
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            onProgress(totalRead.toFloat() / totalBytes)
                        }
                    }
                }
            }
        }
        connection.disconnect()
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead < 0) break
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val BUFFER_SIZE = 1 shl 16
    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
}
