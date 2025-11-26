# Database and Web Interface Setup Guide

This guide explains how to set up and use the PostgreSQL database backend and web interface for the Movie Omnibus project.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Database Setup](#database-setup)
- [Running the Application](#running-the-application)
- [Data Migration](#data-migration)
- [Web Interface](#web-interface)
- [API Documentation](#api-documentation)
- [Troubleshooting](#troubleshooting)

## Prerequisites

1. **PostgreSQL** (version 12 or higher)
   - Download from: https://www.postgresql.org/download/
   - Or use Docker: `docker run --name moviedb -e POSTGRES_PASSWORD=postgres -p 5432:5432 -d postgres`

2. **Java 21** (required by the project)

3. **Gradle** (included via wrapper)

## Database Setup

### Step 1: Create the Database

Connect to PostgreSQL and create the database:

```sql
-- Connect to PostgreSQL (using psql or your preferred client)
psql -U postgres

-- Create the database
CREATE DATABASE moviedb;

-- Exit psql
\q
```

### Step 2: Configure Database Connection

The application can be configured using environment variables or a properties file:

#### Option A: Environment Variables (Recommended for production)

```bash
# Linux/Mac
export DATABASE_URL="jdbc:postgresql://localhost:5432/moviedb"
export DB_USER="postgres"
export DB_PASSWORD="postgres"

# Windows PowerShell
$env:DATABASE_URL="jdbc:postgresql://localhost:5432/moviedb"
$env:DB_USER="postgres"
$env:DB_PASSWORD="postgres"

# Windows Command Prompt
set DATABASE_URL=jdbc:postgresql://localhost:5432/moviedb
set DB_USER=postgres
set DB_PASSWORD=postgres
```

#### Option B: Properties File (For development)

Copy the example configuration file:

```bash
cp database.properties.example database.properties
```

Edit `database.properties` with your credentials:

```properties
database.url=jdbc:postgresql://localhost:5432/moviedb
database.user=postgres
database.password=your_password_here
```

**Note:** The `database.properties` file is git-ignored for security. Never commit it to version control!

### Step 3: Initialize Database Tables

The database tables will be created automatically when you first run the server or migration tool. The application uses Exposed ORM with automatic schema creation.

The following tables will be created:
- `movies` - Main movie information
- `movie_genres` - Movie genres (many-to-many)
- `movie_themes` - Movie themes (many-to-many)
- `movie_countries` - Movie countries (many-to-many)
- `movie_cast` - Movie cast members (many-to-many)
- `movie_crew` - Movie crew with roles (many-to-many)

## Running the Application

### 1. Build the Project

```bash
./gradlew build
```

### 2. Build the Frontend

Build the Kotlin/JS frontend:

```bash
./gradlew jsBrowserDevelopmentWebpack
```

For production builds:

```bash
./gradlew jsBrowserProductionWebpack
```

### 3. Start the Web Server

```bash
./gradlew runServer
```

The server will start on http://localhost:8080

You should see output like:
```
Initializing database connection...
Database initialized successfully!
Server is running on http://localhost:8080
API documentation available at http://localhost:8080/api
```

### 4. Access the Web Interface

Open your browser and navigate to:
- **Web UI**: http://localhost:8080
- **API Info**: http://localhost:8080/api
- **Health Check**: http://localhost:8080/health

## Data Migration

### Migrate JSON Data to Database

If you have existing JSON files from the scraper, you can import them into the database:

```bash
./gradlew migrateData --args="--input output/movie_collection_meta_20251111.json --skip-existing"
```

#### Migration Options:

- `--input` (required): Path to the JSON file
- `--skip-existing`: Skip movies that already exist in the database (by URL)
- `--update-existing`: Update movies that already exist with new data

#### Examples:

```bash
# Import new movies only (skip duplicates)
./gradlew migrateData --args="--input output/movies.json --skip-existing"

# Import and update existing movies
./gradlew migrateData --args="--input output/movies.json --update-existing"

# Import all movies (will fail on duplicates)
./gradlew migrateData --args="--input output/movies.json"
```

## Web Interface

### Features

The web interface provides a full CRUD (Create, Read, Update, Delete) interface for managing your movie collection:

#### Search and Filter
- **Search by title**: Use the search box to find movies by name
- **Filter by genre**: Select one or more genres from the dropdown
- **Filter by country**: Select one or more countries from the dropdown
- **Sortable columns**: Click column headers to sort by that field

#### Create Movies
1. Click the **"+ Add Movie"** button at the top right
2. Fill in the movie details:
   - Title and URL are required
   - Other fields are optional
   - For multiple values (genres, themes, cast), use comma-separated lists
3. Click **"Save"** to create the movie

#### Edit Movies
1. Click the **"Edit"** button next to any movie in the table
2. Modify the movie details in the form
3. Click **"Save"** to update the movie

#### Delete Movies
1. Click the **"Delete"** button next to any movie in the table
2. Confirm the deletion in the popup dialog
3. The movie will be permanently removed from the database

### User Interface

The web interface uses Google Material Design principles with:
- Clean, modern styling
- Responsive layout
- Interactive filters with tag displays
- Hover effects and visual feedback
- Confirmation dialogs for destructive actions

## API Documentation

### Base URL

```
http://localhost:8080/api
```

### Endpoints

#### Get All Movies
```
GET /api/movies
```

Returns a JSON array of all movies in the database.

#### Get Movie by ID
```
GET /api/movies/{id}
```

Returns a single movie by its database ID.

#### Search Movies
```
GET /api/movies/search?q={query}
```

Search movies by title (case-insensitive partial match).

#### Filter by Genre
```
GET /api/movies/by-genre/{genre}
```

Get all movies with a specific genre.

#### Filter by Country
```
GET /api/movies/by-country/{country}
```

Get all movies from a specific country.

#### Create Movie
```
POST /api/movies
Content-Type: application/json

{
  "url": "https://letterboxd.com/film/movie-name/",
  "title": "Movie Title",
  "genres": ["Drama", "Thriller"],
  "themes": ["Coming of Age"],
  "country": ["USA"],
  "cast": ["Actor 1", "Actor 2"],
  "crew": {},
  "release_date": 2024,
  "runtime_mins": 120
}
```

#### Update Movie
```
PUT /api/movies/{id}
Content-Type: application/json

{
  "url": "https://letterboxd.com/film/movie-name/",
  "title": "Updated Title",
  ...
}
```

#### Delete Movie
```
DELETE /api/movies/{id}
```

### Response Formats

Success responses return JSON data:
```json
{
  "id": 1,
  "url": "https://letterboxd.com/film/movie-name/",
  "title": "Movie Title",
  "genres": ["Drama"],
  "themes": ["Love"],
  "country": ["USA"],
  "cast": [],
  "crew": {},
  "release_date": 2024,
  "runtime_mins": 120
}
```

Error responses return:
```json
{
  "error": "Error message description"
}
```

## Troubleshooting

### Database Connection Issues

**Problem**: `Connection refused` or `could not connect to server`

**Solutions**:
1. Ensure PostgreSQL is running:
   ```bash
   # Check PostgreSQL status
   # Linux/Mac:
   pg_isready
   # Or:
   sudo systemctl status postgresql

   # Windows:
   # Check Services app for PostgreSQL service
   ```

2. Verify connection details in environment variables or `database.properties`

3. Check PostgreSQL is listening on the correct port (default 5432):
   ```sql
   -- In psql:
   SHOW port;
   ```

### Frontend Not Loading

**Problem**: Frontend shows blank page or "Failed to load movie data"

**Solutions**:
1. Make sure the server is running on port 8080
2. Build the frontend first:
   ```bash
   ./gradlew jsBrowserDevelopmentWebpack
   ```
3. Check browser console for errors (F12)
4. Verify the API is accessible: http://localhost:8080/api/movies

### Migration Errors

**Problem**: Migration fails with "Movie already exists"

**Solution**: Use the `--skip-existing` flag:
```bash
./gradlew migrateData --args="--input output/movies.json --skip-existing"
```

**Problem**: Migration fails with authentication errors

**Solution**:
1. Check database credentials
2. Ensure the database user has INSERT permissions:
   ```sql
   GRANT ALL PRIVILEGES ON DATABASE moviedb TO postgres;
   ```

### Port Already in Use

**Problem**: Server fails to start with "Address already in use"

**Solutions**:
1. Find and stop the process using port 8080:
   ```bash
   # Linux/Mac:
   lsof -i :8080
   kill <PID>

   # Windows PowerShell:
   netstat -ano | findstr :8080
   taskkill /PID <PID> /F
   ```

2. Or change the port in `Server.kt` (line: `embeddedServer(Netty, port = 8080...`)

## Complete Workflow Example

Here's a complete example workflow from scratch:

```bash
# 1. Set up PostgreSQL database
createdb moviedb

# 2. Set environment variables
export DATABASE_URL="jdbc:postgresql://localhost:5432/moviedb"
export DB_USER="postgres"
export DB_PASSWORD="postgres"

# 3. Build the project
./gradlew build

# 4. Scrape some movie data (optional)
./gradlew scrapeLetterboxd --args="--input data/movies.csv --limit 10"

# 5. Import the scraped data to database
./gradlew migrateData --args="--input output/movie_collection_meta_*.json --skip-existing"

# 6. Build the frontend
./gradlew jsBrowserDevelopmentWebpack

# 7. Start the web server
./gradlew runServer

# 8. Open browser to http://localhost:8080
```

Now you can browse, search, and manage your movie collection through the web interface!

## Production Deployment

For production deployment, consider:

1. **Use proper PostgreSQL credentials** (not default postgres/postgres)
2. **Enable SSL for database connections**
3. **Build optimized frontend**: `./gradlew jsBrowserProductionWebpack`
4. **Configure CORS properly** in `Server.kt` (don't use `anyHost()`)
5. **Set up reverse proxy** (nginx/Apache) in front of Ktor
6. **Use environment variables** for all sensitive configuration
7. **Set up database backups**
8. **Monitor logs and errors**

## Database Backup

### Creating Backups

Create a backup of your database using the built-in Gradle task:

```bash
./gradlew backupDatabase
```

This will:
1. Read database credentials from `database.properties`, environment variables, or use defaults
2. Create a `backups/` directory in your project root
3. Generate a timestamped SQL backup file: `moviedb_backup_YYYYMMDD_HHMMSS.sql`
4. Use PostgreSQL's `pg_dump` utility with verbose output

**Requirements**:
- PostgreSQL client tools must be installed (`pg_dump` must be in PATH)
- On Windows: Install PostgreSQL and add `C:\Program Files\PostgreSQL\<version>\bin` to PATH
- On Linux: `sudo apt install postgresql-client` (Ubuntu/Debian) or `sudo yum install postgresql` (RHEL/CentOS)
- On Mac: `brew install postgresql`

### Restoring from Backup

To restore a database from a backup file:

```bash
# Drop existing database (WARNING: destroys all data!)
dropdb moviedb

# Create fresh database
createdb moviedb

# Restore from backup
psql -U postgres -d moviedb -f backups/moviedb_backup_20251112_143022.sql
```

Or using environment variables:

```bash
psql -h localhost -p 5432 -U postgres -d moviedb -f backups/moviedb_backup_20251112_143022.sql
```

### Automated Backups

For production, consider setting up automated backups using cron (Linux/Mac) or Task Scheduler (Windows):

**Example cron job (daily at 2 AM)**:
```bash
0 2 * * * cd /path/to/movie_omnibus && ./gradlew backupDatabase
```

**Example: Keep only last 7 days of backups**:
```bash
# Add to your backup script
find backups/ -name "moviedb_backup_*.sql" -mtime +7 -delete
```

## Support

For issues or questions:
- Check the main [CLAUDE.md](CLAUDE.md) for project overview
- Review Gradle tasks: `./gradlew tasks --group application`
- Check server logs when running `./gradlew runServer`
