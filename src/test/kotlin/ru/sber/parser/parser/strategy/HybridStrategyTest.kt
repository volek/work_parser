package ru.sber.parser.parser.strategy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import ru.sber.parser.model.BpmMessage
import ru.sber.parser.model.NodeInstance
import java.time.OffsetDateTime

class HybridStrategyTest {
    
    private lateinit var strategy: HybridStrategy
    
    @BeforeEach
    fun setup() {
        strategy = HybridStrategy()
    }
    
    @Test
    fun `should return correct data source name`() {
        assertEquals("process_hybrid", strategy.dataSourceName)
    }
    
    @Test
    fun `should transform message to single record`() {
        val message = createTestMessage()
        
        val records = strategy.transform(message)
        
        assertEquals(1, records.size)
    }
    
    @Test
    fun `should include process metadata in record`() {
        val message = createTestMessage()
        
        val records = strategy.transform(message)
        val record = records.first()
        
        assertEquals("test-123", record["process_id"])
        assertEquals("TestProcess", record["process_name"])
        assertEquals(1, record["state"])
    }
    
    @Test
    fun `should extract hot variables as columns`() {
        val message = createTestMessage(
            variables = mapOf(
                "epkId" to "1234567890",
                "fio" to "ИВАНОВ ИВАН ИВАНОВИЧ",
                "caseId" to "case-001",
                "casePublicId" to "public-001",
                "productCode" to "CREDIT",
                "clientCategory" to "VIP"
            )
        )
        
        val records = strategy.transform(message)
        val record = records.first()
        
        assertEquals("1234567890", record["var_epkId"])
        assertEquals("ИВАНОВ ИВАН ИВАНОВИЧ", record["var_fio"])
        assertEquals("case-001", record["var_caseId"])
        assertEquals("public-001", record["var_casePublicId"])
        assertEquals("CREDIT", record["var_productCode"])
        assertEquals("VIP", record["var_clientCategory"])
    }
    
    @Test
    fun `should serialize nodeInstances to JSON`() {
        val nodeInstances = listOf(
            NodeInstance(
                nodeId = "Start_1",
                nodeType = "StartNode",
                state = 1,
                triggerTime = OffsetDateTime.now()
            )
        )
        val message = createTestMessage(nodeInstances = nodeInstances)
        
        val records = strategy.transform(message)
        val record = records.first()
        
        assertNotNull(record["node_instances_json"])
        assertTrue(record["node_instances_json"].toString().contains("Start_1"))
    }
    
    @Test
    fun `should serialize epkData to JSON blob`() {
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
        val record = records.first()
        
        assertNotNull(record["var_epkData_json"])
        assertTrue(record["var_epkData_json"].toString().contains("UCP123"))
    }
    
    @Test
    fun `should serialize staticData to JSON blob`() {
        val message = createTestMessage(
            variables = mapOf(
                "staticData" to mapOf(
                    "clientEpkId" to 12345,
                    "productCode" to "DEBIT"
                )
            )
        )
        
        val records = strategy.transform(message)
        val record = records.first()
        
        assertNotNull(record["var_staticData_json"])
        assertTrue(record["var_staticData_json"].toString().contains("12345"))
    }
    
    @Test
    fun `should serialize answerGFL to JSON blob`() {
        val message = createTestMessage(
            variables = mapOf(
                "answerGFL" to mapOf(
                    "gflResult" to "approved",
                    "score" to 85
                )
            )
        )
        
        val records = strategy.transform(message)
        val record = records.first()
        
        assertNotNull(record["var_answerGFL_json"])
        assertTrue(record["var_answerGFL_json"].toString().contains("approved"))
    }
    
    @Test
    fun `should collect other variables into JSON blob`() {
        val message = createTestMessage(
            variables = mapOf(
                "epkId" to "123",
                "customField1" to "value1",
                "customField2" to mapOf("nested" to "data")
            )
        )
        
        val records = strategy.transform(message)
        val record = records.first()
        
        assertNotNull(record["other_variables_json"])
        val otherJson = record["other_variables_json"].toString()
        assertTrue(otherJson.contains("customField1"))
        assertTrue(otherJson.contains("customField2"))
    }
    
    @Test
    fun `should handle empty variables`() {
        val message = createTestMessage(variables = emptyMap())
        
        val records = strategy.transform(message)
        val record = records.first()
        
        assertNull(record["var_epkId"])
        assertNull(record["var_fio"])
    }
    
    @Test
    fun `should transform batch of messages`() {
        val messages = listOf(
            createTestMessage(id = "msg-1"),
            createTestMessage(id = "msg-2"),
            createTestMessage(id = "msg-3")
        )
        
        val result = strategy.transformBatch(messages)
        
        assertEquals(1, result.size)
        assertEquals(3, result[strategy.dataSourceName]?.size)
    }
    
    @Test
    fun `should include timestamp column`() {
        val startDate = OffsetDateTime.now()
        val message = createTestMessage(startDate = startDate)
        
        val records = strategy.transform(message)
        val record = records.first()
        
        assertNotNull(record["__time"])
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
