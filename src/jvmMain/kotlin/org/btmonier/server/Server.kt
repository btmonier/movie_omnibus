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
import org.btmonier.storage.GcsService
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
 * Load the custom 404 page from resources or return a fallback.
 */
private fun loadNotFoundPage(): String {
    // Try loading from classpath resources
    val resourceStream = Thread.currentThread().contextClassLoader.getResourceAsStream("404.html")
    if (resourceStream != null) {
        return resourceStream.bufferedReader().use { it.readText() }
    }

    // Try loading from processed resources directory
    val processedResourcesFile = File("build/processedResources/jvm/main/404.html")
    if (processedResourcesFile.exists()) {
        return processedResourcesFile.readText()
    }

    // Fallback to inline 404 page
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>404 - Page Not Found | The Movie Omnibus</title>
            <style>
                body {
                    font-family: 'Google Sans', 'Roboto', arial, sans-serif;
                    background: linear-gradient(135deg, #f5f7fa 0%, #e4e8ed 100%);
                    min-height: 100vh;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    margin: 0;
                    padding: 20px;
                    box-sizing: border-box;
                }
                .container {
                    text-align: center;
                    max-width: 500px;
                }
                .icon { font-size: 100px; margin-bottom: 20px; }
                .code {
                    font-size: 72px;
                    font-weight: 600;
                    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                    -webkit-background-clip: text;
                    -webkit-text-fill-color: transparent;
                    margin-bottom: 16px;
                }
                h1 { color: #202124; font-weight: 500; margin-bottom: 16px; }
                p { color: #5f6368; line-height: 1.6; margin-bottom: 24px; }
                a {
                    display: inline-block;
                    padding: 12px 24px;
                    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                    color: white;
                    text-decoration: none;
                    border-radius: 8px;
                    font-weight: 500;
                }
                a:hover { opacity: 0.9; }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="icon">🎬</div>
                <div class="code">404</div>
                <h1>Reel Not Found</h1>
                <p>The page you're looking for doesn't exist or has been moved.</p>
                <a href="/">Back to Collection</a>
            </div>
        </body>
        </html>
    """.trimIndent()
}

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
    // Initialize Google Cloud Storage service (optional)
    val gcsService = GcsService.createFromConfig()
    if (gcsService != null) {
        log.info("GCS integration enabled - image URLs will be signed")
    } else {
        log.info("GCS integration disabled - using raw image URLs")
    }

    // Initialize DAOs with GCS service for URL signing
    val movieDao = MovieDao(gcsService)
    val physicalMediaDao = PhysicalMediaDao(gcsService)
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

    // Load 404 page content at startup from resources
    val notFoundHtml = loadNotFoundPage()

    // Configure status pages (error handling)
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            // Check if this is an API request
            if (call.request.local.uri.startsWith("/api")) {
                call.respond(
                    status,
                    mapOf("error" to "Not found", "path" to call.request.local.uri)
                )
            } else {
                // Serve custom 404 HTML page for non-API requests
                call.respondText(notFoundHtml, ContentType.Text.Html, status)
            }
        }
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
                    "GET /api/movies - Get all movies (paginated)",
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

        // Static files directory - check multiple possible locations (development vs production builds)
        val staticDir = listOf(
            File("build/kotlin-webpack/js/developmentExecutable"),
            File("build/dist/js/productionExecutable"),
            File("build/processedResources/js/main")
        ).firstOrNull { it.exists() && File(it, "index.html").exists() }
            ?: File("build/kotlin-webpack/js/developmentExecutable")

        // Serve index.html at root
        get("/") {
            val indexFile = File(staticDir, "index.html")
            if (indexFile.exists()) {
                call.respondFile(indexFile)
            } else {
                call.respondText(notFoundHtml, ContentType.Text.Html, HttpStatusCode.NotFound)
            }
        }

        // Serve static files (JS, CSS, map files)
        get("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            
            // Skip API paths (they should be handled by their routes)
            if (path.startsWith("api/")) {
                call.respondText(
                    """{"error": "Not found", "path": "/api/$path"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound
                )
                return@get
            }

            val requestedFile = File(staticDir, path)
            
            // Security check: ensure path doesn't escape the static directory
            if (requestedFile.exists() && 
                requestedFile.isFile && 
                requestedFile.canonicalPath.startsWith(staticDir.canonicalPath)) {
                call.respondFile(requestedFile)
            } else {
                // File not found - serve custom 404 page
                call.respondText(notFoundHtml, ContentType.Text.Html, HttpStatusCode.NotFound)
            }
        }
    }

    println("Server is running on http://localhost:8080")
    println("API documentation available at http://localhost:8080/api")
}
