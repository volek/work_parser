package ru.sber.parser.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class VariableFlattenerTest {
    
    private lateinit var flattener: VariableFlattener
    
    @BeforeEach
    fun setup() {
        flattener = VariableFlattener()
    }
    
    @Test
    fun `should flatten simple variables`() {
        val variables = mapOf(
            "epkId" to "1234567890",
            "fio" to "ИВАНОВ ИВАН ИВАНОВИЧ",
            "amount" to 50000
        )
        
        val flattened = flattener.flatten(variables)
        
        assertEquals(3, flattened.size)
        assertTrue(flattened.any { it.path == "epkId" && it.value == "1234567890" })
        assertTrue(flattened.any { it.path == "fio" && it.type == "string" })
        assertTrue(flattened.any { it.path == "amount" && it.type == "number" })
    }
    
    @Test
    fun `should flatten nested objects`() {
        val variables = mapOf(
            "epkData" to mapOf(
                "epkEntity" to mapOf(
                    "ucpId" to "UCP123",
                    "firstName" to "Иван"
                )
            )
        )
        
        val flattened = flattener.flatten(variables)
        
        assertTrue(flattened.any { it.path == "epkData.epkEntity.ucpId" && it.value == "UCP123" })
        assertTrue(flattened.any { it.path == "epkData.epkEntity.firstName" && it.value == "Иван" })
    }
    
    @Test
    fun `should flatten arrays with indices`() {
        val variables = mapOf(
            "phoneNumbers" to listOf(
                mapOf("type" to "mobile", "number" to "+79001234567"),
                mapOf("type" to "home", "number" to "+74951234567")
            )
        )
        
        val flattened = flattener.flatten(variables)
        
        assertTrue(flattened.any { it.path == "phoneNumbers[0].type" && it.value == "mobile" })
        assertTrue(flattened.any { it.path == "phoneNumbers[0].number" && it.value == "+79001234567" })
        assertTrue(flattened.any { it.path == "phoneNumbers[1].type" && it.value == "home" })
        assertTrue(flattened.any { it.path == "phoneNumbers[1].number" && it.value == "+74951234567" })
    }
    
    @Test
    fun `should flatten primitive arrays`() {
        val variables = mapOf(
            "tags" to listOf("urgent", "verified", "processed")
        )
        
        val flattened = flattener.flatten(variables)
        
        assertTrue(flattened.any { it.path == "tags[0]" && it.value == "urgent" })
        assertTrue(flattened.any { it.path == "tags[1]" && it.value == "verified" })
        assertTrue(flattened.any { it.path == "tags[2]" && it.value == "processed" })
    }
    
    @Test
    fun `should detect correct types`() {
        val variables = mapOf(
            "stringVal" to "text",
            "intVal" to 123,
            "doubleVal" to 123.45,
            "boolTrue" to true,
            "boolFalse" to false,
            "nullVal" to null
        )
        
        val flattened = flattener.flatten(variables)
        
        assertEquals("string", flattened.find { it.path == "stringVal" }?.type)
        assertEquals("number", flattened.find { it.path == "intVal" }?.type)
        assertEquals("number", flattened.find { it.path == "doubleVal" }?.type)
        assertEquals("boolean", flattened.find { it.path == "boolTrue" }?.type)
        assertEquals("boolean", flattened.find { it.path == "boolFalse" }?.type)
        assertEquals("null", flattened.find { it.path == "nullVal" }?.type)
    }
    
    @Test
    fun `should handle deeply nested structures`() {
        val variables = mapOf(
            "level1" to mapOf(
                "level2" to mapOf(
                    "level3" to mapOf(
                        "level4" to mapOf(
                            "deepValue" to "found"
                        )
                    )
                )
            )
        )
        
        val flattened = flattener.flatten(variables)
        
        assertTrue(flattened.any { 
            it.path == "level1.level2.level3.level4.deepValue" && it.value == "found" 
        })
    }
    
    @Test
    fun `should flatten with custom prefix`() {
        val variables = mapOf(
            "field1" to "value1",
            "field2" to "value2"
        )
        
        val flattened = flattener.flatten(variables, "prefix")
        
        assertTrue(flattened.any { it.path == "prefix.field1" && it.value == "value1" })
        assertTrue(flattened.any { it.path == "prefix.field2" && it.value == "value2" })
    }
    
    @Test
    fun `should handle empty map`() {
        val variables = emptyMap<String, Any?>()
        
        val flattened = flattener.flatten(variables)
        
        assertTrue(flattened.isEmpty())
    }
    
    @Test
    fun `should handle empty nested map`() {
        val variables = mapOf(
            "emptyObject" to emptyMap<String, Any?>(),
            "validField" to "value"
        )
        
        val flattened = flattener.flatten(variables)
        
        assertTrue(flattened.any { it.path == "validField" })
    }
    
    @Test
    fun `should handle empty arrays`() {
        val variables = mapOf(
            "emptyArray" to emptyList<Any>(),
            "validField" to "value"
        )
        
        val flattened = flattener.flatten(variables)
        
        assertTrue(flattened.any { it.path == "validField" })
    }
    
    @Test
    fun `should handle mixed nested and array structures`() {
        val variables = mapOf(
            "data" to mapOf(
                "items" to listOf(
                    mapOf(
                        "nested" to mapOf(
                            "value" to "deep"
                        )
                    )
                )
            )
        )
        
        val flattened = flattener.flatten(variables)
        
        assertTrue(flattened.any { 
            it.path == "data.items[0].nested.value" && it.value == "deep" 
        })
    }
    
    @Test
    fun `should extract value by path`() {
        val variables = mapOf(
            "epkData" to mapOf(
                "epkEntity" to mapOf(
                    "ucpId" to "UCP123"
                )
            )
        )
        
        val value = flattener.extractValue(variables, "epkData.epkEntity.ucpId")
        
        assertEquals("UCP123", value)
    }
    
    @Test
    fun `should return null for non-existent path`() {
        val variables = mapOf(
            "field1" to "value1"
        )
        
        val value = flattener.extractValue(variables, "nonExistent.path")
        
        assertNull(value)
    }
    
    @Test
    fun `should extract from array by index path`() {
        val variables = mapOf(
            "items" to listOf(
                mapOf("name" to "first"),
                mapOf("name" to "second")
            )
        )
        
        val value = flattener.extractValue(variables, "items[1].name")
        
        assertEquals("second", value)
    }
}
