package com.crispy.tv.plugins.repo

import java.io.File
import java.security.MessageDigest

internal class PluginCodeStore(private val filesDir: File) {

    private val root = File(filesDir, "plugins").apply { mkdirs() }

    fun writeCode(repoUrl: String, scraperId: String, code: String) {
        val repoDir = File(root, repoHash(repoUrl)).apply { mkdirs() }
        val target = File(repoDir, "$scraperId.js")
        val temp = File(repoDir, "$scraperId.js.tmp")
        temp.writeText(code, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw PluginRepositoryException("Failed to persist plugin code for $scraperId")
        }
    }

    fun readCode(repoUrl: String, scraperId: String): String? {
        val target = File(File(root, repoHash(repoUrl)), "$scraperId.js")
        if (!target.isFile) return null
        return runCatching { target.readText(Charsets.UTF_8) }.getOrNull()
    }

    fun deleteRepo(repoUrl: String) {
        File(root, repoHash(repoUrl)).deleteRecursively()
    }

    private fun repoHash(repoUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(repoUrl.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString(separator = "") { "%02x".format(it) }
    }
}
