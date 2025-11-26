package org.btmonier.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.btmonier.database.DatabaseFactory
import org.btmonier.database.GenreDao
import org.btmonier.database.MovieDao
import org.btmonier.database.PhysicalMediaDao
import org.slf4j.event.Level
import java.io.File

/**
 * API info response data class for proper serialization.
 */
@Serializable
data class ApiInfo(
    val name: String,
    val version: String,
    val endpoints: List<String>
)

/**
 * Health check response data class.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val service: String
)

/**
 * Main entry point for the Ktor web server.
 */
fun main() {
    // Initialize database
    println("Initializing database connection...")
    DatabaseFactory.init()
    println("Database initialized successfully!")

    // Start the server
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        configureServer()
    }.start(wait = true)
}

/**
 * Configure the Ktor server with plugins and routes.
 */
fun Application.configureServer() {
    val movieDao = MovieDao()
    val physicalMediaDao = PhysicalMediaDao()
    val genreDao = GenreDao()

    // Configure JSON serialization
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    // Configure CORS
    install(CORS) {
        anyHost() // For development - in production, specify allowed hosts
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowCredentials = true
    }

    // Configure logging
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.local.uri.startsWith("/api") }
    }

    // Configure status pages (error handling)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("Request failed", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Internal server error: ${cause.message}")
            )
        }
    }

    // Configure routing
    routing {
        // API routes
        movieRoutes(movieDao)
        physicalMediaRoutes(movieDao, physicalMediaDao)
        watchedRoutes()
        genreRoutes(genreDao)

        // Serve static files from build directory (for Kotlin/JS frontend)
        staticFiles("/", File("build/kotlin-webpack/js/developmentExecutable")) {
            default("index.html")
        }

        // Health check endpoint
        get("/health") {
            call.respond(HttpStatusCode.OK, HealthResponse(status = "OK", service = "Movie Omnibus API"))
        }

        // API info endpoint
        get("/api") {
            call.respond(HttpStatusCode.OK, ApiInfo(
                name = "Movie Omnibus API",
                version = "1.0",
                endpoints = listOf(
                    "GET /api/movies - Get all movies",
                    "GET /api/movies/{id} - Get movie by ID",
                    "GET /api/movies/search?q={query} - Search movies by title",
                    "GET /api/movies/by-genre/{genre} - Filter by genre",
                    "GET /api/movies/by-country/{country} - Filter by country",
                    "GET /api/movies/by-media-type/{type} - Filter by physical media type",
                    "POST /api/movies - Create new movie",
                    "POST /api/movies/scrape - Scrape movie data from Letterboxd URL",
                    "PUT /api/movies/{id} - Update movie",
                    "DELETE /api/movies/{id} - Delete movie",
                    "GET /api/movies/{id}/physical-media - Get physical media for movie",
                    "POST /api/movies/{id}/physical-media - Add physical media to movie",
                    "PUT /api/physical-media/{id} - Update physical media entry",
                    "DELETE /api/physical-media/{id} - Delete physical media entry",
                    "GET /api/movies/{id}/watched - Get watched entries for movie",
                    "POST /api/movies/{id}/watched - Add watched entry to movie",
                    "PUT /api/watched/{id} - Update watched entry",
                    "DELETE /api/watched/{id} - Delete watched entry",
                    "GET /api/movies/{id}/rating/average - Get average rating for movie",
                    "GET /api/movies/{id}/watch-count - Get watch count for movie"
                )
            ))
        }
    }

    println("Server is running on http://localhost:8080")
    println("API documentation available at http://localhost:8080/api")
}
