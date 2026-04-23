package ru.sber.parser.metastore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaExtractorTest {
    private val extractor = SchemaExtractor()

    @Test
    fun `same schema with different field order has same hash`() {
        val payload1 = """{"id":"1","variables":{"x":1,"y":"a"}}"""
        val payload2 = """{"variables":{"y":"b","x":2},"id":"2"}"""

        val canonical1 = extractor.extractCanonicalSchemaJson(payload1)
        val canonical2 = extractor.extractCanonicalSchemaJson(payload2)

        assertEquals(canonical1, canonical2)
        assertEquals(SchemaHasher.sha256(canonical1), SchemaHasher.sha256(canonical2))
    }

    @Test
    fun `different payload schemas have different hashes`() {
        val payload1 = """{"id":"1","amount":1}"""
        val payload2 = """{"id":"1","amount":"1"}"""

        val canonical1 = extractor.extractCanonicalSchemaJson(payload1)
        val canonical2 = extractor.extractCanonicalSchemaJson(payload2)

        assertNotEquals(canonical1, canonical2)
        assertNotEquals(SchemaHasher.sha256(canonical1), SchemaHasher.sha256(canonical2))
    }

    @Test
    fun `array schema handles mixed and nullable values`() {
        val payload = """{"items":[1,1.2,null,"x",{"k":true}]}"""
        val canonical = extractor.extractCanonicalSchemaJson(payload)
        assertTrue(canonical.contains(""""type":"array""""))
        assertTrue(canonical.contains(""""items""""))
        assertTrue(canonical.contains(""""null""""))
        assertTrue(canonical.contains(""""number""""))
        assertTrue(canonical.contains(""""string""""))
        assertTrue(canonical.contains(""""k":"boolean""""))
    }
}
