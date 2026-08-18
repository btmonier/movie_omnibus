# CLI Utilities

Command-line tools for managing and inspecting your movie database.

## Table of Contents

- [List Movies](#list-movies)
- [Compare Data](#compare-data)
- [Migrate Data](#migrate-data)
- [Scrape Letterboxd](#scrape-letterboxd)
- [Database Management](#database-management)
- [Server](#server)

## List Movies

List all movie titles from the database.

```bash
# List all titles (sorted alphabetically)
./gradlew listMovies

# Only show count
./gradlew listMovies --args="--count"

# Include Letterboxd URLs
./gradlew listMovies --args="--with-url"

# Output to file
./gradlew listMovies --args="--output movies.txt"

# Output with URLs to file
./gradlew listMovies --args="--output movies.txt --with-url"
```

| Option | Short | Description |
|--------|-------|-------------|
| `--count` | `-c` | Only show the count |
| `--with-url` | `-u` | Include Letterboxd URL for each movie |
| `--output` | `-o` | Write titles to a file |

## Compare Data

Compare movies in the database with a JSON file to preview imports.

```bash
# Basic comparison
./gradlew compareData --args="--input output/movies.json"

# Also show movies that exist in both
./gradlew compareData --args="--input output/movies.json --show-existing"
```

| Option | Short | Description |
|--------|-------|-------------|
| `--input` | `-i` | Path to JSON file (required) |
| `--show-existing` | `-e` | Also list movies that exist in both |

**Output shows:**
- New movies (in file, not in database)
- Database only (in database, not in file)
- Already exist (in both) — with `--show-existing`

## Migrate Data

Import movies from a JSON file into the database.

```bash
# Import new movies only
./gradlew migrateData --args="--input output/movies.json --skip-existing"

# Import and update existing
./gradlew migrateData --args="--input output/movies.json --update-existing"
```

| Option | Description |
|--------|-------------|
| `--input` | Path to JSON file (required) |
| `--skip-existing` | Skip movies that already exist |
| `--update-existing` | Update movies that already exist |

## Scrape Letterboxd

Bulk scrape movie metadata from Letterboxd URLs in a CSV file.

```bash
# Basic scrape
./gradlew scrapeLetterboxd --args="--input data/movies.csv"

# With progress bar and limit
./gradlew scrapeLetterboxd --args="--input data/movies.csv --limit 10 --progress"

# Custom output
./gradlew scrapeLetterboxd --args="--input data/movies.csv --outdir results --prefix my_movies"
```

| Option | Default | Description |
|--------|---------|-------------|
| `--input` | required | Path to CSV with URL column |
| `--skip` | 4 | Header lines to skip |
| `--workers` | 8 | Parallel workers |
| `--outdir` | output | Output directory |
| `--prefix` | movie_collection_meta | Filename prefix |
| `--datetime` | false | Use datetime stamp |
| `--limit` | none | Process only first N rows |
| `--progress` | false | Show progress bar |

## Database Management

```bash
# Reset database (WARNING: deletes all data)
./gradlew resetDatabase --args="--force"

# Backup database
./gradlew backupDatabase
```

### Migrate Releases

Fold the old per-film physical media rows into shared releases, so a box set is stored once instead of once per film on it. `runServer` does this automatically on startup; this task exists to preview the grouping first.

```bash
# Back up before the first run
./gradlew backupDatabase

# Report what would be merged, without writing
./gradlew migrateReleases --args="--dry-run"

# List every shared release rather than the ten largest
./gradlew migrateReleases --args="--dry-run --verbose"

# Apply it
./gradlew migrateReleases
```

| Option | Description |
|--------|-------------|
| `--dry-run` | Report the grouping without writing anything |
| `--verbose` | List every release shared by more than one film |

The old tables are renamed to `physical_media_legacy` and friends rather than dropped, so the original data survives for verification. Repeat runs do nothing. See [Migrating to shared releases](DATABASE_SETUP.md#migrating-to-shared-releases) for how rows are grouped.

## Server

```bash
# Start web server
./gradlew runServer
```

Server runs at http://localhost:8080

