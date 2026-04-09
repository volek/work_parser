package ru.sber.parser.metastore

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class SchemaExtractor(
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
    private val canonicalizer: SchemaCanonicalizer = SchemaCanonicalizer()
) {
    fun extractCanonicalSchemaJson(rawMessage: String): String {
        val root = objectMapper.readTree(rawMessage)
        return extractCanonicalSchemaJson(root)
    }

    fun extractCanonicalSchemaJson(root: JsonNode): String {
        val canonicalSchema = canonicalizer.canonicalizeSchema(root)
        return canonicalizer.toCanonicalJson(canonicalSchema)
    }
}
