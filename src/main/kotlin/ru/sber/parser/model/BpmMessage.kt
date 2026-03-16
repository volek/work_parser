package ru.sber.parser.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import java.time.OffsetDateTime

/**
 * Модель входящего BPM-сообщения от процессного движка.
 * 
 * Представляет экземпляр бизнес-процесса со всеми метаданными,
 * состоянием выполнения, узлами и переменными процесса.
 * 
 * Источники данных:
 * - Camunda BPM Engine через REST API или Kafka
 * - Экспорт из БД процессного движка
 * - Синтетические данные от MessageGenerator
 * 
 * Жизненный цикл:
 * ┌─────────┐    ┌─────────────┐    ┌───────────────┐
 * │ RUNNING │ -> │ COMPLETED   │ или │ FAILED        │
 * │ state=1 │    │ state=2     │    │ state=3       │
 * │ endDate │    │ endDate!=null│   │ error!=null   │
 * │ =null   │    └─────────────┘    └───────────────┘
 * └─────────┘
 * 
 * Использование:
 * - ParseStrategy преобразует BpmMessage в записи Druid
 * - VariableFlattener извлекает переменные для EAV-схемы
 * - Фильтрация по state для отбора завершённых процессов
 * 
 * @property id Уникальный UUID экземпляра процесса
 * @property parentInstanceId ID родительского процесса (для подпроцессов)
 * @property rootInstanceId ID корневого процесса в иерархии
 * @property processId Идентификатор определения процесса (BPMN ID)
 * @property processDefinitionId Версионированный ID определения
 * @property resourceName Имя BPMN-файла ресурса
 * @property rootProcessId Корневой BPMN ID
 * @property processName Человекочитаемое название процесса
 * @property startDate Время запуска экземпляра (ISO-8601)
 * @property endDate Время завершения (null если running)
 * @property state Код состояния: 1=running, 2=completed, 3=failed
 * @property businessKey Бизнес-ключ (например, номер заявки)
 * @property version Версия определения процесса
 * @property bamProjectId ID проекта в BAM-системе
 * @property extIds Внешние идентификаторы (JSON или CSV)
 * @property error Текст ошибки при state=3
 * @property moduleId ID модуля в Camunda
 * @property engineVersion Версия процессного движка
 * @property enginePodName Имя Kubernetes pod с движком
 * @property retryCount Количество попыток перезапуска
 * @property ownerRole Роль владельца процесса
 * @property idempotencyKey Ключ идемпотентности для предотвращения дублирования
 * @property operation Тип операции (CREATE, UPDATE, DELETE)
 * @property nodeInstances Список экземпляров узлов (задач, gateway и т.д.)
 * @property variables Переменные процесса (вложенная структура)
 * @property contextSize Размер контекста в байтах
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BpmMessage(
    val id: String,
    val parentInstanceId: String?,
    val rootInstanceId: String?,
    val processId: String,
    val processDefinitionId: String?,
    val resourceName: String?,
    val rootProcessId: String?,
    val processName: String,
    val startDate: OffsetDateTime,
    val endDate: OffsetDateTime?,
    val state: Int,
    val businessKey: String?,
    val version: Int?,
    val bamProjectId: String?,
    val extIds: String?,
    val error: String?,
    val moduleId: String?,
    val engineVersion: String?,
    val enginePodName: String?,
    val retryCount: Int?,
    val ownerRole: String?,
    val idempotencyKey: String?,
    val operation: String?,
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    val nodeInstances: List<NodeInstance> = emptyList(),
    val variables: Map<String, Any?> = emptyMap(),
    val contextSize: Long?
) {
    companion object {
        /** Процесс выполняется */
        const val STATE_RUNNING = 1
        
        /** Процесс успешно завершён */
        const val STATE_COMPLETED = 2
        
        /** Процесс завершился с ошибкой */
        const val STATE_FAILED = 3
    }
    
    /**
     * Извлекает значение переменной по пути в нотации через точку.
     * 
     * Примеры путей:
     * - "caseId" → variables["caseId"]
     * - "staticData.clientEpkId" → variables["staticData"]["clientEpkId"]
     * - "epkData.epkEntity.ucpId" → глубокая вложенность
     * 
     * @param path Путь к переменной (через точку)
     * @return Значение переменной или null если не найдено
     */
    fun getVariable(path: String): Any? {
        return getNestedValue(variables, path.split("."))
    }
    
    /**
     * Извлекает строковое значение переменной.
     * Вызывает toString() для любого найденного значения.
     * 
     * @param path Путь к переменной
     * @return Строковое представление или null
     */
    fun getStringVariable(path: String): String? {
        return getVariable(path)?.toString()
    }
    
    /**
     * Извлекает числовое значение переменной как Long.
     * Поддерживает конвертацию из Number и String.
     * 
     * @param path Путь к переменной
     * @return Значение Long или null при ошибке конвертации
     */
    fun getLongVariable(path: String): Long? {
        return when (val value = getVariable(path)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }
    
    /**
     * Извлекает числовое значение переменной как Int.
     * Поддерживает конвертацию из Number и String.
     * 
     * @param path Путь к переменной
     * @return Значение Int или null при ошибке конвертации
     */
    fun getIntVariable(path: String): Int? {
        return when (val value = getVariable(path)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
    
    /**
     * Рекурсивно извлекает значение из вложенной структуры Map.
     * 
     * Алгоритм:
     * 1. Берём первую часть пути
     * 2. Ищем ключ в текущем Map
     * 3. Если путь закончился — возвращаем значение
     * 4. Если значение — Map, рекурсивно обрабатываем остаток пути
     * 5. Иначе возвращаем null (путь не существует)
     * 
     * @param map Текущий уровень вложенности
     * @param pathParts Оставшиеся части пути
     * @return Найденное значение или null
     */
    @Suppress("UNCHECKED_CAST")
    private fun getNestedValue(map: Map<String, Any?>, pathParts: List<String>): Any? {
        if (pathParts.isEmpty()) return null
        
        val current = map[pathParts.first()]
        
        return when {
            pathParts.size == 1 -> current
            current is Map<*, *> -> {
                getNestedValue(current as Map<String, Any?>, pathParts.drop(1))
            }
            else -> null
        }
    }
    
    /** Проверяет, выполняется ли процесс в данный момент */
    fun isRunning(): Boolean = state == STATE_RUNNING
    
    /** Проверяет, успешно ли завершён процесс */
    fun isCompleted(): Boolean = state == STATE_COMPLETED
    
    /** Проверяет, завершился ли процесс с ошибкой */
    fun isFailed(): Boolean = state == STATE_FAILED
}
