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

* Add, edit, delete movies with full metadata
* Auto-fetch movie data from Letterboxd URLs
* Track physical media - VHS, DVD, Blu-ray, 4K, and Digital
* Log viewings with dates, ratings, and notes
* Search and filter by title, genre, country, media type
* Get random unwatched movie suggestions with filters!
  + Heavily inspired by Goodbyte's [Watchlist Picker](https://watchlistpicker.com/)


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

* Kotlin Multiplatform
* Ktor
* Exposed
* PostgreSQL
* kotlinx.html
* JSoup

## Disclaimer

This is intended for educational and personal use only. I made this in order to get a better understanding
of PostgreSQL, REST APIs, and Kotlin Multiplatform
