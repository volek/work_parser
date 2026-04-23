package ru.sber.parser.integration

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIf
import ru.sber.parser.model.BpmMessage
import ru.sber.parser.parser.strategy.DefaultStrategy
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
    fun `should ingest unified default strategy records`() = runBlocking {
        val strategy = DefaultStrategy()
        val testDataSourceMain = "${testDataSourcePrefix}default_main_${UUID.randomUUID().toString().take(8)}"
        val testDataSourceArray = "${testDataSourcePrefix}default_arr_${UUID.randomUUID().toString().take(8)}"
        
        val message = createTestMessage(
            "default-test-1",
            mapOf(
                "staticData" to mapOf("caseId" to "case-1"),
                "epkData" to mapOf(
                    "epkEntity" to mapOf(
                        "names" to listOf(mapOf("surname" to "Ivanov"))
                    )
                )
            )
        )
        val grouped = strategy.transformBatch(listOf(message))
        
        grouped.forEach { (ds, records) ->
            val cleanRecords = records.map { r -> r.toMutableMap().apply { remove("_datasource") } }
            val targetDs = when (ds) {
                strategy.dataSourceName -> testDataSourceMain
                strategy.additionalDataSources.single() -> testDataSourceArray
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
    @Order(4)
    fun `should execute SQL query`() = runBlocking {
        val result = druidClient.query("SELECT COUNT(*) as cnt FROM INFORMATION_SCHEMA.TABLES")
        
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }
    
    @Test
    @Order(5)
    fun `should handle batch ingestion`() = runBlocking {
        val strategy = DefaultStrategy()
        val testDataSource = "${testDataSourcePrefix}batch_${UUID.randomUUID().toString().take(8)}"
        
        val messages = (1..10).map { i ->
            createTestMessage("batch-test-$i")
        }
        
        val allRecords = strategy.transformBatch(messages)[strategy.dataSourceName]
            .orEmpty()
            .map { it.toMutableMap().apply { remove("_datasource") } }
        
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
            parentInstanceId = null,
            rootInstanceId = id,
            processId = "process-$id",
            processDefinitionId = null,
            resourceName = null,
            rootProcessId = null,
            processName = "IntegrationTestProcess",
            startDate = OffsetDateTime.now(),
            endDate = null,
            state = 1,
            businessKey = null,
            version = 1,
            bamProjectId = null,
            extIds = null,
            error = null,
            moduleId = null,
            engineVersion = null,
            enginePodName = null,
            retryCount = 0,
            ownerRole = null,
            idempotencyKey = null,
            operation = null,
            nodeInstances = emptyList(),
            variables = variables
            ,
            contextSize = 1
        )
    }
}
