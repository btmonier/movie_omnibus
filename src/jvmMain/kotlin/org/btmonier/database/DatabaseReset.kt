package org.btmonier.database

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * CLI tool to reset the database by dropping and recreating all tables.
 * WARNING: This will permanently delete ALL data in the database!
 */
class DatabaseReset : CliktCommand(name = "reset-database") {
    private val force by option("--force", help = "Skip confirmation prompt and proceed with reset.")
        .flag(default = false)

    override fun run() = runBlocking {
        if (!force) {
            echo("WARNING: This will permanently delete ALL data in the database!", err = true)
            echo("Are you sure you want to continue? Type 'yes' to confirm:")
            val confirmation = readlnOrNull()?.trim()?.lowercase()

            if (confirmation != "yes") {
                echo("Database reset cancelled.")
                return@runBlocking
            }
        }

        echo("Initializing database connection...")
        DatabaseFactory.init()
        echo("Database initialized!")

        echo("Dropping all tables...")
        transaction {
            // Drop tables in reverse order to handle foreign key constraints
            SchemaUtils.drop(
                WatchedEntries,
                PhysicalMediaImages,
                PhysicalMediaTypes,
                PhysicalMedia,
                MovieCrew,
                MovieCast,
                MovieCountries,
                MovieThemes,
                MovieSubgenres,
                MovieGenres,
                MovieAlternateTitles,
                Movies
            )
        }
        echo("All tables dropped successfully!")

        echo("Creating fresh tables...")
        transaction {
            SchemaUtils.create(
                Movies,
                MovieAlternateTitles,
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
        echo("All tables created successfully!")

        echo("Database reset complete! The database is now empty and ready for fresh data.")
    }
}

/**
 * Main entry point for the database reset CLI tool.
 */
fun main(args: Array<String>) = DatabaseReset().main(args)
