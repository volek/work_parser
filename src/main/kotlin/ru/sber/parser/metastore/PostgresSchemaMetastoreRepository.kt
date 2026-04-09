package ru.sber.parser.metastore

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import javax.sql.DataSource

class PostgresSchemaMetastoreRepository(
    jdbcUrl: String,
    username: String,
    password: String,
    maxPoolSize: Int = 5,
    connectionTimeoutMs: Long = 10_000
) : SchemaMetastoreRepository {
    private val logger = LoggerFactory.getLogger(PostgresSchemaMetastoreRepository::class.java)
    private val dataSource: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            this.maximumPoolSize = maxPoolSize
            this.connectionTimeout = connectionTimeoutMs
            this.driverClassName = "org.postgresql.Driver"
            this.isAutoCommit = true
        }
    )

    override fun initializeSchema() {
        val ddl = javaClass.classLoader
            .getResource("sql/metastore_schema.sql")
            ?.readText()
            ?: error("DDL resource sql/metastore_schema.sql not found")
        executeSqlScript(dataSource, ddl)
    }

    override fun upsertSchema(
        canonicalSchemaJson: String,
        schemaHash: String,
        hashAlgo: String,
        observedAt: Instant
    ): SchemaRegistrationResult {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val base = upsertSchemaVersion(
                    connection = connection,
                    canonicalSchemaJson = canonicalSchemaJson,
                    schemaHash = schemaHash,
                    hashAlgo = hashAlgo,
                    schemaVersion = 1,
                    observedAt = observedAt
                )
                val matches = isCanonicalSchemaEqual(connection, base.schemaId, canonicalSchemaJson)
                if (matches) {
                    connection.commit()
                    return base
                }

                logger.warn(
                    "Schema hash collision detected for hash={}, creating next version",
                    schemaHash
                )
                val nextVersion = findNextSchemaVersion(connection, schemaHash, hashAlgo)
                val next = insertSchemaVersion(
                    connection = connection,
                    canonicalSchemaJson = canonicalSchemaJson,
                    schemaHash = schemaHash,
                    hashAlgo = hashAlgo,
                    schemaVersion = nextVersion,
                    observedAt = observedAt
                )
                connection.commit()
                return next
            } catch (ex: Exception) {
                connection.rollback()
                throw ex
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun insertBinding(
        schemaId: Long,
        bindingType: BindingType,
        messageId: String?,
        messageFingerprint: String?,
        source: String,
        receivedAt: Instant
    ) {
        val sql = """
            INSERT INTO message_schema_binding(
                schema_id, binding_type, message_id, message_fingerprint, source, received_at
            )
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
        """.trimIndent()

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { st ->
                st.setLong(1, schemaId)
                st.setString(2, bindingType.toStorageValue())
                st.setString(3, messageId)
                st.setString(4, messageFingerprint)
                st.setString(5, source)
                st.setObject(6, receivedAt)
                st.executeUpdate()
            }
        }
    }

    override fun close() {
        dataSource.close()
    }

    private fun upsertSchemaVersion(
        connection: Connection,
        canonicalSchemaJson: String,
        schemaHash: String,
        hashAlgo: String,
        schemaVersion: Int,
        observedAt: Instant
    ): SchemaRegistrationResult {
        val sql = """
            INSERT INTO message_schema(
                schema_hash, hash_algo, canonical_schema, schema_version, first_seen_at, last_seen_at, seen_count
            )
            VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?, 1)
            ON CONFLICT (schema_hash, hash_algo, schema_version)
            DO UPDATE SET
                last_seen_at = EXCLUDED.last_seen_at,
                seen_count = message_schema.seen_count + 1
            RETURNING id
        """.trimIndent()

        connection.prepareStatement(sql).use { st ->
            st.setString(1, schemaHash)
            st.setString(2, hashAlgo)
            st.setString(3, canonicalSchemaJson)
            st.setInt(4, schemaVersion)
            st.setObject(5, observedAt)
            st.setObject(6, observedAt)
            st.executeQuery().use { rs ->
                check(rs.next()) { "Failed to upsert schema" }
                return SchemaRegistrationResult(
                    schemaId = rs.getLong("id"),
                    schemaVersion = schemaVersion,
                    isNewSchemaVersion = false
                )
            }
        }
    }

    private fun insertSchemaVersion(
        connection: Connection,
        canonicalSchemaJson: String,
        schemaHash: String,
        hashAlgo: String,
        schemaVersion: Int,
        observedAt: Instant
    ): SchemaRegistrationResult {
        val sql = """
            INSERT INTO message_schema(
                schema_hash, hash_algo, canonical_schema, schema_version, first_seen_at, last_seen_at, seen_count
            )
            VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?, 1)
            RETURNING id
        """.trimIndent()
        connection.prepareStatement(sql).use { st ->
            st.setString(1, schemaHash)
            st.setString(2, hashAlgo)
            st.setString(3, canonicalSchemaJson)
            st.setInt(4, schemaVersion)
            st.setObject(5, observedAt)
            st.setObject(6, observedAt)
            st.executeQuery().use { rs ->
                check(rs.next()) { "Failed to insert schema version" }
                return SchemaRegistrationResult(
                    schemaId = rs.getLong("id"),
                    schemaVersion = schemaVersion,
                    isNewSchemaVersion = true
                )
            }
        }
    }

    private fun isCanonicalSchemaEqual(connection: Connection, schemaId: Long, canonicalSchemaJson: String): Boolean {
        val sql = "SELECT canonical_schema = CAST(? AS jsonb) AS matches FROM message_schema WHERE id = ?"
        connection.prepareStatement(sql).use { st ->
            st.setString(1, canonicalSchemaJson)
            st.setLong(2, schemaId)
            st.executeQuery().use { rs ->
                check(rs.next()) { "Schema row not found by id=$schemaId" }
                return rs.getBoolean("matches")
            }
        }
    }

    private fun findNextSchemaVersion(connection: Connection, schemaHash: String, hashAlgo: String): Int {
        val sql = "SELECT COALESCE(MAX(schema_version), 0) + 1 AS next_version FROM message_schema WHERE schema_hash = ? AND hash_algo = ?"
        connection.prepareStatement(sql).use { st ->
            st.setString(1, schemaHash)
            st.setString(2, hashAlgo)
            st.executeQuery().use { rs ->
                check(rs.next()) { "Failed to get next schema version" }
                return rs.getInt("next_version")
            }
        }
    }

    private fun executeSqlScript(dataSource: DataSource, ddl: String) {
        val statements = ddl
            .split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        dataSource.connection.use { connection ->
            statements.forEach { statement ->
                connection.prepareStatement(statement).use(PreparedStatement::execute)
            }
        }
    }

    private fun BindingType.toStorageValue(): String = when (this) {
        BindingType.MESSAGE_ID -> "message_id"
        BindingType.FINGERPRINT -> "fingerprint"
    }
}
