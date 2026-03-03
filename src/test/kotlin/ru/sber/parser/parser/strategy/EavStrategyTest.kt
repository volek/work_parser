package ru.sber.parser.parser.strategy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import ru.sber.parser.model.BpmMessage
import ru.sber.parser.model.NodeInstance
import java.time.OffsetDateTime

class EavStrategyTest {
    
    private lateinit var strategy: EavStrategy
    
    @BeforeEach
    fun setup() {
        strategy = EavStrategy()
    }
    
    @Test
    fun `should return correct data source names`() {
        assertEquals("process_events", strategy.dataSourceName)
        assertTrue(strategy.additionalDataSources.contains("process_variables"))
    }
    
    @Test
    fun `should create process event record`() {
        val message = createTestMessage()
        
        val records = strategy.transform(message)
        
        val eventRecords = records.filter { it["_datasource"] == "process_events" }
        assertEquals(1, eventRecords.size)
        
        val event = eventRecords.first()
        assertEquals("test-123", event["process_id"])
        assertEquals("TestProcess", event["process_name"])
        assertEquals(1, event["state"])
    }
    
    @Test
    fun `should create variable records for each variable`() {
        val message = createTestMessage(
            variables = mapOf(
                "epkId" to "1234567890",
                "fio" to "ИВАНОВ ИВАН ИВАНОВИЧ",
                "amount" to 50000
            )
        )
        
        val records = strategy.transform(message)
        
        val varRecords = records.filter { it["_datasource"] == "process_variables" }
        assertEquals(3, varRecords.size)
        
        assertTrue(varRecords.any { 
            it["var_path"] == "epkId" && it["var_value"] == "1234567890" 
        })
        assertTrue(varRecords.any { 
            it["var_path"] == "fio" && it["var_value"] == "ИВАНОВ ИВАН ИВАНОВИЧ" 
        })
    }
    
    @Test
    fun `should flatten nested variables`() {
        val message = createTestMessage(
            variables = mapOf(
                "epkData" to mapOf(
                    "epkEntity" to mapOf(
                        "ucpId" to "UCP123",
                        "firstName" to "Иван"
                    )
                )
            )
        )
        
        val records = strategy.transform(message)
        val varRecords = records.filter { it["_datasource"] == "process_variables" }
        
        assertTrue(varRecords.any { 
            it["var_path"] == "epkData.epkEntity.ucpId" && it["var_value"] == "UCP123" 
        })
        assertTrue(varRecords.any { 
            it["var_path"] == "epkData.epkEntity.firstName" && it["var_value"] == "Иван" 
        })
    }
    
    @Test
    fun `should include correct variable types`() {
        val message = createTestMessage(
            variables = mapOf(
                "stringVal" to "text",
                "numberVal" to 123,
                "boolVal" to true,
                "nullVal" to null
            )
        )
        
        val records = strategy.transform(message)
        val varRecords = records.filter { it["_datasource"] == "process_variables" }
        
        assertEquals("string", varRecords.find { it["var_path"] == "stringVal" }?.get("var_type"))
        assertEquals("number", varRecords.find { it["var_path"] == "numberVal" }?.get("var_type"))
        assertEquals("boolean", varRecords.find { it["var_path"] == "boolVal" }?.get("var_type"))
        assertEquals("null", varRecords.find { it["var_path"] == "nullVal" }?.get("var_type"))
    }
    
    @Test
    fun `should flatten array elements with indices`() {
        val message = createTestMessage(
            variables = mapOf(
                "items" to listOf(
                    mapOf("name" to "item1"),
                    mapOf("name" to "item2")
                )
            )
        )
        
        val records = strategy.transform(message)
        val varRecords = records.filter { it["_datasource"] == "process_variables" }
        
        assertTrue(varRecords.any { it["var_path"] == "items[0].name" && it["var_value"] == "item1" })
        assertTrue(varRecords.any { it["var_path"] == "items[1].name" && it["var_value"] == "item2" })
    }
    
    @Test
    fun `should include process_id in all variable records`() {
        val message = createTestMessage(
            id = "unique-process-id",
            variables = mapOf(
                "var1" to "value1",
                "var2" to "value2"
            )
        )
        
        val records = strategy.transform(message)
        val varRecords = records.filter { it["_datasource"] == "process_variables" }
        
        assertTrue(varRecords.all { it["process_id"] == "unique-process-id" })
    }
    
    @Test
    fun `should handle empty variables`() {
        val message = createTestMessage(variables = emptyMap())
        
        val records = strategy.transform(message)
        
        val eventRecords = records.filter { it["_datasource"] == "process_events" }
        val varRecords = records.filter { it["_datasource"] == "process_variables" }
        
        assertEquals(1, eventRecords.size)
        assertEquals(0, varRecords.size)
    }
    
    @Test
    fun `should transform batch and group by datasource`() {
        val messages = listOf(
            createTestMessage(id = "msg-1", variables = mapOf("var1" to "val1")),
            createTestMessage(id = "msg-2", variables = mapOf("var2" to "val2"))
        )
        
        val result = strategy.transformBatch(messages)
        
        assertEquals(2, result.size)
        assertTrue(result.containsKey("process_events"))
        assertTrue(result.containsKey("process_variables"))
        
        assertEquals(2, result["process_events"]?.size)
        assertEquals(2, result["process_variables"]?.size)
    }
    
    @Test
    fun `should include timestamp in event record`() {
        val startDate = OffsetDateTime.now()
        val message = createTestMessage(startDate = startDate)
        
        val records = strategy.transform(message)
        val event = records.first { it["_datasource"] == "process_events" }
        
        assertNotNull(event["__time"])
    }
    
    @Test
    fun `should handle deeply nested structures`() {
        val message = createTestMessage(
            variables = mapOf(
                "level1" to mapOf(
                    "level2" to mapOf(
                        "level3" to mapOf(
                            "deepValue" to "found"
                        )
                    )
                )
            )
        )
        
        val records = strategy.transform(message)
        val varRecords = records.filter { it["_datasource"] == "process_variables" }
        
        assertTrue(varRecords.any { 
            it["var_path"] == "level1.level2.level3.deepValue" && it["var_value"] == "found" 
        })
    }
    
    private fun createTestMessage(
        id: String = "test-123",
        processName: String = "TestProcess",
        state: Int = 1,
        startDate: OffsetDateTime = OffsetDateTime.now(),
        endDate: OffsetDateTime? = null,
        nodeInstances: List<NodeInstance> = emptyList(),
        variables: Map<String, Any?> = emptyMap()
    ): BpmMessage {
        return BpmMessage(
            id = id,
            processName = processName,
            startDate = startDate,
            state = state,
            endDate = endDate,
            nodeInstances = nodeInstances,
            variables = variables
        )
    }
}
