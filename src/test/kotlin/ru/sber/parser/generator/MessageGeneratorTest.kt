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

        val variables = message["variables"] as? Map<*, *>
        val nodes = variables?.get("nodeInstances") as? List<*>
        assertNotNull(nodes)
        assertTrue(nodes!!.isNotEmpty())
    }

    @Test
    fun `should generate many nested arrays for stress scenarios`() {
        val messages = (1..40).map { i -> generator.generateMessage("TestProcess", i) }
        val variablesList = messages.mapNotNull { it["variables"] as? Map<*, *> }
        val arraysPerMessage = variablesList.map { vars ->
            vars.values.count { it is List<*> }
        }

        assertTrue(arraysPerMessage.any { it >= 4 })
        assertTrue(arraysPerMessage.any { it >= 10 })
        assertTrue(variablesList.any { vars ->
            val operations = vars["operations"] as? List<*> ?: return@any false
            operations.isNotEmpty()
        })
        assertTrue(variablesList.any { vars ->
            val profile = vars["arrayDepthProfile"] as? List<*> ?: return@any false
            profile.isNotEmpty()
        })
    }

    @Test
    fun `should generate array profile with nested objects`() {
        val message = generator.generateMessage("TestProcess", 15)
        val variables = message["variables"] as? Map<*, *> ?: error("variables missing")
        val profile = variables["arrayDepthProfile"] as? List<*> ?: error("arrayDepthProfile missing")
        val profileString = profile.toString()

        assertTrue(profileString.contains("targetDepth"))
        assertTrue(profileString.contains("payload"))
        assertTrue(profileString.contains("code=DEPTH_"))
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
