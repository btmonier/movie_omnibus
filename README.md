# Movie Omnibus

A Kotlin Multiplatform application for managing your movie collection with Letterboxd integration.

## Quick Start

**Requirements:** Java 21+, PostgreSQL

```bash
# Setup database (see docs/DATABASE_SETUP.md)
cp database.properties.example database.properties
# Edit database.properties with your credentials

# Build and run
./gradlew jsBrowserDevelopmentWebpack
./gradlew runServer
```

Open http://localhost:8080

## Features

- **Movie Management** — Add, edit, delete movies with full metadata
- **Letterboxd Scraping** — Auto-fetch movie data from Letterboxd URLs
- **Physical Media Tracking** — Track VHS, DVD, Blu-ray, 4K, Digital copies
- **Watch History** — Log viewings with dates, ratings, and notes
- **Random Picker** — Get random unwatched movie suggestions with filters
- **Search & Filter** — By title, genre, country, media type

## Gradle Tasks

| Task | Description |
|------|-------------|
| `./gradlew runServer` | Start the web server |
| `./gradlew jsBrowserDevelopmentWebpack` | Build frontend |
| `./gradlew scrapeLetterboxd --args="--input file.csv"` | Bulk scrape from CSV |
| `./gradlew migrateData --args="--input file.json"` | Import JSON to database |
| `./gradlew test` | Run tests |

## Documentation

- [Database Setup](docs/DATABASE_SETUP.md) — PostgreSQL setup, API reference
- [Web Viewer](docs/WEB_VIEWER_README.md) — Frontend architecture

## Tech Stack

Kotlin Multiplatform · Ktor · Exposed · PostgreSQL · kotlinx.html · JSoup

## License

For educational and personal use.
