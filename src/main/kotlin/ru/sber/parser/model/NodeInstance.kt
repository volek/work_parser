package ru.sber.parser.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.OffsetDateTime

/**
 * Экземпляр узла (node) в BPM-процессе.
 * 
 * Представляет выполненный или выполняющийся элемент BPMN-диаграммы:
 * события, задачи, шлюзы и подпроцессы.
 * 
 * Типы узлов (nodeType):
 * ┌─────────────────────────────────────────────────────────────┐
 * │ События:                                                    │
 * │   startEvent          — начало процесса                     │
 * │   endEvent            — завершение процесса                 │
 * │   intermediateCatchEvent — промежуточное событие ожидания   │
 * ├─────────────────────────────────────────────────────────────┤
 * │ Задачи:                                                     │
 * │   restTask            — вызов REST API                      │
 * │   userTask            — ручная задача пользователя          │
 * │   scriptTask          — выполнение скрипта                  │
 * ├─────────────────────────────────────────────────────────────┤
 * │ Управление потоком:                                         │
 * │   exclusiveGateway    — XOR-шлюз (один путь)                │
 * │   subProcess          — вложенный подпроцесс                │
 * └─────────────────────────────────────────────────────────────┘
 * 
 * Жизненный цикл узла:
 *   WAITING (0) → ACTIVE (1) → COMPLETED (4) или FAILED (3)
 * 
 * @property id Уникальный UUID экземпляра узла
 * @property nodeId BPMN-идентификатор узла (Activity_xxx)
 * @property nodeDefinitionId Версионированный ID определения узла
 * @property nodeName Человекочитаемое имя узла из BPMN
 * @property nodeType Тип узла (startEvent, userTask, и т.д.)
 * @property error Текст ошибки при state=3
 * @property state Код состояния: 0=waiting, 1=active, 3=failed, 4=completed
 * @property calledProcessInstanceIds ID вызванных подпроцессов
 * @property retries Список попыток повторного выполнения
 * @property htmTaskId ID задачи в Human Task Manager
 * @property triggerTime Время входа токена в узел
 * @property leaveTime Время выхода токена из узла
 * @property triggerNodeInstanceId ID узла-триггера
 * @property creationOrder Порядок создания узла в процессе
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NodeInstance(
    val id: String,
    val nodeId: String,
    val nodeDefinitionId: String?,
    val nodeName: String?,
    val nodeType: String,
    val error: String?,
    val state: Int,
    val calledProcessInstanceIds: List<String>?,
    val retries: List<RetryInfo>?,
    val htmTaskId: String?,
    val triggerTime: OffsetDateTime?,
    val leaveTime: OffsetDateTime?,
    val triggerNodeInstanceId: String?,
    val creationOrder: Int?
) {
    companion object {
        /** Узел ожидает выполнения (например, таймер) */
        const val STATE_WAITING = 0
        
        /** Узел выполняется в данный момент */
        const val STATE_ACTIVE = 1
        
        /** Узел успешно завершён */
        const val STATE_COMPLETED = 4
        
        /** Узел завершился с ошибкой */
        const val STATE_FAILED = 3
        
        // BPMN типы событий
        /** Стартовое событие — точка входа в процесс */
        const val TYPE_START_EVENT = "startEvent"
        
        /** Конечное событие — точка выхода из процесса */
        const val TYPE_END_EVENT = "endEvent"
        
        // BPMN типы задач
        /** REST-задача — вызов внешнего API */
        const val TYPE_REST_TASK = "restTask"
        
        /** Пользовательская задача — требует ручного выполнения */
        const val TYPE_USER_TASK = "userTask"
        
        /** Скриптовая задача — выполнение встроенного скрипта */
        const val TYPE_SCRIPT_TASK = "scriptTask"
        
        // BPMN типы управления потоком
        /** Вложенный подпроцесс */
        const val TYPE_SUB_PROCESS = "subProcess"
        
        /** XOR-шлюз — эксклюзивный выбор одного пути */
        const val TYPE_EXCLUSIVE_GATEWAY = "exclusiveGateway"
        
        /** Промежуточное событие ожидания (таймер, сообщение) */
        const val TYPE_INTERMEDIATE_CATCH_EVENT = "intermediateCatchEvent"
    }
    
    // Методы проверки состояния
    /** Проверяет, успешно ли завершён узел */
    fun isCompleted(): Boolean = state == STATE_COMPLETED
    
    /** Проверяет, ожидает ли узел выполнения */
    fun isWaiting(): Boolean = state == STATE_WAITING
    
    /** Проверяет, выполняется ли узел в данный момент */
    fun isActive(): Boolean = state == STATE_ACTIVE
    
    /** Проверяет, завершился ли узел с ошибкой */
    fun isFailed(): Boolean = state == STATE_FAILED
    
    // Методы проверки типа узла
    /** Является ли узел стартовым событием */
    fun isStartEvent(): Boolean = nodeType == TYPE_START_EVENT
    
    /** Является ли узел конечным событием */
    fun isEndEvent(): Boolean = nodeType == TYPE_END_EVENT
    
    /** Является ли узел REST-задачей */
    fun isRestTask(): Boolean = nodeType == TYPE_REST_TASK
    
    /** Является ли узел пользовательской задачей */
    fun isUserTask(): Boolean = nodeType == TYPE_USER_TASK
    
    /** Является ли узел подпроцессом */
    fun isSubProcess(): Boolean = nodeType == TYPE_SUB_PROCESS
}

/**
 * Информация о попытке повторного выполнения узла.
 * 
 * При сбое некоторые типы узлов (особенно REST-задачи)
 * могут быть повторно выполнены. Каждая попытка записывается
 * в список retries родительского NodeInstance.
 * 
 * @property attempt Номер попытки (1, 2, 3, ...)
 * @property error Текст ошибки данной попытки
 * @property timestamp Время попытки (ISO-8601)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RetryInfo(
    val attempt: Int?,
    val error: String?,
    val timestamp: OffsetDateTime?
)
