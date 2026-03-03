package ru.sber.parser.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FieldClassificationTest {
    
    @Test
    fun `should create default classification`() {
        val classification = FieldClassification.default()
        
        assertNotNull(classification)
        assertTrue(classification.tier1HotColumns.isNotEmpty())
        assertTrue(classification.tier2WarmCategories.isNotEmpty())
        assertTrue(classification.tier3ColdBlobs.isNotEmpty())
    }
    
    @Test
    fun `should include epkId in tier 1 hot columns`() {
        val classification = FieldClassification.default()
        
        assertTrue(classification.tier1HotColumns.any { it.sourcePath == "epkId" })
    }
    
    @Test
    fun `should include fio in tier 1 hot columns`() {
        val classification = FieldClassification.default()
        
        assertTrue(classification.tier1HotColumns.any { it.sourcePath == "fio" })
    }
    
    @Test
    fun `should include caseId in tier 1 hot columns`() {
        val classification = FieldClassification.default()
        
        assertTrue(classification.tier1HotColumns.any { it.sourcePath == "caseId" })
    }
    
    @Test
    fun `should include epkData in tier 2 warm categories`() {
        val classification = FieldClassification.default()
        
        assertTrue(classification.tier2WarmCategories.containsKey("epkData"))
    }
    
    @Test
    fun `should include staticData in tier 2 warm categories`() {
        val classification = FieldClassification.default()
        
        assertTrue(classification.tier2WarmCategories.containsKey("staticData"))
    }
    
    @Test
    fun `should include answerGFL in tier 2 warm categories`() {
        val classification = FieldClassification.default()
        
        assertTrue(classification.tier2WarmCategories.containsKey("answerGFL"))
    }
    
    @Test
    fun `should include nodeInstances in tier 3 cold blobs`() {
        val classification = FieldClassification.default()
        
        assertTrue(classification.tier3ColdBlobs.contains("nodeInstances"))
    }
    
    @Test
    fun `should have correct field mappings`() {
        val classification = FieldClassification.default()
        
        val epkIdMapping = classification.tier1HotColumns.find { it.sourcePath == "epkId" }
        assertNotNull(epkIdMapping)
        assertEquals("var_epkId", epkIdMapping?.columnName)
        assertEquals(FieldType.STRING, epkIdMapping?.fieldType)
    }
    
    @Test
    fun `should define paths for epkData category`() {
        val classification = FieldClassification.default()
        
        val epkDataPaths = classification.tier2WarmCategories["epkData"]
        assertNotNull(epkDataPaths)
        assertTrue(epkDataPaths!!.contains("epkEntity"))
    }
    
    @Test
    fun `should define paths for staticData category`() {
        val classification = FieldClassification.default()
        
        val staticDataPaths = classification.tier2WarmCategories["staticData"]
        assertNotNull(staticDataPaths)
        assertTrue(staticDataPaths!!.isNotEmpty())
    }
    
    @Test
    fun `should have correct number of hot columns`() {
        val classification = FieldClassification.default()
        
        assertTrue(classification.tier1HotColumns.size >= 6)
    }
    
    @Test
    fun `should support all field types`() {
        val types = FieldType.values()
        
        assertTrue(types.contains(FieldType.STRING))
        assertTrue(types.contains(FieldType.LONG))
        assertTrue(types.contains(FieldType.DOUBLE))
        assertTrue(types.contains(FieldType.TIMESTAMP))
    }
    
    @Test
    fun `should be customizable`() {
        val customClassification = FieldClassification(
            tier1HotColumns = listOf(
                FieldMapping("customField", "var_custom", FieldType.STRING)
            ),
            tier2WarmCategories = mapOf(
                "customCategory" to listOf("path1", "path2")
            ),
            tier3ColdBlobs = listOf("customBlob")
        )
        
        assertEquals(1, customClassification.tier1HotColumns.size)
        assertEquals("customField", customClassification.tier1HotColumns.first().sourcePath)
        assertTrue(customClassification.tier2WarmCategories.containsKey("customCategory"))
        assertTrue(customClassification.tier3ColdBlobs.contains("customBlob"))
    }
}
