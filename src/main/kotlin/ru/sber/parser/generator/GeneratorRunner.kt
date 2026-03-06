package ru.sber.parser.generator

import java.io.File

/**
 * Точка входа для генерации тестовых BPM-сообщений.
 * 
 * Запускает генератор синтетических данных и сохраняет
 * результаты в директорию ./messages.
 * 
 * Параметры генерации (захардкожены):
 * - outputDir: ./messages
 * - count: 25 сообщений
 * 
 * Запуск:
 * ```bash
 * ./gradlew run -PmainClass=ru.sber.parser.generator.GeneratorRunnerKt
 * ```
 * 
 * Результат:
 * ```
 * messages/
 *   message_001.json
 *   message_002.json
 *   ...
 *   message_025.json
 * ```
 * 
 * @see MessageGenerator логика генерации
 */
fun main() {
    val generator = MessageGenerator()
    val outputDir = File("messages")
    generator.generateAll(outputDir, 100)
    println("Messages generated in: ${outputDir.absolutePath}")
}
