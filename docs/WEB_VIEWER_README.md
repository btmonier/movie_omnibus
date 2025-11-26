# Movie Collection Web Viewer

A Kotlin/JS web application for managing and viewing your movie collection with Letterboxd integration.

## Features

### Movie Collection Management
- **Search by Title**: Real-time text search across main titles and alternate titles
- **Filter by Genre**: Multi-select dropdown to filter movies by genre(s)
- **Filter by Country**: Multi-select dropdown to filter movies by country/countries
- **Filter by Media Type**: Filter by physical media type (VHS, DVD, Blu-ray, 4K, Digital)
- **Sortable Columns**: Click column headers to sort
- **Pagination**: Navigate large collections with configurable page sizes (10, 25, 50, 100)
- **Tag-based Filtering**: Visual tags show active filters with easy removal

### Adding Movies
- **Letterboxd Integration**: Enter a Letterboxd URL to automatically scrape movie data
- **Auto-populated Forms**: Title, description, genres, themes, countries, cast, crew, release year, and runtime are fetched automatically
- **Duplicate Detection**: Prevents adding movies that already exist in your collection
- **Manual Entry**: Option to enter all details manually if preferred

### Physical Media Tracking
- **Multiple Media Types**: Track VHS, DVD, Blu-ray, 4K UHD, and Digital copies
- **Entry Organization**: Use letters (A, B, C) to organize multiple copies
- **Detailed Information**: Distributor, release date, location, Blu-ray.com links
- **Image Gallery**: Upload and view cover art and media photos

### Watch History
- **Track Viewings**: Record when you watched each movie
- **Ratings**: Rate movies from 0-5 (supports half-stars)
- **Notes**: Add personal viewing notes and thoughts

### Genre Management
- **Manage Genres**: Add and delete genres from the master list
- **Manage Subgenres**: Add and delete subgenres for finer categorization
- **Usage Tracking**: See how many movies use each genre/subgenre

### Random Movie Picker
- **Unwatched Only**: Randomly selects from movies you haven't watched
- **Filters**: Narrow down by genre, subgenre, country, or media type
- **Available Count**: Shows how many movies match your current filters
- **Physical Media Display**: See what format and where it's located

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

### With the Backend Server (Recommended)

The web viewer is designed to work with the Ktor backend server:

```bash
# Build the frontend
./gradlew jsBrowserDevelopmentWebpack

# Start the server (serves both API and frontend)
./gradlew runServer
```

The application will be available at http://localhost:8080

### Development with Auto-reload

For frontend development with auto-reload:
```bash
./gradlew jsBrowserDevelopmentRun --continuous
```

Note: This runs only the frontend. You'll need the backend server running separately for full functionality.

## Project Structure

```
src/
├── commonMain/kotlin/org/btmonier/
│   └── MovieMetadata.kt        # Shared data classes
├── jvmMain/kotlin/
│   ├── org/btmonier/
│   │   ├── database/           # Database schema and DAOs
│   │   ├── migration/          # Data migration tools
│   │   └── server/             # Ktor server and API routes
│   ├── LetterboxdScraper.kt    # CLI scraper tool
│   └── ScraperUtils.kt         # Scraping utilities
└── jsMain/kotlin/org/btmonier/
    ├── Main.kt                 # Application entry point and API client
    ├── MovieTable.kt           # Main collection view
    ├── MovieForm.kt            # Add/Edit movie form
    ├── RandomMoviePicker.kt    # Random movie picker page
    ├── GenreManagementUI.kt    # Genre/subgenre management
    ├── GenreSelector.kt        # Genre selection component
    ├── PhysicalMediaForm.kt    # Physical media entry form
    ├── WatchedForm.kt          # Watch history entry form
    ├── MovieMetadataModal.kt   # Movie details popup
    ├── LetterboxdUrlForm.kt    # URL input for scraping
    ├── AlertDialog.kt          # Alert dialog component
    ├── ConfirmDialog.kt        # Confirmation dialog component
    └── Footer.kt               # Shared footer component
```

## Technology Stack

- **Kotlin Multiplatform**: Shared code between JVM and JS targets
- **Kotlin/JS**: Frontend compiled to JavaScript
- **Ktor**: Backend web server with REST API
- **Exposed**: SQL ORM for database access
- **PostgreSQL**: Database backend
- **kotlinx.html**: DSL for generating HTML
- **kotlinx.serialization**: JSON serialization
- **kotlinx.coroutines**: Asynchronous operations
- **Webpack**: Module bundling

## Configuration

### Build Configuration

The version is configured in `build.gradle.kts`:
```kotlin
version = "0.1"
```

This version is automatically injected into the frontend via a generated `BuildConfig` object.

### Database Configuration

See [DATABASE_SETUP.md](DATABASE_SETUP.md) for database configuration instructions.

## Building for Production

For a production-ready build with optimizations:

```bash
./gradlew jsBrowserProductionWebpack
```

The optimized output will be in `build/kotlin-webpack/js/productionExecutable/`

To deploy, run the server which serves the frontend automatically:
```bash
./gradlew runServer
```

## Development Notes

### Multiplatform Architecture

The application uses Kotlin Multiplatform to share code:
- **commonMain**: Data classes (`MovieMetadata`, `PhysicalMedia`, `WatchedEntry`) shared by all targets
- **jvmMain**: Server, database access, and scraping tools
- **jsMain**: Web frontend components

### API Integration

The frontend communicates with the backend via REST API calls defined in `Main.kt`. All API functions are suspending functions using coroutines for async operations.

### Component Architecture

UI components follow a consistent pattern:
- Each component manages its own state
- Components render using kotlinx.html DSL
- Event handlers use coroutines for async operations
- Dialogs (Alert, Confirm) are reusable across components
