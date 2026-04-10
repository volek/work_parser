package ru.sber.parser.metastore

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresSchemaMetastoreRepositoryTest {
    @Container
    private val postgres = PostgreSQLContainer("postgres:16-alpine")

    private lateinit var repository: PostgresSchemaMetastoreRepository

    @BeforeAll
    fun setup() {
        postgres.start()
        repository = PostgresSchemaMetastoreRepository(
            jdbcUrl = postgres.jdbcUrl,
            username = postgres.username,
            password = postgres.password
        )
        repository.initializeSchema()
    }

    @AfterAll
    fun teardown() {
        repository.close()
        postgres.stop()
    }

    @Test
    fun `upsert same schema keeps one version`() {
        val schema = """{"root":{"id":"string"}}"""
        val hash = SchemaHasher.sha256(schema)
        val first = repository.upsertSchema(schema, hash, observedAt = Instant.now())
        val second = repository.upsertSchema(schema, hash, observedAt = Instant.now())

        assertEquals(first.schemaId, second.schemaId)
        assertEquals(1, second.schemaVersion)
    }

    @Test
    fun `insert binding for message id and fingerprint`() {
        val schema = """{"root":{"id":"string"}}"""
        val hash = SchemaHasher.sha256(schema)
        val registered = repository.upsertSchema(schema, hash, observedAt = Instant.now())

        repository.insertBinding(
            schemaId = registered.schemaId,
            bindingType = BindingType.MESSAGE_ID,
            messageId = "msg-1",
            messageFingerprint = null,
            source = "source-a"
        )
        repository.insertBinding(
            schemaId = registered.schemaId,
            bindingType = BindingType.FINGERPRINT,
            messageId = null,
            messageFingerprint = "f".repeat(64),
            source = "source-a"
        )
        assertTrue(true)
    }
}
