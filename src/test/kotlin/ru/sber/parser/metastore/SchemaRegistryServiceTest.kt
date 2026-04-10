package ru.sber.parser.metastore

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.sber.parser.model.BpmMessage
import java.time.OffsetDateTime

class SchemaRegistryServiceTest {
    @Test
    fun `uses message_id binding when id exists`() {
        val repo = mockk<SchemaMetastoreRepository>()
        every { repo.upsertSchema(any(), any(), any(), any()) } returns SchemaRegistrationResult(1, 1, false)
        every { repo.insertBinding(any(), any(), any(), any(), any(), any()) } just runs

        val service = SchemaRegistryService(repo)
        service.registerMessageSchema(rawMessage = payload("msg-1"), message = bpmMessage("msg-1"), source = "file-a")

        val typeSlot = slot<BindingType>()
        verify(exactly = 1) {
            repo.insertBinding(1, capture(typeSlot), "msg-1", null, "file-a", any())
        }
        assertEquals(BindingType.MESSAGE_ID, typeSlot.captured)
    }

    @Test
    fun `uses fingerprint binding when message id is blank`() {
        val repo = mockk<SchemaMetastoreRepository>()
        every { repo.upsertSchema(any(), any(), any(), any()) } returns SchemaRegistrationResult(1, 1, false)
        every { repo.insertBinding(any(), any(), any(), any(), any(), any()) } just runs

        val service = SchemaRegistryService(repo)
        service.registerMessageSchema(rawMessage = payload(""), message = bpmMessage(""), source = "file-b")

        verify(exactly = 1) {
            repo.insertBinding(1, BindingType.FINGERPRINT, null, match { it?.length == 64 }, "file-b", any())
        }
    }

    private fun payload(id: String): String = """
        {
          "id": "$id",
          "processName": "TestProcess",
          "startDate": "2024-01-15T10:30:00Z",
          "state": 1
        }
    """.trimIndent()

    private fun bpmMessage(id: String): BpmMessage {
        return BpmMessage(
            id = id,
            parentInstanceId = null,
            rootInstanceId = null,
            processId = "proc",
            processDefinitionId = null,
            resourceName = null,
            rootProcessId = null,
            processName = "TestProcess",
            startDate = OffsetDateTime.parse("2024-01-15T10:30:00Z"),
            endDate = null,
            state = 1,
            businessKey = null,
            version = null,
            bamProjectId = null,
            extIds = null,
            error = null,
            moduleId = null,
            engineVersion = null,
            enginePodName = null,
            retryCount = null,
            ownerRole = null,
            idempotencyKey = null,
            operation = null,
            nodeInstances = emptyList(),
            variables = emptyMap(),
            contextSize = null
        )
    }
}
