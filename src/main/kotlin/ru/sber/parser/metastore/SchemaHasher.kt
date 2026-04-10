package ru.sber.parser.metastore

import java.security.MessageDigest

object SchemaHasher {
    fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
