package ru.sber.parser.druid

/**
 * Единый реестр имён datasource в Druid.
 *
 * Важно: имена scope-нуты по стратегии, чтобы данные разных стратегий
 * никогда не смешивались в одних и тех же таблицах.
 */
object DruidDataSources {
    object Default {
        const val MAIN = "default_process_default"
        const val VARIABLES_ARRAY_INDEXED = "default_process_variables_array_indexed"
    }
}
