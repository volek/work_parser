package ru.sber.parser.parser

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Утилита для "сплющивания" (flattening) вложенных структур переменных.
 * 
 * Преобразует иерархическую структуру Map/List в плоский список
 * пар "путь → значение" для загрузки в EAV-схему Apache Druid.
 * 
 * Пример преобразования:
 * ```
 * Входные данные (nested):
 * {
 *   "caseId": "123",
 *   "staticData": {
 *     "clientEpkId": "456",
 *     "phones": ["111", "222"]
 *   }
 * }
 * 
 * Результат (flattened):
 * [
 *   ("caseId", "123", STRING),
 *   ("staticData.clientEpkId", "456", STRING),
 *   ("staticData.phones[0]", "111", STRING),
 *   ("staticData.phones[1]", "222", STRING)
 * ]
 * ```
 * 
 * Поддерживаемые типы данных:
 * - STRING: строковые значения
 * - NUMBER: числа (Int, Long, Double)
 * - BOOLEAN: true/false
 * - DATE: ISO-8601 даты (автоопределение)
 * - JSON: пустые объекты {} или массивы []
 * - NULL: явные null-значения
 * 
 * Формат пути:
 * - Вложенность через точку: "parent.child.grandchild"
 * - Индексы массивов в скобках: "items[0].name"
 * - Wildcard для всех элементов: "items[*].name"
 * 
 * @see FlattenedVariable результат сплющивания
 * @see PathPart компоненты пути
 * @see EavStrategy использует для генерации EAV-записей
 */
class VariableFlattener {
    
    /**
     * Результат сплющивания одной переменной.
     * 
     * @property path Полный путь к переменной (например, "staticData.clientEpkId")
     * @property value Строковое представление значения (null для NULL-типа)
     * @property type Тип данных из ProcessVariableRecord.TYPE_*
     */
    data class FlattenedVariable(
        val path: String,
        val value: String?,
        val type: String
    )
    
    /**
     * Сплющивает вложенную структуру переменных в плоский список.
     * 
     * Рекурсивно обходит все вложенные Map и List, формируя
     * путь к каждому листовому (конечному) значению.
     * 
     * @param variables Карта переменных процесса (variables из BpmMessage)
     * @param prefix Начальный префикс пути (обычно пустая строка)
     * @return Список сплющенных переменных с путями и типами
     * 
     * @see FlattenedVariable структура результата
     */
    fun flatten(variables: Map<String, Any?>, prefix: String = ""): List<FlattenedVariable> {
        val result = mutableListOf<FlattenedVariable>()
        flattenRecursive(variables, prefix, result)
        return result
    }
    
    /**
     * Рекурсивная функция сплющивания.
     * 
     * Обрабатывает все типы данных JSON:
     * - null → запись с TYPE_NULL
     * - Map → рекурсия по ключам с добавлением к пути через точку
     * - List → рекурсия по индексам с добавлением [index] к пути
     * - String → определение типа (DATE или STRING)
     * - Number → TYPE_NUMBER
     * - Boolean → TYPE_BOOLEAN
     * - Other → toString() с TYPE_STRING
     * 
     * @param obj Текущий объект для обработки
     * @param currentPath Накопленный путь
     * @param result Мутабельный список для накопления результатов
     */
    @Suppress("UNCHECKED_CAST")
    private fun flattenRecursive(
        obj: Any?,
        currentPath: String,
        result: MutableList<FlattenedVariable>
    ) {
        when (obj) {
            // Обработка null-значений
            null -> {
                if (currentPath.isNotEmpty()) {
                    result.add(FlattenedVariable(currentPath, null, TYPE_NULL))
                }
            }
            // Обработка вложенных объектов (Map)
            is Map<*, *> -> {
                val map = obj as Map<String, Any?>
                if (map.isEmpty() && currentPath.isNotEmpty()) {
                    // Пустой объект сохраняем как JSON
                    result.add(FlattenedVariable(currentPath, "{}", TYPE_JSON))
                } else {
                    // Рекурсивно обходим все ключи
                    map.forEach { (key, value) ->
                        val newPath = if (currentPath.isEmpty()) key else "$currentPath.$key"
                        flattenRecursive(value, newPath, result)
                    }
                }
            }
            // Обработка массивов (List)
            is List<*> -> {
                if (obj.isEmpty() && currentPath.isNotEmpty()) {
                    // Пустой массив сохраняем как JSON
                    result.add(FlattenedVariable(currentPath, "[]", TYPE_JSON))
                } else {
                    // Рекурсивно обходим все элементы с индексами
                    obj.forEachIndexed { index, item ->
                        flattenRecursive(item, "$currentPath[$index]", result)
                    }
                }
            }
            // Обработка строк с автоопределением типа
            is String -> {
                val type = detectStringType(obj)
                result.add(FlattenedVariable(currentPath, obj, type))
            }
            // Обработка чисел
            is Number -> {
                result.add(FlattenedVariable(currentPath, obj.toString(), TYPE_NUMBER))
            }
            // Обработка булевых значений
            is Boolean -> {
                result.add(FlattenedVariable(currentPath, obj.toString(), TYPE_BOOLEAN))
            }
            // Fallback для неизвестных типов
            else -> {
                result.add(FlattenedVariable(currentPath, obj.toString(), TYPE_STRING))
            }
        }
    }
    
    /**
     * Определяет тип строкового значения.
     * 
     * Пытается распознать строку как дату ISO-8601.
     * Если не удаётся — возвращает TYPE_STRING.
     * 
     * @param value Строка для анализа
     * @return TYPE_DATE если это дата, иначе TYPE_STRING
     */
    private fun detectStringType(value: String): String {
        if (isDateTimeString(value)) {
            return TYPE_DATE
        }
        return TYPE_STRING
    }
    
    /**
     * Проверяет, является ли строка датой ISO-8601.
     * 
     * Поддерживаемые форматы:
     * - OffsetDateTime: "2024-01-15T10:30:00+03:00"
     * - Instant: "2024-01-15T07:30:00Z"
     * 
     * @param value Строка для проверки
     * @return true если строка — валидная дата
     */
    private fun isDateTimeString(value: String): Boolean {
        return try {
            OffsetDateTime.parse(value)
            true
        } catch (e: DateTimeParseException) {
            try {
                Instant.parse(value)
                true
            } catch (e2: DateTimeParseException) {
                false
            }
        }
    }
    
    /**
     * Извлекает значение по пути из вложенной структуры.
     * 
     * Обратная операция к flatten — получает значение
     * по известному пути в нотации "key.subkey[index]".
     * 
     * Примеры:
     * - "caseId" → значение переменной caseId
     * - "staticData.phones[0]" → первый элемент массива phones
     * - "items[*].name" → wildcard (все имена)
     * 
     * @param variables Карта переменных процесса
     * @param path Путь к значению
     * @return Найденное значение или null
     */
    fun extractValue(variables: Map<String, Any?>, path: String): Any? {
        val parts = parsePath(path)
        return extractValueRecursive(variables, parts)
    }
    
    /**
     * Рекурсивно извлекает значение по списку компонентов пути.
     * 
     * @param obj Текущий уровень вложенности
     * @param parts Оставшиеся компоненты пути
     * @return Найденное значение или null
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractValueRecursive(obj: Any?, parts: List<PathPart>): Any? {
        if (parts.isEmpty()) return obj
        
        val current = parts.first()
        val remaining = parts.drop(1)
        
        return when {
            obj == null -> null
            // Обработка ключа в Map
            current is PathPart.Key && obj is Map<*, *> -> {
                extractValueRecursive((obj as Map<String, Any?>)[current.name], remaining)
            }
            // Обработка индекса в List
            current is PathPart.Index && obj is List<*> -> {
                if (current.index < obj.size) {
                    extractValueRecursive(obj[current.index], remaining)
                } else null
            }
            else -> null
        }
    }
    
    /**
     * Парсит строку пути в список компонентов PathPart.
     * 
     * Грамматика пути:
     * - key → PathPart.Key("key")
     * - [N] → PathPart.Index(N)
     * - [*] → PathPart.Wildcard
     * - key.subkey → [Key("key"), Key("subkey")]
     * - key[0].name → [Key("key"), Index(0), Key("name")]
     * 
     * @param path Строка пути в dot-notation
     * @return Список компонентов PathPart
     */
    private fun parsePath(path: String): List<PathPart> {
        val result = mutableListOf<PathPart>()
        var current = path
        
        while (current.isNotEmpty()) {
            val bracketIdx = current.indexOf('[')
            val dotIdx = current.indexOf('.')
            
            when {
                // Начинается с индекса [...]
                bracketIdx == 0 -> {
                    val endBracket = current.indexOf(']')
                    if (endBracket > 1) {
                        val indexStr = current.substring(1, endBracket)
                        if (indexStr == "*") {
                            // Wildcard [*] — все элементы массива
                            result.add(PathPart.Wildcard)
                        } else {
                            // Числовой индекс [N]
                            indexStr.toIntOrNull()?.let { result.add(PathPart.Index(it)) }
                        }
                        current = current.substring(endBracket + 1).removePrefix(".")
                    } else break
                }
                // Ключ перед скобкой: key[...]
                bracketIdx > 0 && (dotIdx < 0 || bracketIdx < dotIdx) -> {
                    result.add(PathPart.Key(current.substring(0, bracketIdx)))
                    current = current.substring(bracketIdx)
                }
                // Ключ перед точкой: key.subkey
                dotIdx > 0 && (bracketIdx < 0 || dotIdx < bracketIdx) -> {
                    result.add(PathPart.Key(current.substring(0, dotIdx)))
                    current = current.substring(dotIdx + 1)
                }
                // Пропускаем начальную точку
                dotIdx == 0 -> {
                    current = current.substring(1)
                }
                // Последний ключ
                else -> {
                    result.add(PathPart.Key(current))
                    break
                }
            }
        }
        
        return result
    }
    
    /**
     * Компонент пути для навигации по вложенной структуре.
     * 
     * Sealed class обеспечивает исчерпывающую обработку
     * всех типов компонентов через when.
     */
    sealed class PathPart {
        /** Ключ в объекте Map: "fieldName" */
        data class Key(val name: String) : PathPart()
        
        /** Индекс в массиве List: [0], [1], ... */
        data class Index(val index: Int) : PathPart()
        
        /** Wildcard для всех элементов массива: [*] */
        object Wildcard : PathPart()
    }

    companion object {
        const val TYPE_STRING = "string"
        const val TYPE_NUMBER = "number"
        const val TYPE_BOOLEAN = "boolean"
        const val TYPE_DATE = "date"
        const val TYPE_JSON = "json"
        const val TYPE_NULL = "null"
    }
}
