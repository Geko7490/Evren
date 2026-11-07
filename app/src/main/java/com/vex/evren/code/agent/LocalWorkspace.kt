package com.vex.evren.code.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Scoped Storage Access Framework workspace. All paths are resolved from the tree selected
 * by the user; no filesystem-wide permission or raw external storage access is used.
 */
class LocalWorkspace(private val context: Context, val treeUri: Uri) {
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IOException("Seçilen klasör açılamadı.")

    init {
        if (!root.isDirectory) throw IOException("Seçilen URI bir klasör değil.")
        if (!context.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isReadPermission && it.isWritePermission
            }) throw SecurityException("Klasör erişimi iptal edildi. Klasörü yeniden seçin.")
    }

    fun list(prefix: String = ""): String {
        val dir = resolve(prefix) ?: throw IOException("Klasör bulunamadı: $prefix")
        if (!dir.isDirectory) throw IOException("Bu yol bir klasör değil: $prefix")
        val output = mutableListOf<String>()
        fun walk(folder: DocumentFile, base: String, depth: Int) {
            if (depth > 25 || output.size >= 2500) return
            for (child in folder.listFiles()) {
                val name = child.name ?: continue
                val path = if (base.isEmpty()) name else "$base/$name"
                output += (if (child.isDirectory) "dir: " else "file: ") + path
                if (child.isDirectory) walk(child, path, depth + 1)
                if (output.size >= 2500) break
            }
        }
        walk(dir, prefix.trim('/'), 0)
        return output.joinToString("\n").take(80_000) +
            if (output.size >= 2500) "\n[Listing capped. Inspect a narrower directory.]" else ""
    }

    fun read(path: String, startLine: Int = 1, maxLines: Int = 400): String {
        val target = resolve(path) ?: throw IOException("Dosya bulunamadı: $path")
        if (!target.isFile) throw IOException("Bir dosya seçilmelidir.")
        if (target.length() > 2_000_000) throw IOException("Dosya 2 MB sınırını aşıyor; daha küçük dosyalar kullanın.")
        val data = context.contentResolver.openInputStream(target.uri)?.use { it.readBytes() }
            ?: throw IOException("Dosya okunamadı.")
        if (data.any { it == 0.toByte() }) throw IOException("Binary dosya metin olarak okunamaz.")
        val lines = data.toString(Charsets.UTF_8).lines()
        val offset = (startLine - 1).coerceAtLeast(0)
        return lines.drop(offset).take(maxLines.coerceIn(1, 1500))
            .mapIndexed { index, value -> "${offset + index + 1}: $value" }.joinToString("\n")
    }

    fun write(path: String, content: String): String {
        require(content.length <= 500_000) { "Tek seferlik dosya yazımı 500.000 karakteri aşamaz." }
        val segments = segments(path)
        require(segments.isNotEmpty()) { "Geçerli dosya adı gerekli." }
        val parent = ensureDirectory(segments.dropLast(1))
        val name = segments.last()
        val existing = parent.findFile(name)
        if (existing != null && !existing.isFile) throw IOException("Bu adla bir klasör mevcut.")
        if (existing != null) backup(path, existing)
        val target = existing ?: parent.createFile("application/octet-stream", name)
            ?: throw IOException("Dosya oluşturulamadı: $path")
        context.contentResolver.openOutputStream(target.uri, "rwt")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
            it.flush()
        } ?: throw IOException("Dosya yazılamadı: $path")
        return "Yerel dosya kaydedildi: $path"
    }

    fun mkdir(path: String): String {
        ensureDirectory(segments(path))
        return "Klasör oluşturuldu: $path"
    }

    fun delete(path: String): String {
        val target = resolve(path) ?: throw IOException("Dosya bulunamadı: $path")
        if (!target.isFile) throw IOException("Güvenlik için klasörler topluca silinemez.")
        backup(path, target)
        if (!target.delete()) throw IOException("Dosya silinemedi.")
        return "Dosya silindi (yedek saklandı): $path"
    }

    private fun backup(path: String, source: DocumentFile) {
        if (source.length() > 2_000_000) throw IOException("Büyük dosya için güvenli yedek oluşturulamadı.")
        val directory = File(context.filesDir, "local_workspace_backups").apply { mkdirs() }
        val backup = File(directory, UUID.randomUUID().toString() + ".bak")
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            backup.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Yedek alınamadı, yazma iptal edildi.")
        File(directory, backup.name + ".path").writeText(path, Charsets.UTF_8)
    }

    private fun segments(path: String): List<String> {
        if (path.isBlank()) return emptyList()
        require(!path.startsWith('/') && !path.contains('\\')) { "Geçersiz yol." }
        return path.split('/').also { parts ->
            require(parts.all { it.isNotBlank() && it != "." && it != ".." && !it.contains(':') }) {
                "Klasör dışına çıkmaya izin verilmez."
            }
        }
    }

    private fun resolve(path: String): DocumentFile? {
        var current: DocumentFile = root
        for (segment in segments(path)) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun ensureDirectory(path: List<String>): DocumentFile {
        var current = root
        for (part in path) {
            val child = current.findFile(part)
            current = if (child == null) current.createDirectory(part)
                ?: throw IOException("Klasör oluşturulamadı: $part")
            else if (child.isDirectory) child
            else throw IOException("Bir dosya klasör olarak kullanılamaz: $part")
        }
        return current
    }
}
