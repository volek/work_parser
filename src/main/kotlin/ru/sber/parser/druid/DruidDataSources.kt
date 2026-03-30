package ru.sber.parser.druid

/**
 * Единый реестр имён datasource в Druid.
 *
 * Важно: имена scope-нуты по стратегии, чтобы данные разных стратегий
 * никогда не смешивались в одних и тех же таблицах.
 */
object DruidDataSources {
    object Hybrid {
        const val MAIN = "hybrid_process_hybrid"
    }

    object Eav {
        const val EVENTS = "eav_process_events"
        const val VARIABLES = "eav_process_variables"
    }

    object Combined {
        const val MAIN = "combined_process_main"
        const val VARIABLES_INDEXED = "combined_process_variables_indexed"
    }

    object Compcom {
        const val MAIN_COMPACT = "compcom_process_main_compact"
        const val VARIABLES_INDEXED = "compcom_process_variables_indexed"
    }

    object Default {
        const val MAIN = "default_process_default"
    }
}
