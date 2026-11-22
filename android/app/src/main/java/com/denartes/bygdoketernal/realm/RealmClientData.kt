package com.denartes.bygdoketernal.realm

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object RealmClientData {
    private const val DATA_URL = "https://github.com/wowgaming/client-data/releases/download/v20.0/Data.zip"
    private const val DATA_SHA256 = "a3d4df635ae6c2c8f08052c32a79e0f806955150ad36b014a823dd08a32a4610"
    private val vmapMagic = "VMAP_4.8".toByteArray(Charsets.US_ASCII)
    private val mmapMagicAndVersion = byteArrayOf(0x50, 0x41, 0x4d, 0x4d, 0x07, 0, 0, 0, 20, 0, 0, 0)

    fun isPresent(clientDataDir: File): Boolean {
        if (!listOf("dbc", "maps", "vmaps", "mmaps").all {
                File(clientDataDir, it).listFiles()?.isNotEmpty() == true
            }) return false
        val tree = File(clientDataDir, "vmaps/000.vmtree")
        val tile = File(clientDataDir, "mmaps").listFiles { file -> file.name.endsWith(".mmtile") }
            ?.firstOrNull() ?: return false
        return hasHeader(tree, vmapMagic) && hasHeader(tile, mmapMagicAndVersion)
    }

    private fun hasHeader(file: File, expected: ByteArray): Boolean {
        if (!file.isFile || file.length() < expected.size) return false
        return file.inputStream().use { input ->
            val header = ByteArray(expected.size)
            var count = 0
            while (count < header.size) {
                val read = input.read(header, count, header.size - count)
                if (read < 0) return@use false
                count += read
            }
            header.contentEquals(expected)
        }
    }

    fun download(clientDataDir: File, onProgress: (String) -> Unit) {
        recover(clientDataDir)
        if (isPresent(clientDataDir)) {
            onProgress("Compatible client data already present; skipping download")
            return
        }

        onProgress("Downloading compatible WoW client data (about 1.2 GB); existing data will be kept until replacement succeeds")
        val connection = URL(DATA_URL).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            check(connection.responseCode in 200..299) {
                "Client data download failed with HTTP ${connection.responseCode}"
            }
            connection.inputStream.buffered().use { install(clientDataDir, it, DATA_SHA256) }
        } finally {
            connection.disconnect()
        }
        onProgress("Compatible client data extracted")
    }

    internal fun install(clientDataDir: File, input: InputStream, expectedSha256: String? = null) {
        recover(clientDataDir)
        val staging = sibling(clientDataDir, ".download")
        val backup = sibling(clientDataDir, ".previous")
        remove(staging)
        check(staging.mkdirs()) { "Could not create client data staging directory: $staging" }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val checkedInput = DigestInputStream(input, digest)
            ZipInputStream(checkedInput).use { zip ->
                val root = staging.canonicalPath + File.separator
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(staging, entry.name)
                    require(target.canonicalPath.startsWith(root)) { "Invalid client data archive path: ${entry.name}" }
                    if (entry.isDirectory) {
                        check(target.isDirectory || target.mkdirs()) { "Could not create directory: $target" }
                    } else {
                        val parent = requireNotNull(target.parentFile)
                        check(parent.isDirectory || parent.mkdirs()) { "Could not create directory: $parent" }
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                val remainder = ByteArray(8192)
                while (checkedInput.read(remainder) != -1) { }
                if (expectedSha256 != null) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    check(actual == expectedSha256) { "Client data download checksum does not match the published release" }
                }
            }
            check(isPresent(staging)) { "Downloaded client data is incomplete or incompatible with this server" }
            if (clientDataDir.exists()) {
                check(clientDataDir.renameTo(backup)) { "Could not preserve existing client data at $backup" }
            }
            check(staging.renameTo(clientDataDir)) { "Could not install client data; previous data remains at $backup" }
            remove(backup)
        } finally {
            remove(staging)
        }
    }

    private fun recover(clientDataDir: File) {
        val backup = sibling(clientDataDir, ".previous")
        if (!backup.exists()) return
        if (isPresent(clientDataDir)) {
            remove(backup)
            return
        }
        if (clientDataDir.isDirectory && clientDataDir.list()?.isEmpty() == true) {
            check(clientDataDir.delete()) { "Could not remove empty client data directory: $clientDataDir" }
        }
        check(!clientDataDir.exists()) {
            "Interrupted client data replacement needs attention; existing data is preserved at $backup"
        }
        check(backup.renameTo(clientDataDir)) { "Could not restore previous client data from $backup" }
    }

    private fun sibling(dir: File, suffix: String): File =
        File(requireNotNull(dir.parentFile), dir.name + suffix)

    private fun remove(dir: File) {
        check(!dir.exists() || dir.deleteRecursively()) { "Could not remove client data temporary directory: $dir" }
    }
}
