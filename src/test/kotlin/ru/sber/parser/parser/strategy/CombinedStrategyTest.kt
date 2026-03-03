package ru.sber.parser.parser.strategy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import ru.sber.parser.config.FieldClassification
import ru.sber.parser.model.BpmMessage
import ru.sber.parser.model.NodeInstance
import java.time.OffsetDateTime

class CombinedStrategyTest {
    
    private lateinit var strategy: CombinedStrategy
    private lateinit var classification: FieldClassification
    
    @BeforeEach
    fun setup() {
        classification = FieldClassification.default()
        strategy = CombinedStrategy(classification)
    }
    
    @Test
    fun `should return correct data source names`() {
        assertEquals("process_main", strategy.dataSourceName)
        assertTrue(strategy.additionalDataSources.contains("process_variables_indexed"))
    }
    
    @Test
    fun `should create main record with tier 1 hot columns`() {
        val message = createTestMessage(
            variables = mapOf(
                "epkId" to "1234567890",
                "fio" to "ИВАНОВ ИВАН ИВАНОВИЧ",
                "caseId" to "case-001"
            )
        )
        
        val records = strategy.transform(message)
        val mainRecords = records.filter { it["_datasource"] == "process_main" }
        
        assertEquals(1, mainRecords.size)
        
        val main = mainRecords.first()
        assertEquals("1234567890", main["var_epkId"])
        assertEquals("ИВАНОВ ИВАН ИВАНОВИЧ", main["var_fio"])
        assertEquals("case-001", main["var_caseId"])
    }
    
    @Test
    fun `should include process metadata in main record`() {
        val message = createTestMessage()
        
        val records = strategy.transform(message)
        val main = records.first { it["_datasource"] == "process_main" }
        
        assertEquals("test-123", main["process_id"])
        assertEquals("TestProcess", main["process_name"])
        assertEquals(1, main["state"])
    }
    
    @Test
    fun `should create indexed records for tier 2 warm fields`() {
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
        val indexedRecords = records.filter { it["_datasource"] == "process_variables_indexed" }
        
        assertTrue(indexedRecords.isNotEmpty())
        assertTrue(indexedRecords.any { it["category"] == "epkData" })
        assertTrue(indexedRecords.any { 
            it["var_path"] == "epkEntity.ucpId" && it["var_value"] == "UCP123" 
        })
    }
    
    @Test
    fun `should include category in indexed records`() {
        val message = createTestMessage(
            variables = mapOf(
                "staticData" to mapOf(
                    "clientEpkId" to "12345",
                    "productCode" to "CREDIT"
                )
            )
        )
        
        val records = strategy.transform(message)
        val indexedRecords = records.filter { it["_datasource"] == "process_variables_indexed" }
        
        assertTrue(indexedRecords.all { it.containsKey("category") })
        assertTrue(indexedRecords.any { it["category"] == "staticData" })
    }
    
    @Test
    fun `should serialize tier 3 cold fields to JSON blob`() {
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
        val main = records.first { it["_datasource"] == "process_main" }
        
        assertNotNull(main["cold_blob_json"])
        assertTrue(main["cold_blob_json"].toString().contains("Start_1"))
    }
    
    @Test
    fun `should include process_id in all indexed records`() {
        val message = createTestMessage(
            id = "unique-id",
            variables = mapOf(
                "epkData" to mapOf("field1" to "value1"),
                "staticData" to mapOf("field2" to "value2")
            )
        )
        
        val records = strategy.transform(message)
        val indexedRecords = records.filter { it["_datasource"] == "process_variables_indexed" }
        
        assertTrue(indexedRecords.all { it["process_id"] == "unique-id" })
    }
    
    @Test
    fun `should handle empty variables`() {
        val message = createTestMessage(variables = emptyMap())
        
        val records = strategy.transform(message)
        
        val mainRecords = records.filter { it["_datasource"] == "process_main" }
        val indexedRecords = records.filter { it["_datasource"] == "process_variables_indexed" }
        
        assertEquals(1, mainRecords.size)
        assertEquals(0, indexedRecords.size)
    }
    
    @Test
    fun `should correctly classify hot variables`() {
        val message = createTestMessage(
            variables = mapOf(
                "epkId" to "123",
                "fio" to "ФИО",
                "caseId" to "case",
                "casePublicId" to "public",
                "productCode" to "PRODUCT",
                "clientCategory" to "CATEGORY"
            )
        )
        
        val records = strategy.transform(message)
        val main = records.first { it["_datasource"] == "process_main" }
        
        assertEquals("123", main["var_epkId"])
        assertEquals("ФИО", main["var_fio"])
        assertEquals("case", main["var_caseId"])
        assertEquals("public", main["var_casePublicId"])
        assertEquals("PRODUCT", main["var_productCode"])
        assertEquals("CATEGORY", main["var_clientCategory"])
    }
    
    @Test
    fun `should transform batch and group by datasource`() {
        val messages = listOf(
            createTestMessage(
                id = "msg-1", 
                variables = mapOf(
                    "epkId" to "1",
                    "epkData" to mapOf("field" to "val1")
                )
            ),
            createTestMessage(
                id = "msg-2", 
                variables = mapOf(
                    "epkId" to "2",
                    "epkData" to mapOf("field" to "val2")
                )
            )
        )
        
        val result = strategy.transformBatch(messages)
        
        assertEquals(2, result.size)
        assertTrue(result.containsKey("process_main"))
        assertTrue(result.containsKey("process_variables_indexed"))
        
        assertEquals(2, result["process_main"]?.size)
    }
    
    @Test
    fun `should include timestamp in main record`() {
        val startDate = OffsetDateTime.now()
        val message = createTestMessage(startDate = startDate)
        
        val records = strategy.transform(message)
        val main = records.first { it["_datasource"] == "process_main" }
        
        assertNotNull(main["__time"])
    }
    
    @Test
    fun `should include variable type in indexed records`() {
        val message = createTestMessage(
            variables = mapOf(
                "epkData" to mapOf(
                    "stringVal" to "text",
                    "numberVal" to 123,
                    "boolVal" to true
                )
            )
        )
        
        val records = strategy.transform(message)
        val indexedRecords = records.filter { it["_datasource"] == "process_variables_indexed" }
        
        assertEquals("string", indexedRecords.find { it["var_path"] == "stringVal" }?.get("var_type"))
        assertEquals("number", indexedRecords.find { it["var_path"] == "numberVal" }?.get("var_type"))
        assertEquals("boolean", indexedRecords.find { it["var_path"] == "boolVal" }?.get("var_type"))
    }
    
    @Test
    fun `should handle gflAnswer category`() {
        val message = createTestMessage(
            variables = mapOf(
                "answerGFL" to mapOf(
                    "result" to "approved",
                    "score" to 85
                )
            )
        )
        
        val records = strategy.transform(message)
        val indexedRecords = records.filter { it["_datasource"] == "process_variables_indexed" }
        
        assertTrue(indexedRecords.any { it["category"] == "gflAnswer" })
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
