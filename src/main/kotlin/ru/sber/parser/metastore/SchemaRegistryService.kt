package ru.sber.parser.metastore

import org.slf4j.LoggerFactory
import ru.sber.parser.model.BpmMessage
import java.time.Instant

class SchemaRegistryService(
    private val repository: SchemaMetastoreRepository,
    private val schemaExtractor: SchemaExtractor = SchemaExtractor()
) {
    private val logger = LoggerFactory.getLogger(SchemaRegistryService::class.java)

    fun registerMessageSchema(rawMessage: String, message: BpmMessage, source: String, receivedAt: Instant = Instant.now()) {
        val canonicalSchemaJson = schemaExtractor.extractCanonicalSchemaJson(rawMessage)
        val schemaHash = SchemaHasher.sha256(canonicalSchemaJson)
        val messageId = message.id.takeIf { it.isNotBlank() }
        val messageFingerprint = if (messageId == null) {
            SchemaHasher.sha256("${rawMessage.trim()}|$source")
        } else {
            null
        }

        val registration = repository.upsertSchema(
            canonicalSchemaJson = canonicalSchemaJson,
            schemaHash = schemaHash,
            observedAt = receivedAt
        )
        val bindingType = if (messageId != null) BindingType.MESSAGE_ID else BindingType.FINGERPRINT
        repository.insertBinding(
            schemaId = registration.schemaId,
            bindingType = bindingType,
            messageId = messageId,
            messageFingerprint = messageFingerprint,
            source = source,
            receivedAt = receivedAt
        )

        logger.info(
            "Schema registered: schemaId={}, schemaVersion={}, isNewSchemaVersion={}, bindingType={}, source={}",
            registration.schemaId,
            registration.schemaVersion,
            registration.isNewSchemaVersion,
            bindingType,
            source
        )
    }
}
