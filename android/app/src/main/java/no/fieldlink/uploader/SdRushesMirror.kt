package no.fieldlink.uploader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.security.MessageDigest
import java.util.Calendar

/**
 * Independent SD copy of the original DJI cache media. The operator explicitly
 * grants read/write access to the SD root through ACTION_OPEN_DOCUMENT_TREE.
 * A failed/incomplete copy is never recorded as complete or used for upload.
 */
internal class SdRushesMirror(private val context: Context) {
    private val prefs = context.getSharedPreferences("fieldlink", Context.MODE_PRIVATE)

    internal companion object {
        fun safeProjectFolder(year: Int, name: String): String {
            val clean = name.trim()
                .replace(Regex("[\\\\/\\u0000-\\u001f:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim(' ', '.')
                .take(75)
                .ifBlank { "Project" }
            return "${year}_${clean}"
        }
    }

    private fun digestKey(uri: Uri, project: String): String {
        val data = "${project}|${uri}".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun shaOf(document: DocumentFile): String? = try {
        val hash = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(document.uri)?.use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val bytes = input.read(buffer)
                if (bytes < 0) break
                if (bytes > 0) hash.update(buffer, 0, bytes)
            }
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    } catch (_: Exception) { null }

    /** Returns true only after the destination SHA-256 equals the original. */
    fun copy(file: DocumentFile, project: String, projectName: String,
             mayContinue: () -> Boolean): Boolean {
        if (!prefs.getBoolean("sd_copy_enabled", false)) return false
        val uriText = prefs.getString("sd_tree_uri", null) ?: return false
        val treeUri = Uri.parse(uriText)
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
        if (!granted) return false
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        if (!root.isDirectory || !root.canWrite()) return false
        val fileName = file.name ?: return false
        if (!file.isFile || !fileName.endsWith(".mp4", true) || file.length() <= 0) return false
        val size = file.length()
        val modified = file.lastModified()
        val key = digestKey(file.uri, project)
        val yearKey = "sd_copy_year_${project}"
        val year = prefs.getInt(yearKey, 0).takeIf { it in 2000..2100 }
            ?: Calendar.getInstance().get(Calendar.YEAR).also {
                prefs.edit().putInt(yearKey, it).commit()
            }
        val projectFolder = safeProjectFolder(year, projectName)
        // Android 11+ may block choosing the SD volume root. In that case
        // select/create the offset folder itself in the Android picker.
        val offsetDir = if(root.name.equals("offset", ignoreCase=true)) root
            else root.findFile("offset") ?: root.createDirectory("offset")
                ?: throw IOException("Cannot create offset folder on SD card")
        val destination = offsetDir.findFile(projectFolder) ?: offsetDir.createDirectory(projectFolder)
            ?: throw IOException("Cannot create project folder on SD card")
        if (!destination.isDirectory || !destination.canWrite()) throw IOException("SD card is not writable")
        val journal = prefs.getString("sd_copied_${key}", "") ?: ""
        if (journal == "${size}|${modified}|${projectFolder}|${uriText}") {
            val existing = destination.findFile(fileName)
            if (existing?.isFile == true && existing.length() == size) return true
        }
        if (!mayContinue()) return false

        // A previously existing different file must never be overwritten.
        var targetName = fileName
        var target = destination.findFile(targetName)
        if (target != null) {
            val originalHash = shaOf(file) ?: return false
            if (target.isFile && target.length() == size && shaOf(target) == originalHash) {
                prefs.edit().putString("sd_copied_${key}", "${size}|${modified}|${projectFolder}").commit()
                return true
            }
            val suffix = key.take(10)
            targetName = fileName.substringBeforeLast('.') + "_${suffix}.mp4"
            target = destination.findFile(targetName)
            if (target != null) {
                if (target.isFile && target.length() == size && shaOf(target) == originalHash) return true
                throw IOException("A different SD file already uses this name")
            }
        }
        val created = destination.createFile("video/mp4", targetName)
            ?: throw IOException("Cannot create SD file")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val source = context.contentResolver.openInputStream(file.uri)
                ?: throw IOException("Cannot read DJI clip")
            source.use { input ->
                val output = context.contentResolver.openOutputStream(created.uri, "w")
                    ?: throw IOException("Cannot write SD file")
                output.use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (!mayContinue()) throw IOException("SD copy paused")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            out.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                    }
                    out.flush()
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (created.length() != size || shaOf(created) != hash) {
                throw IOException("SD copy verification failed")
            }
            prefs.edit().putString("sd_copied_${key}", "${size}|${modified}|${projectFolder}").commit()
            return true
        } catch (e: Exception) {
            created.delete() // Delete only the new incomplete copy, never the DJI original.
            throw e
        }
    }
}
