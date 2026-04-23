package ru.sber.parser.druid

/**
 * Справочный генератор DDL для унифицированной default-стратегии.
 */
object SchemaGenerator {
    fun generateDefaultDDL(): String {
        return """
            |-- Default Strategy: required top-level fields + warm variables
            |
            |CREATE TABLE ${DruidDataSources.Default.MAIN} (
            |    __time TIMESTAMP NOT NULL,
            |    id VARCHAR NOT NULL,
            |    process_id VARCHAR NOT NULL,
            |    process_name VARCHAR,
            |    state BIGINT,
            |    start_date TIMESTAMP
            |);
            |
            |CREATE TABLE ${DruidDataSources.Default.VARIABLES_ARRAY_INDEXED} (
            |    __time TIMESTAMP NOT NULL,
            |    process_id VARCHAR NOT NULL,
            |    var_category VARCHAR NOT NULL,
            |    var_path VARCHAR NOT NULL,
            |    var_value VARCHAR,
            |    var_type VARCHAR,
            |    value_json VARCHAR
            |);
        """.trimMargin()
    }

    fun getAllSchemas(): Map<String, String> = mapOf("default" to generateDefaultDDL())
}
