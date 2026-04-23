package ru.sber.parser.parser.strategy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sber.parser.config.FieldClassification
import ru.sber.parser.model.BpmMessage
import java.time.OffsetDateTime

class DefaultStrategyTest {

    @Test
    fun `routes array paths to dedicated datasource`() {
        val strategy = DefaultStrategy(fieldClassification = FieldClassification.default())
        val grouped = strategy.transformBatch(listOf(testMessage()))

        val main = grouped[strategy.dataSourceName].orEmpty().single()
        val arrayRows = grouped[strategy.additionalDataSources.single()].orEmpty()

        assertNotNull(main["variables.staticData.caseId"])
        assertFalse(main.keys.any { it.contains("[0]") })
        assertTrue(arrayRows.any { it["var_path"] == "epkData.epkEntity.names[0].surname" })
    }

    @Test
    fun `applies array depth limit`() {
        val strategy = DefaultStrategy(
            fieldClassification = FieldClassification.default(),
            arrayMaxDepth = 1
        )
        val grouped = strategy.transformBatch(listOf(testMessage()))
        val arrayRows = grouped[strategy.additionalDataSources.single()].orEmpty()

        assertTrue(arrayRows.any { (it["var_path"] as String).contains("names[0]") })
        assertFalse(arrayRows.any { (it["var_path"] as String).contains("history[0]") })
    }

    @Test
    fun `stores nested array objects as json blob when enabled`() {
        val strategy = DefaultStrategy(
            fieldClassification = FieldClassification.default(),
            arrayObjectJsonBlobEnabled = true
        )
        val grouped = strategy.transformBatch(listOf(testMessage()))
        val arrayRows = grouped[strategy.additionalDataSources.single()].orEmpty()

        val blobRow = arrayRows.find { it["var_path"] == "epkData.epkEntity.names[0]" }
        assertNotNull(blobRow)
        assertEquals("json", blobRow?.get("var_type"))
        assertTrue(blobRow?.get("value_json").toString().contains("surname"))
    }

    private fun testMessage(): BpmMessage {
        return BpmMessage(
            id = "proc-1",
            parentInstanceId = null,
            rootInstanceId = "proc-1",
            processId = "Process_1",
            processDefinitionId = "Process_1:1:abc",
            resourceName = null,
            rootProcessId = null,
            processName = "Process",
            startDate = OffsetDateTime.parse("2026-04-01T10:00:00+03:00"),
            endDate = null,
            state = 1,
            businessKey = null,
            version = 1,
            bamProjectId = null,
            extIds = null,
            error = null,
            moduleId = null,
            engineVersion = null,
            enginePodName = null,
            retryCount = 0,
            ownerRole = null,
            idempotencyKey = null,
            operation = null,
            nodeInstances = emptyList(),
            variables = mapOf(
                "staticData" to mapOf(
                    "caseId" to "case-1",
                    "clientEpkId" to 1
                ),
                "epkData" to mapOf(
                    "epkEntity" to mapOf(
                        "names" to listOf(
                            mapOf("surname" to "Ivanov", "name" to "Ivan")
                        ),
                        "addresses" to listOf(
                            mapOf(
                                "geo" to mapOf(
                                    "history" to listOf(
                                        mapOf("source" to "sync")
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            contextSize = 1
        )
    }
}
