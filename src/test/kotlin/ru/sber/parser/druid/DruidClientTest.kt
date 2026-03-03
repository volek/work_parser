package ru.sber.parser.druid

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import ru.sber.parser.config.DruidConfig

class DruidClientTest {
    
    private lateinit var config: DruidConfig
    
    @BeforeEach
    fun setup() {
        config = DruidConfig(
            brokerUrl = "http://localhost:8082",
            coordinatorUrl = "http://localhost:8081",
            overlordUrl = "http://localhost:8090",
            batchSize = 100
        )
    }
    
    @Test
    fun `should use correct broker URL for queries`() {
        assertEquals("http://localhost:8082", config.brokerUrl)
    }
    
    @Test
    fun `should use correct overlord URL for ingestion`() {
        assertEquals("http://localhost:8090", config.overlordUrl)
    }
    
    @Test
    fun `should have configurable batch size`() {
        assertEquals(100, config.batchSize)
    }
    
    @Test
    fun `should create correct ingestion spec for single record`() {
        val records = listOf(
            mapOf(
                "process_id" to "test-123",
                "process_name" to "TestProcess",
                "state" to 1,
                "__time" to "2024-01-15T10:30:00Z"
            )
        )
        
        val spec = createTestIngestionSpec("test_datasource", records)
        
        assertNotNull(spec)
        assertEquals("index_parallel", spec["type"])
        
        val specMap = spec["spec"] as Map<*, *>
        val dataSchema = specMap["dataSchema"] as Map<*, *>
        assertEquals("test_datasource", dataSchema["dataSource"])
    }
    
    @Test
    fun `should infer string type for text values`() {
        val type = inferDruidType("text value")
        assertEquals("string", type)
    }
    
    @Test
    fun `should infer long type for integer values`() {
        val type = inferDruidType(123)
        assertEquals("long", type)
    }
    
    @Test
    fun `should infer double type for decimal values`() {
        val type = inferDruidType(123.45)
        assertEquals("double", type)
    }
    
    @Test
    fun `should infer string type for boolean values`() {
        val type = inferDruidType(true)
        assertEquals("string", type)
    }
    
    @Test
    fun `should infer string type for null values`() {
        val type = inferDruidType(null)
        assertEquals("string", type)
    }
    
    @Test
    fun `should batch records correctly`() {
        val records = (1..250).map { i ->
            mapOf(
                "process_id" to "test-$i",
                "value" to i
            )
        }
        
        val batches = batchRecords(records, 100)
        
        assertEquals(3, batches.size)
        assertEquals(100, batches[0].size)
        assertEquals(100, batches[1].size)
        assertEquals(50, batches[2].size)
    }
    
    @Test
    fun `should handle empty records list`() {
        val batches = batchRecords(emptyList(), 100)
        assertTrue(batches.isEmpty())
    }
    
    @Test
    fun `should handle records less than batch size`() {
        val records = (1..50).map { i ->
            mapOf("id" to "test-$i")
        }
        
        val batches = batchRecords(records, 100)
        
        assertEquals(1, batches.size)
        assertEquals(50, batches[0].size)
    }
    
    @Test
    fun `should create dimensions from record keys`() {
        val record = mapOf(
            "process_id" to "123",
            "process_name" to "Test",
            "state" to 1,
            "__time" to "2024-01-01T00:00:00Z"
        )
        
        val dimensions = extractDimensions(record)
        
        assertTrue(dimensions.contains("process_id"))
        assertTrue(dimensions.contains("process_name"))
        assertTrue(dimensions.contains("state"))
        assertFalse(dimensions.contains("__time"))
    }
    
    @Test
    fun `should exclude internal fields from dimensions`() {
        val record = mapOf(
            "process_id" to "123",
            "_datasource" to "test_ds",
            "__time" to "2024-01-01T00:00:00Z"
        )
        
        val dimensions = extractDimensions(record)
        
        assertTrue(dimensions.contains("process_id"))
        assertFalse(dimensions.contains("_datasource"))
        assertFalse(dimensions.contains("__time"))
    }
    
    private fun createTestIngestionSpec(dataSource: String, records: List<Map<String, Any?>>): Map<String, Any?> {
        val dimensions = records.firstOrNull()?.keys
            ?.filter { it != "__time" && !it.startsWith("_") }
            ?.map { key ->
                val value = records.firstOrNull()?.get(key)
                mapOf(
                    "name" to key,
                    "type" to inferDruidType(value)
                )
            } ?: emptyList()
        
        return mapOf(
            "type" to "index_parallel",
            "spec" to mapOf(
                "dataSchema" to mapOf(
                    "dataSource" to dataSource,
                    "timestampSpec" to mapOf(
                        "column" to "__time",
                        "format" to "iso"
                    ),
                    "dimensionsSpec" to mapOf(
                        "dimensions" to dimensions
                    ),
                    "granularitySpec" to mapOf(
                        "type" to "uniform",
                        "segmentGranularity" to "DAY",
                        "queryGranularity" to "NONE"
                    )
                ),
                "ioConfig" to mapOf(
                    "type" to "index_parallel",
                    "inputSource" to mapOf(
                        "type" to "inline",
                        "data" to records.joinToString("\n") { 
                            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                                .writeValueAsString(it) 
                        }
                    ),
                    "inputFormat" to mapOf(
                        "type" to "json"
                    )
                )
            )
        )
    }
    
    private fun inferDruidType(value: Any?): String {
        return when (value) {
            null -> "string"
            is Int, is Long -> "long"
            is Float, is Double -> "double"
            else -> "string"
        }
    }
    
    private fun batchRecords(records: List<Map<String, Any?>>, batchSize: Int): List<List<Map<String, Any?>>> {
        if (records.isEmpty()) return emptyList()
        return records.chunked(batchSize)
    }
    
    private fun extractDimensions(record: Map<String, Any?>): List<String> {
        return record.keys
            .filter { it != "__time" && !it.startsWith("_") }
            .toList()
    }
}
