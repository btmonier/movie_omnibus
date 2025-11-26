# Movie Collection Web Viewer

A Kotlin/JS web application for viewing and filtering movie metadata from Letterboxd.

## Features

- **Search by Title**: Real-time text search to find movies by name
- **Filter by Genre**: Multi-select dropdown to filter movies by genre(s)
- **Filter by Country**: Multi-select dropdown to filter movies by country/countries
- **Sortable Columns**: Click column headers to sort by Title, Genres, Country, or Letterboxd URL
- **Responsive Design**: Clean, modern UI that works across different screen sizes
- **Tag-based Filtering**: Visual tags show active filters with easy removal

## Building the Application

### Development Build
```bash
./gradlew jsBrowserDevelopmentWebpack
```

### Production Build
```bash
./gradlew jsBrowserProductionWebpack
```

## Running the Application

### Option 1: Development Server (Recommended)
Start the webpack development server:
```bash
./gradlew jsBrowserDevelopmentRun --continuous
```

The application will be available at http://localhost:8080

The `--continuous` flag enables auto-reload when you make changes to the source code.

### Option 2: Serve Static Files
After building, you can serve the files from:
```
build/kotlin-webpack/js/developmentExecutable/
```

Using any static file server. For example with Python:
```bash
cd build/kotlin-webpack/js/developmentExecutable
python -m http.server 8080
```

Or with Node.js (`npx` comes with Node.js):
```bash
cd build/kotlin-webpack/js/developmentExecutable
npx http-server -p 8080
```

Then open http://localhost:8080 in your browser.

## Project Structure

- `src/commonMain/kotlin/` - Shared code (MovieMetadata data class)
- `src/jvmMain/kotlin/` - JVM-specific code (Letterboxd scraper)
- `src/jsMain/kotlin/` - Kotlin/JS web application code
  - `Main.kt` - Application entry point
  - `MovieTable.kt` - Main UI component with filtering and sorting logic
- `src/jsMain/resources/` - Static resources
  - `index.html` - HTML entry point
  - `movie_collection_processed_20251111.json` - Movie data

## Technology Stack

- **Kotlin/JS**: Kotlin compiled to JavaScript
- **kotlinx.html**: DSL for generating HTML
- **kotlinx.serialization**: JSON parsing
- **kotlinx.coroutines**: Asynchronous data loading
- **Webpack**: Module bundling and development server

## Adding New Movie Data

To use a different JSON file:

1. Copy your JSON file to `src/jsMain/resources/`
2. Update the filename in `Main.kt` (line 17):
   ```kotlin
   val response = window.fetch("your_file_name.json").await()
   ```
3. Rebuild the application

## Development Notes

The application uses a multiplatform Kotlin project structure, allowing code sharing between:
- JVM target (for the Letterboxd scraper)
- JS target (for the web viewer)

The `MovieMetadata` data class is defined in `commonMain` and used by both targets.

## Building for Production

For a production-ready build with optimizations:

```bash
./gradlew jsBrowserProductionWebpack
```

The optimized output will be in `build/kotlin-webpack/js/productionExecutable/`
