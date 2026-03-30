package ru.sber.parser.druid

import ru.sber.parser.druid.DruidDataSources.Combined
import ru.sber.parser.druid.DruidDataSources.Compcom
import ru.sber.parser.druid.DruidDataSources.Eav
import ru.sber.parser.druid.DruidDataSources.Hybrid
import ru.sber.parser.parser.strategy.CombinedStrategy
import ru.sber.parser.parser.strategy.EavStrategy
import ru.sber.parser.parser.strategy.HybridStrategy

/**
 * Генератор SQL DDL-скриптов для создания datasource в Apache Druid.
 * 
 * Предоставляет справочные DDL-скрипты для каждой стратегии парсинга.
 * Эти скрипты служат документацией и могут использоваться для:
 * - Создания datasource через Druid SQL
 * - Понимания структуры данных
 * - Настройки BI-инструментов
 * 
 * Примечание: Druid автоматически создаёт datasource при первом
 * ingestion. DDL-скрипты полезны для документации и валидации схемы.
 * 
 * Стратегии и их схемы:
 * ┌───────────────────┬──────────────────────────────────────────────┐
 * │ Hybrid            │ 1 таблица: process_hybrid                   │
 * │ EAV               │ 2 таблицы: process_events + process_variables│
 * │ Combined          │ 2 таблицы: process_main + process_vars_idx   │
 * └───────────────────┴──────────────────────────────────────────────┘
 * 
 * Использование:
 * ```kotlin
 * // Получить DDL для конкретной стратегии
 * val ddl = SchemaGenerator.generateHybridDDL()
 * println(ddl)
 * 
 * // Получить все DDL
 * val allSchemas = SchemaGenerator.getAllSchemas()
 * ```
 * 
 * @see HybridStrategy стратегия одной таблицы
 * @see EavStrategy EAV-стратегия с двумя таблицами
 * @see CombinedStrategy комбинированная стратегия с hot/warm/cold
 */
object SchemaGenerator {
    
    /**
     * Генерирует DDL для Hybrid-стратегии.
     * 
     * Одна широкая таблица с:
     * - Метаданными процесса
     * - Hot-колонками (Tier 1)
     * - JSON-блобами (Tier 3)
     * 
     * @return SQL DDL скрипт
     */
    fun generateHybridDDL(): String {
        return """
            |-- Hybrid Strategy: Single table with flat columns and JSON blobs
            |-- DataSource: ${Hybrid.MAIN}
            |
            |CREATE TABLE ${Hybrid.MAIN} (
            |    __time TIMESTAMP NOT NULL,
            |    process_id VARCHAR NOT NULL,
            |    process_name VARCHAR,
            |    state BIGINT,
            |    module_id VARCHAR,
            |    business_key VARCHAR,
            |    root_instance_id VARCHAR,
            |    parent_instance_id VARCHAR,
            |    version BIGINT,
            |    end_date TIMESTAMP,
            |    error VARCHAR,
            |    
            |    -- Hot variables (Tier 1)
            |    var_caseId VARCHAR,
            |    var_epkId VARCHAR,
            |    var_fio VARCHAR,
            |    var_ucpId VARCHAR,
            |    var_status VARCHAR,
            |    var_globalInstanceId VARCHAR,
            |    var_interactionId VARCHAR,
            |    var_interactionDate VARCHAR,
            |    var_theme VARCHAR,
            |    var_result VARCHAR,
            |    var_staticData_caseId VARCHAR,
            |    var_staticData_clientEpkId BIGINT,
            |    var_staticData_casePublicId VARCHAR,
            |    var_staticData_statusCode VARCHAR,
            |    var_staticData_registrationTime TIMESTAMP,
            |    var_staticData_closedTime TIMESTAMP,
            |    var_staticData_classifierVersion BIGINT,
            |    var_epkData_ucpId VARCHAR,
            |    var_epkData_clientStatus BIGINT,
            |    var_epkData_gender BIGINT,
            |    var_tracingHeaders_requestId VARCHAR,
            |    var_tracingHeaders_traceId VARCHAR,
            |    
            |    -- JSON blobs (Cold data)
            |    node_instances_json VARCHAR,
            |    var_epkData_json VARCHAR,
            |    var_staticData_json VARCHAR,
            |    var_answerGFL_json VARCHAR,
            |    var_other_json VARCHAR
            |);
            |
            |-- Recommended indexes for hot columns
            |-- Note: Druid automatically indexes all dimensions
        """.trimMargin()
    }
    
    /**
     * Генерирует DDL для EAV-стратегии.
     * 
     * Две таблицы:
     * 1. process_events — метаданные процессов
     * 2. process_variables — переменные в EAV-формате
     * 
     * Включает пример JOIN-запроса.
     * 
     * @return SQL DDL скрипт
     */
    fun generateEavDDL(): String {
        return """
            |-- EAV Strategy: Two tables - ${Eav.EVENTS} and ${Eav.VARIABLES}
            |
            |-- Table 1: Process Events (core process data)
            |CREATE TABLE ${Eav.EVENTS} (
            |    __time TIMESTAMP NOT NULL,
            |    process_id VARCHAR NOT NULL,
            |    process_name VARCHAR,
            |    state BIGINT,
            |    module_id VARCHAR,
            |    business_key VARCHAR,
            |    root_instance_id VARCHAR,
            |    parent_instance_id VARCHAR,
            |    version BIGINT,
            |    end_date TIMESTAMP,
            |    error VARCHAR,
            |    node_instances_json VARCHAR
            |);
            |
            |-- Table 2: Process Variables (EAV model)
            |CREATE TABLE ${Eav.VARIABLES} (
            |    __time TIMESTAMP NOT NULL,
            |    process_id VARCHAR NOT NULL,
            |    var_path VARCHAR NOT NULL,
            |    var_value VARCHAR,
            |    var_type VARCHAR
            |);
            |
            |-- Join queries example:
            |-- SELECT pe.process_name, pv.var_value
            |-- FROM ${Eav.EVENTS} pe
            |-- JOIN ${Eav.VARIABLES} pv ON pe.process_id = pv.process_id
            |-- WHERE pv.var_path = 'epkId'
        """.trimMargin()
    }
    
    /**
     * Генерирует DDL для Combined-стратегии.
     * 
     * Две таблицы:
     * 1. process_main — hot-колонки + cold-блобы
     * 2. process_variables_indexed — warm-переменные по категориям
     * 
     * Включает пример запроса с категорийной фильтрацией.
     * 
     * @return SQL DDL скрипт
     */
    fun generateCombinedDDL(): String {
        return """
            |-- Combined Strategy: Hot columns in main table, warm data in indexed variables
            |
            |-- Table 1: Process Main (Tier 1 hot + Tier 3 cold blobs)
            |CREATE TABLE ${Combined.MAIN} (
            |    __time TIMESTAMP NOT NULL,
            |    process_id VARCHAR NOT NULL,
            |    process_name VARCHAR,
            |    state BIGINT,
            |    module_id VARCHAR,
            |    business_key VARCHAR,
            |    root_instance_id VARCHAR,
            |    parent_instance_id VARCHAR,
            |    version BIGINT,
            |    end_date TIMESTAMP,
            |    error VARCHAR,
            |    
            |    -- Tier 1: Hot variables (direct columns)
            |    var_caseId VARCHAR,
            |    var_epkId VARCHAR,
            |    var_fio VARCHAR,
            |    var_ucpId VARCHAR,
            |    var_status VARCHAR,
            |    var_globalInstanceId VARCHAR,
            |    var_interactionId VARCHAR,
            |    var_interactionDate VARCHAR,
            |    var_theme VARCHAR,
            |    var_result VARCHAR,
            |    var_staticData_caseId VARCHAR,
            |    var_staticData_clientEpkId BIGINT,
            |    var_staticData_casePublicId VARCHAR,
            |    var_staticData_statusCode VARCHAR,
            |    var_staticData_registrationTime TIMESTAMP,
            |    var_staticData_closedTime TIMESTAMP,
            |    var_staticData_classifierVersion BIGINT,
            |    var_epkData_ucpId VARCHAR,
            |    var_epkData_clientStatus BIGINT,
            |    var_epkData_gender BIGINT,
            |    var_tracingHeaders_requestId VARCHAR,
            |    var_tracingHeaders_traceId VARCHAR,
            |    
            |    -- Tier 3: Cold JSON blobs
            |    node_instances_json VARCHAR,
            |    var_blob_json VARCHAR
            |);
            |
            |-- Table 2: Process Variables Indexed (Tier 2 warm data)
            |CREATE TABLE ${Combined.VARIABLES_INDEXED} (
            |    __time TIMESTAMP NOT NULL,
            |    process_id VARCHAR NOT NULL,
            |    var_category VARCHAR NOT NULL,  -- epkData, staticData, tracingHeaders
            |    var_path VARCHAR NOT NULL,
            |    var_value VARCHAR,
            |    var_type VARCHAR
            |);
            |
            |-- Category-filtered queries:
            |-- SELECT pm.*, pvi.var_value
            |-- FROM ${Combined.MAIN} pm
            |-- JOIN ${Combined.VARIABLES_INDEXED} pvi ON pm.process_id = pvi.process_id
            |-- WHERE pvi.var_category = 'epkData' AND pvi.var_path = 'epkEntity.phoneNumbers[0].phoneNumber'
        """.trimMargin()
    }

    fun generateCompcomDDL(): String {
        return """
            |-- Compcom Strategy: Compact main table + strategy-scoped indexed variables
            |
            |-- Table 1: Compact main table (no var_blob_json)
            |CREATE TABLE ${Compcom.MAIN_COMPACT} (
            |    __time TIMESTAMP NOT NULL,
            |    process_id VARCHAR NOT NULL,
            |    process_name VARCHAR,
            |    state BIGINT,
            |    module_id VARCHAR,
            |    business_key VARCHAR,
            |    root_instance_id VARCHAR,
            |    parent_instance_id VARCHAR,
            |    version BIGINT,
            |    end_date TIMESTAMP,
            |    error VARCHAR,
            |    var_caseId VARCHAR,
            |    var_epkId VARCHAR,
            |    var_fio VARCHAR,
            |    var_ucpId VARCHAR,
            |    var_status VARCHAR,
            |    var_globalInstanceId VARCHAR,
            |    var_interactionId VARCHAR,
            |    var_interactionDate VARCHAR,
            |    var_theme VARCHAR,
            |    var_result VARCHAR,
            |    var_staticData_caseId VARCHAR,
            |    var_staticData_clientEpkId BIGINT,
            |    var_staticData_casePublicId VARCHAR,
            |    var_staticData_statusCode VARCHAR,
            |    var_staticData_registrationTime TIMESTAMP,
            |    var_staticData_closedTime TIMESTAMP,
            |    var_staticData_classifierVersion BIGINT,
            |    var_epkData_ucpId VARCHAR,
            |    var_epkData_clientStatus BIGINT,
            |    var_epkData_gender BIGINT,
            |    var_tracingHeaders_requestId VARCHAR,
            |    var_tracingHeaders_traceId VARCHAR,
            |    node_instances_json VARCHAR
            |);
            |
            |-- Table 2: Strategy-scoped warm variables index
            |CREATE TABLE ${Compcom.VARIABLES_INDEXED} (
            |    __time TIMESTAMP NOT NULL,
            |    process_id VARCHAR NOT NULL,
            |    var_category VARCHAR NOT NULL,
            |    var_path VARCHAR NOT NULL,
            |    var_value VARCHAR,
            |    var_type VARCHAR
            |);
        """.trimMargin()
    }
    
    /**
     * Возвращает все DDL-схемы как Map.
     * 
     * Ключи: "hybrid", "eav", "combined"
     * Значения: соответствующие DDL-скрипты
     * 
     * @return Map<strategyName, ddlScript>
     */
    fun getAllSchemas(): Map<String, String> = mapOf(
        "hybrid" to generateHybridDDL(),
        "eav" to generateEavDDL(),
        "combined" to generateCombinedDDL(),
        "compcom" to generateCompcomDDL()
    )
}
