package com.bigdotdev.aospbugreportanalyzer.bugreport

import com.bigdotdev.aospbugreportanalyzer.storage.HistoryLogger
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class BugreportReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

object BugreportFileReader {
    private val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte())

    @Throws(BugreportReadException::class)
    fun readTextRobust(path: Path): String {
        if (!Files.exists(path)) {
            throw BugreportReadException("Файл не найден: $path")
        }

        return try {
            if (isZip(path)) {
                readFromZip(path)
            } else {
                readTextFile(path)
            }
        } catch (e: BugreportReadException) {
            throw e
        } catch (t: Throwable) {
            throw BugreportReadException("Ошибка чтения файла $path: ${t.message}", t)
        }
    }

    private fun isZip(path: Path): Boolean {
        return Files.newInputStream(path).use { input ->
            val header = ByteArray(4)
            val read = input.read(header)
            if (read < 2) return false
            header[0] == ZIP_MAGIC[0] && header[1] == ZIP_MAGIC[1]
        }
    }

    private fun readFromZip(path: Path): String {
        HistoryLogger.log("Detected zip bugreport: $path")
        ZipFile(path.toFile()).use { zipFile ->
            val entries = zipFile.entries().asSequence().filterNot { it.isDirectory }.toList()
            val textEntries = entries.filter { it.name.lowercase(Locale.getDefault()).endsWith(".txt") }
            val candidate = selectBestEntry(textEntries) ?: throw BugreportReadException(
                "Не найден текстовый файл bugreport внутри zip: $path"
            )

            HistoryLogger.log("Reading bugreport zip entry: ${candidate.name}")
            val bytes = zipFile.getInputStream(candidate).use(InputStream::readBytes)
            return decodeBytes(bytes, "zip entry ${candidate.name} in $path")
        }
    }

    private fun selectBestEntry(entries: List<ZipEntry>): ZipEntry? {
        return entries.minByOrNull { entry ->
            val lower = entry.name.lowercase(Locale.getDefault())
            when {
                "bugreport" in lower -> 0
                "dumpstate" in lower -> 1
                else -> 2
            }
        } ?: entries.firstOrNull()
    }

    private fun readTextFile(path: Path): String {
        val bytes = Files.readAllBytes(path)
        return decodeBytes(bytes, "file $path")
    }

    private fun decodeBytes(bytes: ByteArray, sourceLabel: String): String {
        return try {
            decodeStrict(bytes, StandardCharsets.UTF_8)
        } catch (e: MalformedInputException) {
            HistoryLogger.log("Bugreport read failed as UTF-8 for $sourceLabel: ${e.message}. Fallback to UTF-8 with REPLACE")
            try {
                decodeWithReplace(bytes, StandardCharsets.UTF_8)
            } catch (e2: Throwable) {
                HistoryLogger.log("Fallback UTF-8 decode failed for $sourceLabel: ${e2.message}. Trying ISO-8859-1")
                decodeWithReplace(bytes, StandardCharsets.ISO_8859_1)
            }
        }
    }

    private fun decodeStrict(bytes: ByteArray, charset: java.nio.charset.Charset): String {
        val decoder = charset.newDecoder()
        decoder.onMalformedInput(CodingErrorAction.REPORT)
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun decodeWithReplace(bytes: ByteArray, charset: java.nio.charset.Charset): String {
        val decoder = charset.newDecoder()
        decoder.onMalformedInput(CodingErrorAction.REPLACE)
        decoder.onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }
}
