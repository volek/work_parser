package ru.sber.parser.metastore

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.TreeMap

class SchemaCanonicalizer(
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
) {
    fun canonicalizeSchema(root: JsonNode): ObjectNode {
        val schemaNode = normalizeNode(root)
        val result = JsonNodeFactory.instance.objectNode()
        result.set<JsonNode>("root", schemaNode)
        return result
    }

    fun toCanonicalJson(schemaNode: JsonNode): String = objectMapper.writeValueAsString(schemaNode)

    private fun normalizeNode(node: JsonNode): JsonNode {
        return when {
            node.isObject -> normalizeObject(node as ObjectNode)
            node.isArray -> normalizeArray(node as ArrayNode)
            node.isTextual -> TextNode("string")
            node.isIntegralNumber -> TextNode("integer")
            node.isFloatingPointNumber || node.isBigDecimal || node.isBigInteger -> TextNode("number")
            node.isBoolean -> TextNode("boolean")
            node.isNull -> TextNode("null")
            else -> TextNode("unknown")
        }
    }

    private fun normalizeObject(node: ObjectNode): ObjectNode {
        val fields = TreeMap<String, JsonNode>()
        val iterator = node.fields()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            fields[entry.key] = normalizeNode(entry.value)
        }
        val result = JsonNodeFactory.instance.objectNode()
        fields.forEach { (key, value) -> result.set<JsonNode>(key, value) }
        return result
    }

    private fun normalizeArray(node: ArrayNode): ObjectNode {
        val elementSchemas = node.map { normalizeNode(it) }
            .map { toCanonicalJson(it) to it }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .map { it.second }

        val result = JsonNodeFactory.instance.objectNode()
        val unionArray = JsonNodeFactory.instance.arrayNode()
        elementSchemas.forEach { unionArray.add(it) }
        result.put("type", "array")
        result.set<JsonNode>("items", unionArray)
        return result
    }
}
