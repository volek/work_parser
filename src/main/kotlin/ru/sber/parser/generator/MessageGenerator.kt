package ru.sber.parser.generator

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random

/**
 * Генератор синтетических BPM-сообщений для тестирования.
 * 
 * Создаёт реалистичные JSON-сообщения, имитирующие выход
 * процессного движка Camunda. Используется для:
 * - Тестирования стратегий парсинга
 * - Нагрузочного тестирования ingestion
 * - Демонстрации работы системы
 * 
 * Генерируемые данные включают:
 * - Метаданные процесса (id, name, state, timestamps)
 * - Экземпляры узлов (startEvent, restTask, userTask, endEvent)
 * - Переменные процесса:
 *   - Идентификаторы (caseId, epkId, ucpId)
 *   - ФИО (реалистичные русские имена)
 *   - staticData (данные обращения)
 *   - epkData (данные клиента из ЕПК)
 *   - tracingHeaders (заголовки трассировки)
 *   - answerGFL (ответы системы ГФЛ)
 * 
 * Особенности генерации:
 * - Рандомизация с seed для воспроизводимости
 * - Связанные данные (processId в узлах соответствует process)
 * - Логичные состояния (endEvent только при state=COMPLETED)
 * - Специфичные переменные для разных типов процессов
 * 
 * Использование:
 * ```kotlin
 * val generator = MessageGenerator()
 * 
 * // Генерация в директорию
 * generator.generateAll(File("./test-messages"), count = 25)
 * 
 * // Генерация одного сообщения
 * val message = generator.generateMessage("MyProcess", 1)
 * ```
 * 
 * @see GeneratorRunner CLI-обёртка для генерации
 * @see BpmMessage модель данных
 */
class MessageGenerator {
    private val logger = LoggerFactory.getLogger(MessageGenerator::class.java)
    
    /** Jackson ObjectMapper с pretty-print для читаемого JSON */
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .configure(SerializationFeature.INDENT_OUTPUT, true)
    
    /** Генератор случайных чисел с seed для воспроизводимости */
    private val random = Random(System.currentTimeMillis())
    private val randomArrayMaxDepth = 5
    
    // === Справочники для генерации реалистичных данных ===
    
    /** Типичные названия BPM-процессов */
    private val processNames = listOf(
        "uvskRemainderReturnCR-Service",
        "uvskStupidsEarlyRehrenment_sub",
        "MassTransferProcessUnif",
        "uvskFraudFin_front",
        "tappeal_p2p_receiver"
    )
    
    private val nodeTypes = listOf(
        "startEvent", "endEvent", "restTask", "userTask", 
        "scriptTask", "subProcess", "exclusiveGateway", "intermediateCatchEvent"
    )
    
    private val surnames = listOf(
        "ИВАНОВ", "ПЕТРОВ", "СИДОРОВ", "КОЗЛОВ", "НОВИКОВ",
        "МОРОЗОВ", "ВОЛКОВ", "СОКОЛОВ", "ПАВЛОВ", "СЕМЕНОВ",
        "ГОЛУБЕВ", "ВИНОГРАДОВ", "БОГДАНОВ", "ВОРОБЬЕВ", "ФЕДОРОВ",
        "МИХАЙЛОВ", "БЕЛЯЕВ", "ТАРАСОВ", "БЕЛОВ", "КОМАРОВ"
    )
    
    private val names = listOf(
        "АЛЕКСАНДР", "СЕРГЕЙ", "АНДРЕЙ", "ДМИТРИЙ", "МАКСИМ",
        "ИВАН", "АЛЕКСЕЙ", "НИКОЛАЙ", "ВЛАДИМИР", "МИХАИЛ",
        "ЕВГЕНИЙ", "КОНСТАНТИН", "ПАВЕЛ", "АРТЕМ", "ДЕНИС",
        "МАРИЯ", "АННА", "ЕЛЕНА", "ОЛЬГА", "НАТАЛЬЯ"
    )
    
    private val patronymics = listOf(
        "АЛЕКСАНДРОВИЧ", "СЕРГЕЕВИЧ", "АНДРЕЕВИЧ", "ДМИТРИЕВИЧ", "МАКСИМОВИЧ",
        "ИВАНОВИЧ", "АЛЕКСЕЕВИЧ", "НИКОЛАЕВИЧ", "ВЛАДИМИРОВИЧ", "МИХАЙЛОВИЧ",
        "АЛЕКСАНДРОВНА", "СЕРГЕЕВНА", "АНДРЕЕВНА", "ДМИТРИЕВНА", "ИВАНОВНА"
    )
    
    private val statusCodes = listOf(
        "IN_PROGRESS", "COMPLETED", "PENDING", "REJECTED", "CANCELLED"
    )
    
    private val considerationResults = listOf(
        "POSTPONED" to "Отложенная обработка",
        "APPROVED" to "Одобрено",
        "REJECTED" to "Отклонено",
        "IN_REVIEW" to "На рассмотрении",
        "ESCALATED" to "Эскалировано"
    )
    
    private val moduleIds = listOf(
        "psi-fl-5g", "ift-5g-rb", "psi-5g-fl-mpz1", "ift-5g-fl-cc", "psi-fl-cc"
    )
    
    /**
     * Генерирует набор тестовых сообщений в указанную директорию.
     * 
     * Распределяет сообщения равномерно по типам процессов.
     * Файлы именуются message_001.json, message_002.json, ...
     * 
     * @param outputDir Директория для сохранения (создаётся при необходимости)
     * @param count Общее количество сообщений для генерации
     */
    fun generateAll(outputDir: File, count: Int = 25) {
        outputDir.mkdirs()
        
        var messageIndex = 1
        
        processNames.forEach { processName ->
            val messagesPerProcess = count / processNames.size + 
                if (messageIndex <= count % processNames.size) 1 else 0
            
            repeat(messagesPerProcess.coerceAtMost(count - messageIndex + 1)) {
                val message = generateMessage(processName, messageIndex)
                val file = File(outputDir, "message_${String.format("%03d", messageIndex)}.json")
                file.writeText(objectMapper.writeValueAsString(message))
                logger.info("Generated: ${file.name}")
                messageIndex++
            }
        }
        
        logger.info("Total messages generated: ${messageIndex - 1}")
    }
    
    /**
     * Генерирует одно BPM-сообщение.
     * 
     * Создаёт полную структуру с:
     * - Метаданными процесса (id, состояние, временные метки)
     * - Иерархией процессов (parent/root для подпроцессов)
     * - Экземплярами узлов
     * - Переменными (специфичными для типа процесса)
     * 
     * @param processName Название процесса
     * @param index Порядковый номер (для логирования)
     * @return Map-представление BPM-сообщения
     */
    fun generateMessage(processName: String, index: Int): Map<String, Any?> {
        val now = OffsetDateTime.now(ZoneOffset.of("+03:00"))
        val startDate = now.minusMinutes(random.nextLong(1, 1440))
        val endDate = if (random.nextBoolean()) startDate.plusMinutes(random.nextLong(1, 60)) else null
        val state = if (endDate != null) 2 else listOf(0, 1, 1, 1).random()
        
        val id = generateProcessId(processName)
        val rootInstanceId = if (random.nextInt(3) == 0) generateProcessId(processName) else id
        val parentInstanceId = if (rootInstanceId != id) rootInstanceId else null
        
        val caseId = UUID.randomUUID().toString()
        val epkId = generateEpkId()
        val ucpId = generateUcpId()
        val fio = generateFio()
        
        return mapOf(
            "id" to id,
            "parentInstanceId" to parentInstanceId,
            "rootInstanceId" to rootInstanceId,
            "processId" to "Process_${generateHex(7)}",
            "processDefinitionId" to "Process_${generateHex(7)}:${random.nextInt(1, 100)}:${UUID.randomUUID()}",
            "resourceName" to null,
            "rootProcessId" to if (parentInstanceId != null) "Process_${generateHex(7)}" else null,
            "processName" to processName,
            "startDate" to startDate.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "endDate" to endDate?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "state" to state,
            "businessKey" to generateBusinessKey(),
            "version" to random.nextInt(1, 100),
            "bamProjectId" to UUID.randomUUID().toString(),
            "extIds" to null,
            "error" to if (state == 3) "NullPointerException at line 42" else null,
            "moduleId" to moduleIds.random(),
            "engineVersion" to "D-5.5.0-${random.nextInt(3000, 4000)}",
            "enginePodName" to "bpmx-engine-${moduleIds.random()}-${generateHex(10)}",
            "retryCount" to random.nextInt(0, 5),
            "ownerRole" to if (random.nextBoolean()) "admin" else null,
            "idempotencyKey" to null,
            "operation" to null,
            // В корне сообщения больше нет массивов: nodeInstances переносим в ветку variables.
            "nodeInstances" to null,
            "variables" to generateVariables(processName, caseId, epkId, ucpId, fio, startDate, index, state),
            "contextSize" to random.nextLong(5000, 200000)
        )
    }
    
    // === Генерация узлов процесса ===
    
    /**
     * Генерирует список экземпляров узлов для процесса.
     * 
     * Логика генерации:
     * - Всегда начинается с startEvent
     * - Добавляются промежуточные узлы (tasks, gateways)
     * - EndEvent только если процесс завершён (state=2)
     * - Временные метки увеличиваются последовательно
     * 
     * @param startDate Время старта процесса
     * @param processState Состояние процесса (влияет на наличие endEvent)
     * @return Список Map-представлений узлов
     */
    private fun generateNodeInstances(startDate: OffsetDateTime, processState: Int): List<Map<String, Any?>> {
        val nodeCount = random.nextInt(2, 10)
        val nodes = mutableListOf<Map<String, Any?>>()
        var currentTime = startDate
        
        nodes.add(createNodeInstance(
            nodeName = "Start",
            nodeType = "startEvent",
            state = 4,
            triggerTime = currentTime,
            leaveTime = currentTime,
            creationOrder = 1
        ))
        currentTime = currentTime.plusSeconds(random.nextLong(1, 10))
        
        for (i in 2 until nodeCount) {
            val nodeType = listOf("restTask", "scriptTask", "userTask", "subProcess", "exclusiveGateway").random()
            val nodeState = when {
                processState == 2 -> 4
                i == nodeCount - 1 && processState == 1 -> 0
                else -> listOf(0, 4, 4, 4).random()
            }
            val leaveTime = if (nodeState == 4) currentTime.plusSeconds(random.nextLong(1, 30)) else null
            
            nodes.add(createNodeInstance(
                nodeName = generateNodeName(nodeType, i),
                nodeType = nodeType,
                state = nodeState,
                triggerTime = currentTime,
                leaveTime = leaveTime,
                creationOrder = i
            ))
            
            if (leaveTime != null) {
                currentTime = leaveTime
            }
        }
        
        if (processState == 2) {
            nodes.add(createNodeInstance(
                nodeName = "End",
                nodeType = "endEvent",
                state = 4,
                triggerTime = currentTime,
                leaveTime = currentTime,
                creationOrder = nodeCount
            ))
        }
        
        return nodes
    }
    
    /**
     * Создаёт структуру экземпляра узла.
     * 
     * @param nodeName Отображаемое имя узла
     * @param nodeType BPMN-тип (startEvent, restTask, userTask, etc.)
     * @param state Состояние: 0=waiting, 1=active, 4=completed
     * @param triggerTime Время активации
     * @param leaveTime Время завершения (null если активен)
     * @param creationOrder Порядок создания
     * @return Map-представление NodeInstance
     */
    private fun createNodeInstance(
        nodeName: String,
        nodeType: String,
        state: Int,
        triggerTime: OffsetDateTime,
        leaveTime: OffsetDateTime?,
        creationOrder: Int
    ): Map<String, Any?> {
        val nodeId = "Activity_${generateHex(7)}"
        return mapOf(
            "id" to UUID.randomUUID().toString(),
            "nodeId" to nodeId,
            "nodeDefinitionId" to nodeId,
            "nodeName" to nodeName,
            "nodeType" to nodeType,
            "error" to null,
            "state" to state,
            "calledProcessInstanceIds" to null,
            "retries" to emptyList<Any>(),
            "htmTaskId" to if (nodeType == "userTask") UUID.randomUUID().toString() else null,
            "triggerTime" to triggerTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "leaveTime" to leaveTime?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "triggerNodeInstanceId" to null,
            "creationOrder" to creationOrder
        )
    }
    
    /** Генерирует реалистичное имя узла по его типу */
    private fun generateNodeName(nodeType: String, index: Int): String {
        return when (nodeType) {
            "restTask" -> listOf(
                "getEpkData", "setCaseStage", "createInteraction", "sendNotification",
                "processGfl", "validateData", "updateStatus", "getCustomerInfo"
            ).random()
            "scriptTask" -> listOf(
                "parseResponse", "validateInput", "calculateAmount", "formatOutput",
                "checkConditions", "transformData", "prepareRequest"
            ).random()
            "userTask" -> listOf(
                "ARM", "ManualReview", "ApprovalTask", "DataEntry",
                "DocumentVerification", "CustomerCallback"
            ).random()
            "subProcess" -> listOf(
                "ProcessEpk", "ProcessGfl", "FraudCheck", "RiskAssessment",
                "NotificationFlow", "ValidationSubProcess"
            ).random()
            "exclusiveGateway" -> "Gateway_$index"
            else -> "Node_$index"
        }
    }
    
    // === Генерация переменных процесса ===
    
    /**
     * Генерирует набор переменных процесса.
     * 
     * Базовые переменные (для всех процессов):
     * - caseId, epkId, ucpId — идентификаторы
     * - fio — ФИО клиента
     * - staticData — данные обращения
     * - epkData — данные из ЕПК
     * - tracingHeaders — заголовки трассировки
     * 
     * Специфичные переменные добавляются в зависимости
     * от типа процесса (Fraud, Transfer, etc.).
     * 
     * Часть сообщений дополнительно получает переменные-массивы
     * разной длины и с элементами разных типов (строки, числа, boolean, объекты, вложенные массивы).
     * 
     * @param messageIndex Порядковый номер сообщения (для вариативности массивов)
     * @return Map переменных процесса
     */
    private fun generateVariables(
        processName: String,
        caseId: String,
        epkId: String,
        ucpId: String,
        fio: String,
        startDate: OffsetDateTime,
        messageIndex: Int,
        processState: Int
    ): Map<String, Any?> {
        val variables = mutableMapOf<String, Any?>()
        
        variables["caseId"] = caseId
        variables["epkId"] = epkId
        variables["ucpId"] = ucpId
        variables["fio"] = fio
        variables["globalInstanceId"] = UUID.randomUUID().toString()
        variables["INTERACTION_ID"] = generateInteractionId(startDate)
        variables["INTERACTION_DATE"] = startDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        variables["theme"] = listOf("incorrectCommunication", "fraudAlert", "refundRequest", "complaint").random()
        variables["result"] = ""
        variables["status"] = statusCodes.random()
        
        variables["staticData"] = generateStaticData(caseId, epkId, startDate)
        variables["epkData"] = generateEpkData(ucpId, fio)
        variables["tracingHeaders"] = generateTracingHeaders()
        variables["startAttributes"] = generateStartAttributes(caseId)
        // Переносим nodeInstances в ветку variables, чтобы массивов не было в корне сообщения.
        variables["nodeInstances"] = generateNodeInstances(startDate, processState)
        
        if (processName.contains("Fraud") || random.nextInt(3) == 0) {
            variables["answerGFL"] = generateAnswerGFL()
        }
        
        if (processName.contains("Transfer") || random.nextInt(4) == 0) {
            variables["unifMetadata"] = "${random.nextInt(10, 99)}_${random.nextInt(1000, 9999)}_1"
            variables["unifObjectId"] = UUID.randomUUID().toString()
            variables["rosterId"] = UUID.randomUUID().toString()
        }
        
        addProcessSpecificVariables(variables, processName)
        addAdditionalArrayVariables(variables, startDate, messageIndex)
        
        // Часть сообщений содержит дополнительные переменные-массивы разной длины и типов
        if (messageIndex % 3 != 1) { // ~2/3 сообщений с массивами
            addRandomArrayVariables(variables, messageIndex)
        }
        
        return variables
    }
    
    /** Имена переменных, которые могут быть массивами с разными данными */
    private val arrayVariableNames = listOf(
        "documentIds", "amounts", "tags", "flags", "metadataList", "references",
        "codes", "values", "items", "history", "recipientIds", "channelCodes",
        "statusHistory", "approvalStages", "commentIds", "attachmentIds", "notifyList"
    )
    
    /**
     * Добавляет в variables несколько переменных-массивов разной длины и с элементами разных типов.
     */
    private fun addRandomArrayVariables(variables: MutableMap<String, Any?>, messageIndex: Int) {
        val isHeavyArrayMessage = messageIndex % 10 == 0
        val count = if (isHeavyArrayMessage) random.nextInt(10, 16) else random.nextInt(2, 6)
        val namesPool = if (count > arrayVariableNames.size) {
            buildList {
                var sequence = 0
                while (size < count) {
                    arrayVariableNames.shuffled(random).forEach { base ->
                        if (size < count) {
                            add("${base}_$sequence")
                            sequence++
                        }
                    }
                }
            }
        } else {
            arrayVariableNames.shuffled(random).take(count)
        }
        (0 until count).forEach { i ->
            val name = namesPool[i]
            val minLen = if (isHeavyArrayMessage) listOf(1, 2, 3).random() else listOf(0, 0, 1, 2).random()
            val maxLen = if (isHeavyArrayMessage) listOf(12, 20, 30).random() else listOf(3, 5, 8, 12, 20).random()
            variables[name] = generateRandomArray(minLen, maxLen, depth = 0, maxDepth = randomArrayMaxDepth)
        }
    }
    
    /**
     * Генерирует массив случайной длины с элементами разных типов:
     * String, Int, Long, Double, Boolean, null, Map, вложенный List.
     * @param depth Глубина вложенности (для ограничения рекурсии при вложенных списках)
     */
    private fun generateRandomArray(
        minLen: Int,
        maxLen: Int,
        depth: Int = 0,
        maxDepth: Int = randomArrayMaxDepth
    ): List<Any?> {
        val len = random.nextInt(minLen, maxLen.coerceAtLeast(minLen + 1))
        return (0 until len).map { generateRandomValue(depth, maxDepth) }
    }
    
    /**
     * Генерирует одно значение случайного типа.
     * @param depth Текущая глубина вложенности (для ограничения рекурсии)
     */
    private fun generateRandomValue(depth: Int, maxDepth: Int): Any? {
        if (depth >= maxDepth) {
            return listOf(
                random.nextInt(-1000, 1000),
                random.nextDouble(-1000.0, 1000.0),
                listOf("code_${generateHex(4)}", "val_${random.nextInt(100)}", "").random().takeIf { it.isNotEmpty() },
                random.nextBoolean()
            ).random()
        }
        when (random.nextInt(0, 12)) {
            0 -> return null
            1 -> return random.nextInt(-10000, 10000)
            2 -> return random.nextLong(-1000000L, 1000000L)
            3 -> return random.nextDouble(0.0, 100000.0)
            4 -> return random.nextBoolean()
            5, 6 -> return listOf(
                UUID.randomUUID().toString(),
                "code_${generateHex(6)}",
                statusCodes.random(),
                listOf("RUB", "USD", "EUR").random(),
                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            ).random()
            7, 8 -> return mapOf(
                "id" to (if (random.nextBoolean()) UUID.randomUUID().toString() else random.nextLong(1, 999999)),
                "code" to "c_${generateHex(4)}",
                "value" to (if (random.nextBoolean()) random.nextInt(1, 100) else random.nextDouble(0.0, 100.0)),
                "active" to random.nextBoolean()
            )
            9, 10 -> return generateRandomArray(0, 4, depth + 1, maxDepth).takeIf { it.isNotEmpty() }
            11 -> return mapOf(
                "key" to "k_${generateHex(3)}",
                "nested" to generateRandomValue(depth + 1, maxDepth)
            )
            else -> return random.nextInt(0, 100)
        }
    }
    
    /** Генерирует блок staticData — данные обращения клиента */
    private fun generateStaticData(caseId: String, epkId: String, startDate: OffsetDateTime): Map<String, Any?> {
        val (resultCode, resultName) = considerationResults.random()
        return mapOf(
            "classifierVersion" to random.nextInt(1, 50),
            "caseId" to caseId,
            "classifierMasterId" to UUID.randomUUID().toString(),
            "classifierParentMasterId" to UUID.randomUUID().toString(),
            "casePublicId" to generateCasePublicId(startDate),
            "clientEpkId" to (epkId.toLongOrNull() ?: random.nextLong(1000000000000000000, 2000000000000000000)),
            "statusCode" to statusCodes.random(),
            "statusName" to null,
            "crmAppealNum" to null,
            "considerationResultCode" to resultCode,
            "considerationResultName" to resultName,
            "crmRequestId" to null,
            "lastUpdateTime" to startDate.plusMinutes(random.nextLong(1, 30)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "closedTime" to null,
            "registrationTime" to startDate.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "publicDescription" to "Тестовое обращение #${random.nextInt(1000, 9999)}",
            "caseEcmFolder" to null,
            "pprbCase" to random.nextBoolean(),
            "contactSettingsStart" to "09:00",
            "contactSettingsFinish" to "20:00",
            "contactSettingsTimeZone" to "+03:00",
            "expirationTime" to startDate.plusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "initialExpirationTime" to startDate.plusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "initialInteractionId" to UUID.randomUUID().toString(),
            // answers теперь содержит массив сложных структур с дополнительной вложенностью
            "answers" to listOf(
                mapOf(
                    "channel" to listOf("WEB", "MOBILE", "BRANCH").random(),
                    "codes" to listOf(resultCode, "INFO_${generateHex(3)}"),
                    "details" to mapOf(
                        "operator" to "OP_${generateHex(4)}",
                        "tags" to listOf("test", "synthetic", "case_$caseId"),
                        "history" to (0..random.nextInt(0, 3)).map {
                            mapOf(
                                "ts" to startDate.plusMinutes(random.nextLong(1, 120))
                                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                                "status" to statusCodes.random()
                            )
                        }
                    )
                )
            )
        )
    }
    
    /** Генерирует блок epkData — данные клиента из ЕПК (Единая Платформа Клиента) */
    private fun generateEpkData(ucpId: String, fio: String): Map<String, Any?> {
        val fioParts = fio.split(" ")
        return mapOf(
            "epkEntity" to mapOf(
                "ucpId" to ucpId,
                "version" to random.nextInt(1, 100),
                "names" to listOf(
                    mapOf(
                        "surname" to fioParts.getOrElse(0) { surnames.random() },
                        "name" to fioParts.getOrElse(1) { names.random() },
                        "patronymic" to fioParts.getOrElse(2) { patronymics.random() }
                    )
                ),
                "clientStatus" to 1,
                "gender" to listOf(1, 2).random(),
                "clientGroups" to (1..random.nextInt(1, 5)).map { random.nextInt(1, 3000) },
                "phoneNumbers" to (1..random.nextInt(1, 4)).map { generatePhoneNumber() },
                "electronicAddresses" to emptyList<Any>(),
                "deathDate" to null,
                "birthDate" to generateBirthDate(),
                "identifications" to listOf(generateIdentification()),
                // Дополнительный массив адресов с вложенными массивами и объектами
                "addresses" to (1..random.nextInt(1, 3)).map { idx ->
                    mapOf(
                        "type" to listOf("REGISTRATION", "RESIDENCE", "WORK").random(),
                        "region" to random.nextInt(1, 80),
                        "city" to "City_$idx",
                        "street" to "Street_${generateHex(3)}",
                        "house" to "${random.nextInt(1, 200)}",
                        "flat" to if (random.nextBoolean()) random.nextInt(1, 300) else null,
                        "geo" to mapOf(
                            "lat" to random.nextDouble(54.0, 56.0),
                            "lon" to random.nextDouble(36.0, 39.0),
                            "history" to (0..random.nextInt(0, 2)).map {
                                mapOf(
                                    "source" to listOf("ECP", "MANUAL", "SYNC").random(),
                                    "updatedAt" to OffsetDateTime.now()
                                        .minusDays(random.nextLong(1, 365))
                                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                                )
                            }
                        )
                    )
                }
            ),
            "customerKnowledge" to (0..random.nextInt(0, 3)).map {
                mapOf(
                    "value" to random.nextInt(30000, 50000).toString(),
                    "id" to random.nextLong(1000000000000000000, 2000000000000000000)
                )
            }
        )
    }

    /**
     * Добавляет дополнительные массивы с глубокой вложенностью в переменные процесса.
     * Эти структуры предназначены для тестирования парсера на сложных JSON-ветках.
     */
    private fun addAdditionalArrayVariables(
        variables: MutableMap<String, Any?>,
        startDate: OffsetDateTime,
        messageIndex: Int
    ) {
        // Массив операций по процессу с вложенными массивами шагов и атрибутов.
        variables["operations"] = (1..random.nextInt(1, 4)).map { opIndex ->
            mapOf(
                "operationId" to "OP_${opIndex}_${generateHex(4)}",
                "type" to listOf("CREATE", "UPDATE", "CLOSE").random(),
                "createdAt" to startDate
                    .plusMinutes(random.nextLong(0, 240))
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "steps" to (1..random.nextInt(1, 5)).map { stepIndex ->
                    mapOf(
                        "stepId" to "STEP_${opIndex}_$stepIndex",
                        "status" to statusCodes.random(),
                        "attempts" to (0..random.nextInt(0, 2)).map {
                            mapOf(
                                "attempt" to it,
                                "result" to listOf("OK", "WARN", "ERROR").random(),
                                "ts" to startDate.plusMinutes(random.nextLong(0, 480))
                                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                            )
                        }
                    )
                }
            )
        }

        // Массив лимитов с вложенными массивами правил и значений.
        variables["limits"] = listOf("DAILY", "MONTHLY").map { period ->
            mapOf(
                "period" to period,
                "currency" to "RUB",
                "rules" to (1..random.nextInt(1, 3)).map { ruleIndex ->
                    mapOf(
                        "ruleId" to "LIM_${period}_$ruleIndex",
                        "thresholds" to listOf(
                            mapOf("type" to "WARN", "value" to random.nextInt(10_000, 50_000)),
                            mapOf("type" to "BLOCK", "value" to random.nextInt(50_001, 200_000))
                        ),
                        "channels" to listOf("WEB", "MOBILE", "ATM").shuffled().take(random.nextInt(1, 3))
                    )
                }
            )
        }

        // Массив событий аудита с глубоко вложенными деталями.
        variables["auditTrail"] = (1..random.nextInt(1, 5)).map { _ ->
            mapOf(
                "eventId" to "EVT_${generateHex(6)}",
                "eventType" to listOf("CREATE", "STATE_CHANGE", "NOTIFY", "ROUTE").random(),
                "timestamp" to startDate.plusMinutes(random.nextLong(0, 720))
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "actor" to mapOf(
                    "id" to "USR_${generateHex(5)}",
                    "roles" to listOf("OPERATOR", "SUPPORT", "SYSTEM").shuffled()
                        .take(random.nextInt(1, 3))
                ),
                "details" to mapOf(
                    "oldState" to statusCodes.random(),
                    "newState" to statusCodes.random(),
                    "attributes" to (0..random.nextInt(0, 4)).map {
                        mapOf(
                            "key" to "attr_${generateHex(3)}",
                            "values" to (0..random.nextInt(1, 3)).map { "v_${generateHex(2)}" }
                        )
                    }
                )
            )
        }

        // Добавляем контролируемый профиль глубины массивов, чтобы стабильно покрывать
        // разные степени вложенности в тестах парсинга.
        val targetDepth = (messageIndex % randomArrayMaxDepth) + 1
        variables["arrayDepthProfile"] = generateDepthProfileArray(targetDepth, startDate)
    }

    private fun generateDepthProfileArray(targetDepth: Int, startDate: OffsetDateTime): List<Any?> {
        fun nested(level: Int): Any? {
            if (level >= targetDepth) {
                return mapOf(
                    "level" to level,
                    "timestamp" to startDate.plusMinutes(level.toLong()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "payload" to mapOf(
                        "code" to "DEPTH_${targetDepth}_$level",
                        "active" to true
                    )
                )
            }
            return listOf(
                mapOf("level" to level, "kind" to "object"),
                nested(level + 1)
            )
        }
        return listOf(
            mapOf("targetDepth" to targetDepth, "rootKind" to "array"),
            nested(1)
        )
    }
    
    /** Генерирует структуру телефонного номера с метаданными */
    private fun generatePhoneNumber(): Map<String, Any?> {
        val phoneNum = "+7 (${random.nextInt(900, 999)}) ${random.nextInt(1000000, 9999999)}"
        return mapOf(
            "phoneNumber" to phoneNum,
            "startDate" to OffsetDateTime.now().minusYears(random.nextLong(1, 5)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "endDate" to if (random.nextInt(5) == 0) OffsetDateTime.now().minusDays(random.nextLong(1, 365)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) else null,
            "updateDateTime" to OffsetDateTime.now().minusDays(random.nextLong(1, 365)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "contactStatus" to 1,
            "timeAvailabilityFrom" to null,
            "timeAvailabilityTo" to null,
            "usageType" to listOf(14, 15).random(),
            "flags" to (0..random.nextInt(0, 5)).map { random.nextInt(10, 100) },
            "flagList" to emptyList<Any>(),
            "phoneQualityCode" to 511,
            "region" to random.nextInt(1, 100)
        )
    }
    
    /** Генерирует данные документа, удостоверяющего личность */
    private fun generateIdentification(): Map<String, Any?> {
        return mapOf(
            "documentSeries" to "${random.nextInt(10, 99)} ${random.nextInt(10, 99)}",
            "documentNumber" to random.nextInt(100000, 999999).toString(),
            "documentTypeCode" to listOf(17, 177).random(),
            "issuedDate" to OffsetDateTime.now().minusYears(random.nextLong(1, 20)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "issuerCode" to "${random.nextInt(100, 999)}-${random.nextInt(100, 999)}",
            "issuedByOrganization" to "ОВД района ${listOf("Центральный", "Северный", "Южный", "Восточный", "Западный").random()}"
        )
    }
    
    /** Генерирует заголовки распределённой трассировки (B3 format) */
    private fun generateTracingHeaders(): Map<String, String> {
        return mapOf(
            "x-request-id" to UUID.randomUUID().toString(),
            "x-b3-parentspanid" to generateHex(16),
            "x-b3-spanid" to generateHex(16),
            "x-b3-traceid" to generateHex(32),
            "x-b3-sampled" to "1"
        )
    }
    
    /** Генерирует атрибуты старта процесса (параметры карт) */
    private fun generateStartAttributes(caseId: String): Map<String, Any?> {
        return mapOf(
            "caseId" to caseId,
            "attributes" to listOf(
                mapOf("inputSumm" to random.nextInt(100, 10000).toString()),
                mapOf(
                    "inputCC" to mapOf(
                        "DPAN" to generateDpan(),
                        "endDate" to OffsetDateTime.now().plusYears(random.nextLong(1, 5)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                        "accountNum" to random.nextLong(10000000000000000, 99999999999999999).toString(),
                        "processing" to "urn:sbrfsystems:99-way"
                    )
                ),
                mapOf(
                    "inputDC" to mapOf(
                        "DPAN" to generateDpan(),
                        "endDate" to OffsetDateTime.now().plusYears(random.nextLong(1, 5)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                        "accountNum" to random.nextLong(10000000000000000, 99999999999999999).toString(),
                        "processing" to "urn:sbrfsystems:99-pci:tw"
                    )
                )
            )
        )
    }
    
    /** Генерирует ответ от системы ГФЛ (банковские продукты) */
    private fun generateAnswerGFL(): Map<String, Any?> {
        return mapOf(
            "RqTm" to OffsetDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            "RqUID" to generateHex(32),
            "Status" to mapOf(
                "StatusCode" to 0,
                "StatusDesc" to "SUCCESS",
                "AdditionalStatus" to emptyList<Any>()
            ),
            "OperUID" to generateHex(32),
            "AcctType" to emptyList<Any>(),
            "PankAcctRec" to (1..random.nextInt(1, 5)).map { generatePankAcctRec() }
        )
    }
    
    /** Генерирует запись банковского счёта (структура PankAcctRec) */
    private fun generatePankAcctRec(): Map<String, Any?> {
        return mapOf(
            "Mord" to mapOf(
                "SPName" to "urn:sbrfsystems:99-way",
                "AcctBal" to listOf(
                    mapOf("CurAmt" to random.nextDouble(0.0, 500000.0), "AcctCur" to "RUB", "BalType" to "Avail"),
                    mapOf("CurAmt" to random.nextDouble(0.0, 100000.0), "AcctCur" to "RUB", "BalType" to "CR_LIMIT"),
                    mapOf("CurAmt" to random.nextDouble(0.0, 500000.0), "AcctCur" to "RUB", "BalType" to "OWN_BALANCE"),
                    mapOf("CurAmt" to 0.0, "AcctCur" to "RUB", "BalType" to "Debt")
                ),
                "MordAcctId" to mapOf(
                    "EndDt" to OffsetDateTime.now().plusYears(random.nextLong(1, 5)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                    "AcctId" to "408178${random.nextLong(10000000000000, 99999999999999)}",
                    "AcctCur" to "RUB",
                    "MordNum" to generateHex(16).uppercase(),
                    "MordType" to listOf("OV", "CC", "DC").random(),
                    "SystemId" to "urn:sbrfsystems:99-way",
                    "MordLevel" to listOf("VG", "ST", "PR").random()
                ),
                "PankAcctStatus" to mapOf(
                    "StatusDesc" to "+ - КАРТОЧКА ОТКРЫТА",
                    "StatusClass" to "Valid",
                    "PankAcctStatusCode" to "+"
                )
            )
        )
    }
    
    /**
     * Добавляет специфичные переменные в зависимости от типа процесса.
     * 
     * - uvsk: таймеры, URL сервисов
     * - Transfer: параметры unif
     * - Fraud: оценка риска
     * - tappeal: тип обращения, приоритет
     */
    private fun addProcessSpecificVariables(variables: MutableMap<String, Any?>, processName: String) {
        when {
            processName.contains("uvsk") -> {
                variables["uvsk_restTaskTimer"] = "PT30S"
                variables["uvskCLTV_taskTimer"] = "PT30S"
                variables["uvskVis_armStageDurationTimer"] = "P3D"
                variables["expTimer"] = "P3D"
                variables["armTimer"] = "PT73H"
                variables["taskTimer"] = "PT30S"
                variables["universalStage"] = "OKS_US"
                variables["urlGFL"] = "http://gfl-service:8080"
                variables["urlEpk"] = "http://bpm-gateway:8080"
            }
            processName.contains("Transfer") -> {
                variables["durationUnifResult"] = "PT1H"
                variables["unifPageSize"] = 1000
                variables["externalIds"] = null
            }
            processName.contains("Fraud") -> {
                variables["fraudScore"] = random.nextDouble(0.0, 100.0)
                variables["riskLevel"] = listOf("LOW", "MEDIUM", "HIGH", "CRITICAL").random()
                variables["alertType"] = listOf("SUSPICIOUS_TRANSACTION", "IDENTITY_THEFT", "ACCOUNT_TAKEOVER").random()
            }
            processName.contains("tappeal") -> {
                variables["appealType"] = listOf("GCT", "COMPLAINT", "REQUEST").random()
                variables["priority"] = listOf(1, 2, 3).random()
                variables["slaDeadline"] = OffsetDateTime.now().plusDays(random.nextLong(1, 7)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            }
        }
    }
    
    // === Утилиты генерации идентификаторов ===
    
    /** Генерирует ID процесса с префиксом модуля */
    private fun generateProcessId(processName: String): String {
        val prefix = when {
            processName.contains("uvsk") && processName.contains("sub") -> "ift-5g-rb"
            processName.contains("Transfer") -> "psi-5g-fl-mpz1"
            else -> moduleIds.random()
        }
        return "${prefix}_${UUID.randomUUID()}"
    }
    
    /** Генерирует бизнес-ключ формата YYMMDD + 7 цифр */
    private fun generateBusinessKey(): String {
        val date = OffsetDateTime.now()
        return "${date.format(DateTimeFormatter.ofPattern("yyMMdd"))}${random.nextInt(1000000, 9999999)}"
    }
    
    /** Генерирует ID взаимодействия формата YYMMDD-7000-NNNNNN */
    private fun generateInteractionId(date: OffsetDateTime): String {
        return "${date.format(DateTimeFormatter.ofPattern("yyMMdd"))}-7000-${String.format("%06d", random.nextInt(1, 999999))}"
    }
    
    /** Генерирует публичный ID обращения формата YYMMDD7000NNNNNN */
    private fun generateCasePublicId(date: OffsetDateTime): String {
        return "${date.format(DateTimeFormatter.ofPattern("yyMMdd"))}7000${String.format("%06d", random.nextInt(1, 999999))}"
    }
    
    /** Генерирует 19-значный ID клиента ЕПК */
    private fun generateEpkId(): String {
        return random.nextLong(1000000000000000000, 2000000000000000000).toString()
    }
    
    /** Генерирует 19-значный UCP ID */
    private fun generateUcpId(): String {
        return random.nextLong(1000000000000000000, 2000000000000000000).toString()
    }
    
    /** Генерирует ФИО из справочников */
    private fun generateFio(): String {
        return "${surnames.random()} ${names.random()} ${patronymics.random()}"
    }
    
    /** Генерирует дату рождения (возраст 20-80 лет) */
    private fun generateBirthDate(): String {
        return OffsetDateTime.now()
            .minusYears(random.nextLong(20, 80))
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
    
    /** Генерирует DPAN (токен карты) — 16 цифр */
    private fun generateDpan(): String {
        return random.nextLong(1000000000000000, 9999999999999999).toString()
    }
    
    /** Генерирует случайную hex-строку заданной длины */
    private fun generateHex(length: Int): String {
        val chars = "0123456789abcdef"
        return (1..length).map { chars.random() }.joinToString("")
    }
}
