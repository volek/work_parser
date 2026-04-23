package ru.sber.parser.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class MessageParserTest {
    
    private lateinit var parser: MessageParser
    
    @BeforeEach
    fun setup() {
        parser = MessageParser()
    }
    
    @Test
    fun `should parse minimal BpmMessage`() {
        val json = baseJson()
        
        val message = parser.parse(json)
        
        assertEquals("test-id", message.id)
        assertEquals("TestProcess", message.processName)
        assertEquals(1, message.state)
        assertNotNull(message.startDate)
    }
    
    @Test
    fun `should parse BpmMessage with variables`() {
        val json = baseJson(
            """
            "epkId": "1234567890",
            "fio": "ИВАНОВ ИВАН ИВАНОВИЧ",
            "caseId": "case-001",
            "amount": 50000,
            "isApproved": true
            """.trimIndent()
        )
        
        val message = parser.parse(json)
        
        assertEquals("test-id", message.id)
        assertEquals(5, message.variables.size)
        assertEquals("1234567890", message.getStringVariable("epkId"))
        assertEquals("ИВАНОВ ИВАН ИВАНОВИЧ", message.getStringVariable("fio"))
        assertEquals(true, message.getVariable("isApproved"))
    }
    
    @Test
    fun `should parse BpmMessage with nested variables`() {
        val json = baseJson(
            """
            "epkData": {
                "epkEntity": {
                    "ucpId": "UCP123",
                    "firstName": "Иван",
                    "lastName": "Иванов"
                }
            },
            "staticData": {
                "clientEpkId": 12345,
                "productCode": "CREDIT"
            }
            """.trimIndent()
        )
        
        val message = parser.parse(json)
        
        assertNotNull(message.variables["epkData"])
        assertNotNull(message.variables["staticData"])
        
        val epkData = message.variables["epkData"] as? Map<*, *>
        assertNotNull(epkData)
        
        val epkEntity = epkData?.get("epkEntity") as? Map<*, *>
        assertNotNull(epkEntity)
        assertEquals("UCP123", epkEntity?.get("ucpId"))
    }
    
    @Test
    fun `should parse BpmMessage with nodeInstances`() {
        val json = baseJson(
            extraTopLevel = """
            "nodeInstances": [
                {
                    "id": "node-1",
                    "nodeId": "StartEvent_1",
                    "nodeDefinitionId": null,
                    "nodeName": "Start",
                    "nodeType": "StartNode",
                    "error": null,
                    "state": 1,
                    "calledProcessInstanceIds": null,
                    "retries": null,
                    "htmTaskId": null,
                    "triggerTime": "2024-01-15T10:30:00Z",
                    "leaveTime": null,
                    "triggerNodeInstanceId": null,
                    "creationOrder": 1
                },
                {
                    "id": "node-2",
                    "nodeId": "Task_1",
                    "nodeDefinitionId": null,
                    "nodeName": "Task",
                    "nodeType": "HumanTaskNode",
                    "error": null,
                    "state": 0,
                    "calledProcessInstanceIds": null,
                    "retries": null,
                    "htmTaskId": null,
                    "triggerTime": "2024-01-15T10:31:00Z",
                    "leaveTime": null,
                    "triggerNodeInstanceId": null,
                    "creationOrder": 2
                }
            ]
            """.trimIndent()
        )
        
        val message = parser.parse(json)
        
        assertEquals(2, message.nodeInstances.size)
        assertEquals("StartEvent_1", message.nodeInstances[0].nodeId)
        assertEquals("HumanTaskNode", message.nodeInstances[1].nodeType)
    }
    
    @Test
    fun `should serialize BpmMessage to JSON`() {
        val json = baseJson()
        
        val message = parser.parse(json)
        val serialized = parser.toJson(message)
        
        assertTrue(serialized.contains("test-id"))
        assertTrue(serialized.contains("TestProcess"))
    }
    
    @Test
    fun `should ignore unknown properties`() {
        val json = baseJson(extraTopLevel = """"unknownField": "should be ignored", "anotherUnknown": 12345""")
        
        val message = parser.parse(json)
        
        assertEquals("test-id", message.id)
        assertEquals("TestProcess", message.processName)
    }
    
    @Test
    fun `should handle null variables gracefully`() {
        val json = baseJson(
            """
            "nullField": null,
            "validField": "value"
            """.trimIndent()
        )
        
        val message = parser.parse(json)
        
        assertNull(message.variables["nullField"])
        assertEquals("value", message.variables["validField"])
        assertNull(message.getStringVariable("nullField"))
    }
    
    @Test
    fun `should parse endDate when present`() {
        val json = baseJson(extraTopLevel = """"endDate":"2024-01-15T11:45:00Z","state":2""")
        
        val message = parser.parse(json)
        
        assertNotNull(message.endDate)
    }
    
    @Test
    fun `should parse array variables`() {
        val json = baseJson(
            """
            "phoneNumbers": [
                {"type": "mobile", "number": "+79001234567"},
                {"type": "home", "number": "+74951234567"}
            ],
            "tags": ["urgent", "verified", "processed"]
            """.trimIndent()
        )
        
        val message = parser.parse(json)
        
        val phones = message.variables["phoneNumbers"] as? List<*>
        assertNotNull(phones)
        assertEquals(2, phones?.size)
        
        val tags = message.variables["tags"] as? List<*>
        assertNotNull(tags)
        assertEquals(3, tags?.size)
    }

    private fun baseJson(
        variablesBlock: String = "",
        extraTopLevel: String = ""
    ): String {
        val extra = if (extraTopLevel.isBlank()) "" else ",$extraTopLevel"
        val variables = if (variablesBlock.isBlank()) "{}" else "{ $variablesBlock }"
        return """
        {
            "id": "test-id",
            "parentInstanceId": null,
            "rootInstanceId": "test-id",
            "processId": "Process_1",
            "processDefinitionId": "Process_1:1:abc",
            "resourceName": null,
            "rootProcessId": null,
            "processName": "TestProcess",
            "startDate": "2024-01-15T10:30:00Z",
            "state": 1,
            "businessKey": null,
            "version": 1,
            "bamProjectId": null,
            "extIds": null,
            "error": null,
            "moduleId": null,
            "engineVersion": null,
            "enginePodName": null,
            "retryCount": 0,
            "ownerRole": null,
            "idempotencyKey": null,
            "operation": null,
            "variables": $variables,
            "contextSize": 1
            $extra
        }
        """.trimIndent()
    }
}
