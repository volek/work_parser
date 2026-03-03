package ru.sber.parser.integration

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIf
import ru.sber.parser.config.FieldClassification
import ru.sber.parser.model.BpmMessage
import ru.sber.parser.parser.strategy.CombinedStrategy
import ru.sber.parser.parser.strategy.EavStrategy
import ru.sber.parser.parser.strategy.HybridStrategy
import java.time.OffsetDateTime
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("ru.sber.parser.integration.IntegrationTestBase#isDruidAvailable")
class DruidIntegrationTest : IntegrationTestBase() {
    
    private val testDataSourcePrefix = "test_integration_"
    
    @AfterAll
    fun cleanup() = runBlocking {
        if (isDruidAvailable()) {
            try {
                val dataSources = druidClient.listDataSources()
                dataSources.filter { it.startsWith(testDataSourcePrefix) }
                    .forEach { ds ->
                        druidClient.deleteDataSource(ds)
                    }
            } catch (e: Exception) {
                println("Cleanup warning: ${e.message}")
            }
        }
    }
    
    @Test
    @Order(1)
    fun `should connect to Druid broker`() = runBlocking {
        val result = druidClient.query("SELECT 1 as test")
        assertNotNull(result)
    }
    
    @Test
    @Order(2)
    fun `should list data sources`() = runBlocking {
        val dataSources = druidClient.listDataSources()
        assertNotNull(dataSources)
    }
    
    @Test
    @Order(3)
    fun `should ingest hybrid strategy records`() = runBlocking {
        val strategy = HybridStrategy()
        val testDataSource = "${testDataSourcePrefix}hybrid_${UUID.randomUUID().toString().take(8)}"
        
        val message = createTestMessage("hybrid-test-1")
        val records = strategy.transform(message).map { record ->
            record.toMutableMap().apply {
                remove("_datasource")
            }
        }
        
        assertDoesNotThrow {
            runBlocking {
                druidClient.ingest(testDataSource, records)
            }
        }
    }
    
    @Test
    @Order(4)
    fun `should ingest EAV strategy records`() = runBlocking {
        val strategy = EavStrategy()
        val testDataSourceEvents = "${testDataSourcePrefix}eav_events_${UUID.randomUUID().toString().take(8)}"
        val testDataSourceVars = "${testDataSourcePrefix}eav_vars_${UUID.randomUUID().toString().take(8)}"
        
        val message = createTestMessage("eav-test-1", mapOf(
            "epkId" to "123",
            "fio" to "Test FIO"
        ))
        
        val grouped = strategy.transformBatch(listOf(message))
        
        grouped.forEach { (ds, records) ->
            val cleanRecords = records.map { r ->
                r.toMutableMap().apply { remove("_datasource") }
            }
            val targetDs = when (ds) {
                "process_events" -> testDataSourceEvents
                "process_variables" -> testDataSourceVars
                else -> ds
            }
            
            assertDoesNotThrow {
                runBlocking {
                    druidClient.ingest(targetDs, cleanRecords)
                }
            }
        }
    }
    
    @Test
    @Order(5)
    fun `should ingest combined strategy records`() = runBlocking {
        val strategy = CombinedStrategy(FieldClassification.default())
        val testDataSourceMain = "${testDataSourcePrefix}combined_main_${UUID.randomUUID().toString().take(8)}"
        val testDataSourceIndexed = "${testDataSourcePrefix}combined_idx_${UUID.randomUUID().toString().take(8)}"
        
        val message = createTestMessage("combined-test-1", mapOf(
            "epkId" to "456",
            "fio" to "Combined FIO",
            "epkData" to mapOf(
                "epkEntity" to mapOf(
                    "ucpId" to "UCP789"
                )
            )
        ))
        
        val grouped = strategy.transformBatch(listOf(message))
        
        grouped.forEach { (ds, records) ->
            val cleanRecords = records.map { r ->
                r.toMutableMap().apply { remove("_datasource") }
            }
            val targetDs = when (ds) {
                "process_main" -> testDataSourceMain
                "process_variables_indexed" -> testDataSourceIndexed
                else -> ds
            }
            
            assertDoesNotThrow {
                runBlocking {
                    druidClient.ingest(targetDs, cleanRecords)
                }
            }
        }
    }
    
    @Test
    @Order(6)
    fun `should execute SQL query`() = runBlocking {
        val result = druidClient.query("SELECT COUNT(*) as cnt FROM INFORMATION_SCHEMA.TABLES")
        
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }
    
    @Test
    @Order(7)
    fun `should handle batch ingestion`() = runBlocking {
        val strategy = HybridStrategy()
        val testDataSource = "${testDataSourcePrefix}batch_${UUID.randomUUID().toString().take(8)}"
        
        val messages = (1..10).map { i ->
            createTestMessage("batch-test-$i")
        }
        
        val allRecords = messages.flatMap { msg ->
            strategy.transform(msg).map { r ->
                r.toMutableMap().apply { remove("_datasource") }
            }
        }
        
        assertDoesNotThrow {
            runBlocking {
                druidClient.ingest(testDataSource, allRecords)
            }
        }
    }
    
    private fun createTestMessage(
        id: String,
        variables: Map<String, Any?> = mapOf(
            "epkId" to "1234567890",
            "fio" to "ТЕСТОВЫЙ ПОЛЬЗОВАТЕЛЬ",
            "caseId" to "case-$id"
        )
    ): BpmMessage {
        return BpmMessage(
            id = id,
            processName = "IntegrationTestProcess",
            startDate = OffsetDateTime.now(),
            state = 1,
            variables = variables
        )
    }
}
