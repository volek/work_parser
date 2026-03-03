package ru.sber.parser.generator

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MessageGeneratorTest {
    
    private lateinit var generator: MessageGenerator
    
    @TempDir
    lateinit var tempDir: File
    
    @BeforeEach
    fun setup() {
        generator = MessageGenerator()
    }
    
    @Test
    fun `should generate specified number of messages`() {
        generator.generateAll(tempDir, 10)
        
        val files = tempDir.listFiles { f -> f.extension == "json" }
        assertEquals(10, files?.size)
    }
    
    @Test
    fun `should generate message with required fields`() {
        val message = generator.generateMessage("TestProcess", 1)
        
        assertTrue(message.containsKey("id"))
        assertTrue(message.containsKey("processName"))
        assertTrue(message.containsKey("startDate"))
        assertTrue(message.containsKey("state"))
    }
    
    @Test
    fun `should generate message with variables`() {
        val message = generator.generateMessage("TestProcess", 1)
        
        assertTrue(message.containsKey("variables"))
        val variables = message["variables"] as? Map<*, *>
        assertNotNull(variables)
        assertFalse(variables!!.isEmpty())
    }
    
    @Test
    fun `should generate unique IDs`() {
        val messages = (1..10).map { i ->
            generator.generateMessage("TestProcess", i)
        }
        
        val ids = messages.map { it["id"] }
        assertEquals(ids.size, ids.distinct().size)
    }
    
    @Test
    fun `should generate valid state values`() {
        val messages = (1..20).map { i ->
            generator.generateMessage("TestProcess", i)
        }
        
        val validStates = setOf(0, 1, 2, 3)
        assertTrue(messages.all { (it["state"] as Int) in validStates })
    }
    
    @Test
    fun `should generate message with nodeInstances`() {
        val message = generator.generateMessage("TestProcess", 1)
        
        assertTrue(message.containsKey("nodeInstances"))
        val nodes = message["nodeInstances"] as? List<*>
        assertNotNull(nodes)
    }
    
    @Test
    fun `should generate epkData structure`() {
        val messages = (1..10).map { i ->
            generator.generateMessage("TestProcess", i)
        }
        
        val withEpkData = messages.filter { msg ->
            val variables = msg["variables"] as? Map<*, *>
            variables?.containsKey("epkData") == true
        }
        
        assertTrue(withEpkData.isNotEmpty())
    }
    
    @Test
    fun `should generate staticData structure`() {
        val messages = (1..10).map { i ->
            generator.generateMessage("TestProcess", i)
        }
        
        val withStaticData = messages.filter { msg ->
            val variables = msg["variables"] as? Map<*, *>
            variables?.containsKey("staticData") == true
        }
        
        assertTrue(withStaticData.isNotEmpty())
    }
    
    @Test
    fun `should generate valid JSON files`() {
        generator.generateAll(tempDir, 5)
        
        val parser = ru.sber.parser.parser.MessageParser()
        val files = tempDir.listFiles { f -> f.extension == "json" }
        
        files?.forEach { file ->
            val json = file.readText()
            assertDoesNotThrow {
                parser.parse(json)
            }
        }
    }
    
    @Test
    fun `should generate ISO date format`() {
        val message = generator.generateMessage("TestProcess", 1)
        
        val startDate = message["startDate"] as? String
        assertNotNull(startDate)
        assertTrue(startDate!!.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*")))
    }
    
    @Test
    fun `should generate different process names`() {
        generator.generateAll(tempDir, 25)
        
        val parser = ru.sber.parser.parser.MessageParser()
        val files = tempDir.listFiles { f -> f.extension == "json" }
        
        val processNames = files?.map { file ->
            val message = parser.parse(file.readText())
            message.processName
        }?.toSet()
        
        assertNotNull(processNames)
        assertTrue(processNames!!.size > 1)
    }
    
    @Test
    fun `should generate epkId in variables`() {
        val messages = (1..10).map { i ->
            generator.generateMessage("TestProcess", i)
        }
        
        val withEpkId = messages.filter { msg ->
            val variables = msg["variables"] as? Map<*, *>
            variables?.containsKey("epkId") == true
        }
        
        assertTrue(withEpkId.isNotEmpty())
    }
    
    @Test
    fun `should generate fio in variables`() {
        val messages = (1..10).map { i ->
            generator.generateMessage("TestProcess", i)
        }
        
        val withFio = messages.filter { msg ->
            val variables = msg["variables"] as? Map<*, *>
            variables?.containsKey("fio") == true
        }
        
        assertTrue(withFio.isNotEmpty())
    }
}
