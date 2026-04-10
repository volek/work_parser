package ru.sber.parser.metastore

import java.io.Closeable
import java.time.Instant

enum class BindingType {
    MESSAGE_ID,
    FINGERPRINT
}

data class SchemaRegistrationResult(
    val schemaId: Long,
    val schemaVersion: Int,
    val isNewSchemaVersion: Boolean
)

interface SchemaMetastoreRepository : Closeable {
    fun initializeSchema()

    fun upsertSchema(
        canonicalSchemaJson: String,
        schemaHash: String,
        hashAlgo: String = "sha256",
        observedAt: Instant = Instant.now()
    ): SchemaRegistrationResult

    fun insertBinding(
        schemaId: Long,
        bindingType: BindingType,
        messageId: String?,
        messageFingerprint: String?,
        source: String,
        receivedAt: Instant = Instant.now()
    )
}
