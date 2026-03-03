package ru.sber.parser.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.time.OffsetDateTime

class MessageParserTest {
    
    private lateinit var parser: MessageParser
    
    @BeforeEach
    fun setup() {
        parser = MessageParser()
    }
    
    @Test
    fun `should parse minimal BpmMessage`() {
        val json = """
        {
            "id": "test-123",
            "processName": "TestProcess",
            "startDate": "2024-01-15T10:30:00Z",
            "state": 1
        }
        """.trimIndent()
        
        val message = parser.parse(json)
        
        assertEquals("test-123", message.id)
        assertEquals("TestProcess", message.processName)
        assertEquals(1, message.state)
        assertNotNull(message.startDate)
    }
    
    @Test
    fun `should parse BpmMessage with variables`() {
        val json = """
        {
            "id": "test-456",
            "processName": "ProcessWithVars",
            "startDate": "2024-01-15T10:30:00Z",
            "state": 2,
            "variables": {
                "epkId": "1234567890",
                "fio": "ИВАНОВ ИВАН ИВАНОВИЧ",
                "caseId": "case-001",
                "amount": 50000,
                "isApproved": true
            }
        }
        """.trimIndent()
        
        val message = parser.parse(json)
        
        assertEquals("test-456", message.id)
        assertEquals(5, message.variables.size)
        assertEquals("1234567890", message.getStringVariable("epkId"))
        assertEquals("ИВАНОВ ИВАН ИВАНОВИЧ", message.getStringVariable("fio"))
        assertEquals(true, message.getBooleanVariable("isApproved"))
    }
    
    @Test
    fun `should parse BpmMessage with nested variables`() {
        val json = """
        {
            "id": "test-789",
            "processName": "NestedVarsProcess",
            "startDate": "2024-01-15T10:30:00Z",
            "state": 1,
            "variables": {
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
            }
        }
        """.trimIndent()
        
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
        val json = """
        {
            "id": "test-nodes",
            "processName": "NodeProcess",
            "startDate": "2024-01-15T10:30:00Z",
            "state": 2,
            "nodeInstances": [
                {
                    "nodeId": "StartEvent_1",
                    "nodeType": "StartNode",
                    "state": 1,
                    "triggerTime": "2024-01-15T10:30:00Z"
                },
                {
                    "nodeId": "Task_1",
                    "nodeType": "HumanTaskNode",
                    "state": 0,
                    "triggerTime": "2024-01-15T10:31:00Z"
                }
            ]
        }
        """.trimIndent()
        
        val message = parser.parse(json)
        
        assertEquals(2, message.nodeInstances.size)
        assertEquals("StartEvent_1", message.nodeInstances[0].nodeId)
        assertEquals("HumanTaskNode", message.nodeInstances[1].nodeType)
    }
    
    @Test
    fun `should serialize BpmMessage to JSON`() {
        val json = """
        {
            "id": "serialize-test",
            "processName": "SerializeProcess",
            "startDate": "2024-01-15T10:30:00Z",
            "state": 1
        }
        """.trimIndent()
        
        val message = parser.parse(json)
        val serialized = parser.toJson(message)
        
        assertTrue(serialized.contains("serialize-test"))
        assertTrue(serialized.contains("SerializeProcess"))
    }
    
    @Test
    fun `should ignore unknown properties`() {
        val json = """
        {
            "id": "unknown-props",
            "processName": "UnknownPropsProcess",
            "startDate": "2024-01-15T10:30:00Z",
            "state": 1,
            "unknownField": "should be ignored",
            "anotherUnknown": 12345
        }
        """.trimIndent()
        
        val message = parser.parse(json)
        
        assertEquals("unknown-props", message.id)
        assertEquals("UnknownPropsProcess", message.processName)
    }
    
    @Test
    fun `should handle null variables gracefully`() {
        val json = """
        {
            "id": "null-vars",
            "processName": "NullVarsProcess",
            "startDate": "2024-01-15T10:30:00Z",
            "state": 1,
            "variables": {
                "nullField": null,
                "validField": "value"
            }
        }
        """.trimIndent()
        
        val message = parser.parse(json)
        
        assertNull(message.variables["nullField"])
        assertEquals("value", message.variables["validField"])
        assertNull(message.getStringVariable("nullField"))
    }
    
    @Test
    fun `should parse endDate when present`() {
        val json = """
        {
            "id": "with-end",
            "processName": "CompletedProcess",
            "startDate": "2024-01-15T10:30:00Z",
            "endDate": "2024-01-15T11:45:00Z",
            "state": 2
        }
        """.trimIndent()
        
        val message = parser.parse(json)
        
        assertNotNull(message.endDate)
    }
    
    @Test
    fun `should parse array variables`() {
        val json = """
        {
            "id": "array-test",
            "processName": "ArrayProcess",
            "startDate": "2024-01-15T10:30:00Z",
            "state": 1,
            "variables": {
                "phoneNumbers": [
                    {"type": "mobile", "number": "+79001234567"},
                    {"type": "home", "number": "+74951234567"}
                ],
                "tags": ["urgent", "verified", "processed"]
            }
        }
        """.trimIndent()
        
        val message = parser.parse(json)
        
        val phones = message.variables["phoneNumbers"] as? List<*>
        assertNotNull(phones)
        assertEquals(2, phones?.size)
        
        val tags = message.variables["tags"] as? List<*>
        assertNotNull(tags)
        assertEquals(3, tags?.size)
    }
}
