package ru.sber.parser.integration

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIf
import ru.sber.parser.config.AppConfig
import ru.sber.parser.config.DruidConfig
import ru.sber.parser.druid.DruidClient
import java.net.HttpURLConnection
import java.net.URL

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class IntegrationTestBase {
    
    protected lateinit var config: AppConfig
    protected lateinit var druidClient: DruidClient
    
    companion object {
        private var druidAvailable: Boolean? = null
        
        @JvmStatic
        fun isDruidAvailable(): Boolean {
            if (druidAvailable == null) {
                druidAvailable = checkDruidConnection()
            }
            return druidAvailable!!
        }
        
        private fun checkDruidConnection(): Boolean {
            return try {
                val url = URL("http://localhost:8082/status")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.requestMethod = "GET"
                connection.responseCode == 200
            } catch (e: Exception) {
                false
            }
        }
    }
    
    @BeforeAll
    open fun setupBase() {
        config = AppConfig(
            druid = DruidConfig(
                brokerUrl = "http://localhost:8082",
                coordinatorUrl = "http://localhost:8081",
                overlordUrl = "http://localhost:8090",
                batchSize = 100
            )
        )
        
        if (isDruidAvailable()) {
            druidClient = DruidClient(config.druid)
        }
    }
}
