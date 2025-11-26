package org.btmonier.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.Properties

/**
 * Factory for database connection and initialization.
 */
object DatabaseFactory {
    private fun loadProperties(): Properties? {
        val propertiesFile = File("database.properties")
        return if (propertiesFile.exists()) {
            Properties().apply {
                propertiesFile.inputStream().use { load(it) }
            }
        } else {
            null
        }
    }

    fun init(
        jdbcUrl: String? = null,
        driverClassName: String? = null,
        username: String? = null,
        password: String? = null,
        maximumPoolSize: Int? = null
    ) {
        val props = loadProperties()

        // Priority: function parameters > environment variables > properties file > defaults
        val finalJdbcUrl = jdbcUrl
            ?: System.getenv("DATABASE_URL")
            ?: props?.getProperty("database.url")
            ?: "jdbc:postgresql://localhost:5432/moviedb"

        val finalDriverClassName = driverClassName
            ?: "org.postgresql.Driver"

        val finalUsername = username
            ?: System.getenv("DB_USER")
            ?: props?.getProperty("database.user")
            ?: "postgres"

        val finalPassword = password
            ?: System.getenv("DB_PASSWORD")
            ?: props?.getProperty("database.password")
            ?: "postgres"

        val finalMaxPoolSize = maximumPoolSize
            ?: props?.getProperty("database.maxPoolSize")?.toIntOrNull()
            ?: 10

        // Log configuration source
        if (props != null) {
            println("Loading database configuration from database.properties")
        } else if (System.getenv("DATABASE_URL") != null) {
            println("Loading database configuration from environment variables")
        } else {
            println("Using default database configuration")
        }

        val database = Database.connect(createHikariDataSource(
            url = finalJdbcUrl,
            driver = finalDriverClassName,
            username = finalUsername,
            password = finalPassword,
            maximumPoolSize = finalMaxPoolSize
        ))

        // Create tables if they don't exist
        transaction(database) {
            SchemaUtils.create(
                Movies,
                MovieAlternateTitles,
                Genres,
                Subgenres,
                MovieGenres,
                MovieSubgenres,
                MovieThemes,
                MovieCountries,
                MovieCast,
                MovieCrew,
                PhysicalMedia,
                PhysicalMediaTypes,
                PhysicalMediaImages,
                WatchedEntries
            )
        }
    }

    private fun createHikariDataSource(
        url: String,
        driver: String,
        username: String,
        password: String,
        maximumPoolSize: Int
    ): HikariDataSource {
        val config = HikariConfig().apply {
            driverClassName = driver
            jdbcUrl = url
            this.username = username
            this.password = password
            this.maximumPoolSize = maximumPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        return HikariDataSource(config)
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
