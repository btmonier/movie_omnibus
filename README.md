# Movie Omnibus

A Kotlin-based web scraper for extracting movie metadata from Letterboxd, including genres, themes, and country information.

## Features

- **Parallel Processing**: Scrape multiple URLs concurrently using Kotlin coroutines
- **Flexible CLI**: Command-line interface with comprehensive options for customization
- **CSV Input**: Read movie URLs from CSV files with configurable skip lines
- **JSON Output**: Export scraped data as pretty-printed JSON, sorted by title
- **Polite Scraping**: Built-in random delays to avoid overwhelming servers
- **Visual Progress Bar**: Optional ASCII progress bar with completion tracking
- **Error Handling**: Graceful handling of failed requests

## Requirements

- Java 21 or higher
- Gradle (wrapper included)

## Installation

Clone the repository and build the project:

```bash
./gradlew build
```

This will download all dependencies and compile the project.

### Viewing Available Tasks

To see all available Gradle tasks:

```bash
# List all tasks
./gradlew tasks

# List only application tasks
./gradlew tasks --group application
```

## Usage

### Basic Example

```bash
./gradlew scrapeLetterboxd --args="--input data/movies.csv"
```

### Common Usage Patterns

```bash
# Test with limited rows and show progress
./gradlew scrapeLetterboxd --args="--input data/movies.csv --limit 10 --progress"

# Customize output directory and filename
./gradlew scrapeLetterboxd --args="--input data/movies.csv --outdir results --prefix letterboxd_data"

# Increase parallel workers for faster scraping
./gradlew scrapeLetterboxd --args="--input data/movies.csv --workers 16"

# Use datetime stamp for output filename
./gradlew scrapeLetterboxd --args="--input data/movies.csv --datetime"

# Skip different number of header lines
./gradlew scrapeLetterboxd --args="--input data/movies.csv --skip 0"
```

**Alternative**: You can also use the generic `./gradlew run --args="..."` command, but `scrapeLetterboxd` is the recommended dedicated task.

### CLI Options

| Option | Default | Description |
|--------|---------|-------------|
| `--input` | *required* | Path to CSV file with 'URL' column |
| `--skip` | 4 | Number of header lines to skip in CSV |
| `--workers` | 8 | Number of parallel workers for scraping |
| `--outdir` | output | Output directory for JSON file |
| `--prefix` | movie_collection_meta | Output filename prefix |
| `--datetime` | false | Use datetime stamp (YYYYMMDD_HHMM) vs date (YYYYMMDD) |
| `--limit` | none | Process only first N rows (useful for testing) |
| `--progress` | false | Show visual progress bar during scraping |

## Input Format

The input CSV must contain:
- **Required**: A `URL` column with Letterboxd movie URLs
- **Optional**: A `Name` or `Title` column for movie titles

For example:

```csv
Title,Year,URL
The Shawshank Redemption,1994,https://letterboxd.com/film/the-shawshank-redemption/
The Godfather,1972,https://letterboxd.com/film/the-godfather/
```

The scraper will skip the specified number of header lines (default: 4) to accommodate CSVs with metadata rows.

**Note**: If a `Name` or `Title` column is present, the movie title will be taken from the CSV. Otherwise, the title field will be empty in the output.

## Output Format

The scraper generates a timestamped JSON file containing an array of movie metadata objects:

```json
[
  {
    "url": "https://letterboxd.com/film/the-shawshank-redemption/",
    "title": "The Shawshank Redemption",
    "genres": ["Drama"],
    "themes": ["Prison", "Friendship"],
    "country": ["United States"]
  }
]
```

Each movie object includes:
- **url**: The Letterboxd URL for the movie
- **title**: The movie title from the CSV input
- **genres**: List of genres
- **themes**: List of themes
- **country**: List of countries

**Output is automatically sorted alphabetically by movie title.**

Output files are named using the pattern: `{prefix}_{timestamp}.json`

## Project Structure

```
movie_omnibus/
├── src/
│   ├── main/kotlin/
│   │   ├── Main.kt                  # Original Hello World example
│   │   ├── LetterboxdScraper.kt     # Main scraper CLI implementation
│   │   ├── ScraperUtils.kt          # Scraping utility functions
│   │   └── MovieMetadata.kt         # Data model (in LetterboxdScraper.kt)
│   └── test/
│       ├── kotlin/
│       │   ├── ScraperUtilsTest.kt  # Tests for scraping logic
│       │   ├── MovieMetadataTest.kt # Tests for JSON serialization
│       │   └── CsvReadingTest.kt    # Tests for CSV reading
│       └── resources/
│           ├── sample_movie_page.html  # Test HTML fixture
│           └── test_movies.csv         # Test CSV file
├── scripts/
│   └── letterboxd_scraper.R     # Original R implementation
├── build.gradle.kts              # Gradle build configuration
├── settings.gradle.kts           # Gradle settings
├── CLAUDE.md                     # AI assistant guidance
└── README.md                     # This file
```

## Technology Stack

### Main Dependencies
- **Kotlin 2.2.21**: Modern JVM language with coroutines support
- **JSoup 1.17.2**: HTML parsing and web scraping
- **Kotlin DataFrame 0.14.1**: CSV reading and data manipulation
- **Clikt 5.0.1**: Command-line interface framework
- **kotlinx.serialization 1.7.3**: JSON serialization
- **kotlinx.coroutines 1.9.0**: Asynchronous and parallel processing
- **ProgressBar 0.10.1**: Visual progress bar for terminal output

### Test Dependencies
- **kotlin-test**: Kotlin testing framework (JUnit Platform)
- **MockK 1.13.13**: Mocking library for Kotlin
- **kotlinx.coroutines-test 1.9.0**: Testing utilities for coroutines

## Development

### Running Tests

The project includes comprehensive unit tests covering HTML parsing, JSON serialization, and CSV reading.

```bash
# Run all tests
./gradlew test

# Run tests with detailed output
./gradlew test --info

# Run a specific test class
./gradlew test --tests "ScraperUtilsTest"

# Run a specific test method
./gradlew test --tests "ScraperUtilsTest.scrapeByHref extracts genres correctly"

# Run tests continuously during development
./gradlew test --continuous
```

### Test Coverage

The test suite includes:
- **ScraperUtilsTest**: 13 tests for HTML parsing, title extraction, and metadata extraction
- **MovieMetadataTest**: 5 tests for JSON serialization/deserialization
- **CsvReadingTest**: 4 tests for CSV file reading with DataFrame

Total: 22 tests covering all core functionality

### Building a Distribution

```bash
./gradlew installDist
```

This creates a standalone distribution in `build/install/movie_omnibus/` with launcher scripts.

### Code Organization

- **ScraperUtils.kt**: Contains reusable scraping functions (`scrapeByHref`, `extractTitle`, `extractMetadata`, `extractMetadataWithTitle`)
- **LetterboxdScraper.kt**: CLI application with parallel scraping logic and JSON output sorting
- **MovieMetadata.kt**: Data class for movie metadata (defined in LetterboxdScraper.kt)

## Notes

- Movie titles are read from the CSV input (`Name` or `Title` column) rather than scraped from web pages
- JSON output is automatically sorted alphabetically by movie title for easier browsing
- The scraper includes random delays (250-750ms) between requests to be respectful to Letterboxd's servers
- Default parallelism is set to 8 workers; adjust based on your network and ethical considerations
- Failed requests are logged but don't stop the overall scraping process
- The original R implementation is available in `scripts/letterboxd_scraper.R` for comparison

## License

This project is provided as-is for educational and personal use.
