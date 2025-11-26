# Database and Web Interface Setup Guide

This guide explains how to set up and use the PostgreSQL database backend and web interface for the Movie Omnibus project.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Database Setup](#database-setup)
- [Running the Application](#running-the-application)
- [Data Migration](#data-migration)
- [Web Interface](#web-interface)
- [API Documentation](#api-documentation)

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

| Table | Description |
|-------|-------------|
| `movies` | Main movie information (title, URL, description, release date, runtime) |
| `movie_alternate_titles` | Alternate titles for movies |
| `genres` | Master list of available genres |
| `subgenres` | Master list of available subgenres |
| `movie_genres` | Links movies to genres |
| `movie_subgenres` | Links movies to subgenres |
| `movie_themes` | Movie themes |
| `movie_countries` | Movie countries of origin |
| `movie_cast` | Cast members |
| `movie_crew` | Crew members with roles |
| `physical_media` | Physical media entries (Blu-ray, DVD, etc.) |
| `physical_media_types` | Media types for each physical media entry |
| `physical_media_images` | Images of physical media |
| `watched_entries` | Watch history with dates, ratings, and notes |

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

The web interface provides a full CRUD (Create, Read, Update, Delete) interface for managing your movie collection.

#### Movie Collection View

- **Search by title**: Use the search box to find movies by name (searches main title and alternate titles)
- **Filter by genre**: Select one or more genres from the dropdown
- **Filter by country**: Select one or more countries from the dropdown
- **Filter by media type**: Filter by physical media type (VHS, DVD, Blu-ray, 4K, Digital)
- **Sortable columns**: Click column headers to sort
- **Pagination**: Navigate through large collections with configurable page sizes

#### Adding Movies

1. Click the **"+ Add Movie"** button
2. Enter a **Letterboxd URL** (e.g., `https://letterboxd.com/film/the-godfather/`)
3. Click **"Fetch Movie Data"** to automatically scrape movie information
4. Review and edit the pre-populated form as needed
5. Click **"Save"** to add the movie

Alternatively, click **"enter details manually"** to skip scraping and fill in all fields yourself.

#### Editing Movies

1. Click the **"Edit"** button next to any movie
2. Modify the movie details in the form
3. Manage **Physical Media** entries (add/edit/delete)
4. Manage **Watched Entries** (track viewing history with ratings and notes)
5. Click **"Save"** to update

#### Physical Media Management

Track your physical movie collection:
- **Media Types**: VHS, DVD, Blu-ray, 4K UHD, Digital
- **Entry Letter**: Organize multiple copies (A, B, C, etc.)
- **Title**: Custom title for box sets or special editions
- **Distributor**: Release distributor
- **Release Date**: Physical media release date
- **Location**: Where it's stored (Archive, Shelf, etc.)
- **Images**: Upload cover art and photos
- **Blu-ray.com Link**: Link to Blu-ray.com listing

#### Watched Entries

Track your viewing history:
- **Watch Date**: When you watched the movie
- **Rating**: Rate from 0-5 (supports half-stars)
- **Notes**: Add viewing notes or thoughts

#### Genre Management

Click **"Manage Genres"** to:
- Add new genres and subgenres
- Delete unused genres and subgenres
- View usage counts

#### Random Movie Picker

Click **"Random Pick"** to get a random unwatched movie recommendation:
- Filter by genre, subgenre, country, or media type
- See how many unwatched movies match your filters
- View full movie details including physical media information
- Pick again or open on Letterboxd

### User Interface

The web interface features:
- Clean, modern Google Material Design styling
- Responsive layout
- Interactive filters with tag displays
- Hover effects and visual feedback
- Confirmation dialogs for destructive actions
- Version information in footer

## API Documentation

### Base URL

```
http://localhost:8080/api
```

### Movie Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/movies` | Get all movies |
| GET | `/api/movies/{id}` | Get movie by ID |
| GET | `/api/movies/search?q={query}` | Search movies by title |
| GET | `/api/movies/by-genre/{genre}` | Filter by genre |
| GET | `/api/movies/by-country/{country}` | Filter by country |
| GET | `/api/movies/random` | Get random unwatched movie |
| GET | `/api/movies/random/count` | Count unwatched movies |
| GET | `/api/movies/genres` | Get all genres |
| GET | `/api/movies/subgenres` | Get all subgenres |
| GET | `/api/movies/countries` | Get all countries |
| GET | `/api/movies/media-types` | Get all media types |
| POST | `/api/movies` | Create new movie |
| POST | `/api/movies/scrape` | Scrape movie from Letterboxd URL |
| PUT | `/api/movies/{id}` | Update movie |
| DELETE | `/api/movies/{id}` | Delete movie |

### Physical Media Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/movies/{id}/physical-media` | Get physical media for movie |
| POST | `/api/movies/{id}/physical-media` | Add physical media to movie |
| PUT | `/api/physical-media/{id}` | Update physical media entry |
| DELETE | `/api/physical-media/{id}` | Delete physical media entry |

### Watched Entry Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/movies/{id}/watched` | Get watched entries for movie |
| POST | `/api/movies/{id}/watched` | Add watched entry |
| PUT | `/api/watched/{id}` | Update watched entry |
| DELETE | `/api/watched/{id}` | Delete watched entry |

### Genre Management Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/genres` | Get all genres |
| POST | `/api/genres` | Create new genre |
| DELETE | `/api/genres/{id}` | Delete genre |
| GET | `/api/subgenres` | Get all subgenres |
| POST | `/api/subgenres` | Create new subgenre |
| DELETE | `/api/subgenres/{id}` | Delete subgenre |

### Example: Create Movie

```http
POST /api/movies
Content-Type: application/json

{
  "url": "https://letterboxd.com/film/movie-name/",
  "title": "Movie Title",
  "description": "Movie synopsis...",
  "alternateTitles": ["Other Title"],
  "genres": ["Drama", "Thriller"],
  "subgenres": ["Neo-noir"],
  "themes": ["Coming of Age"],
  "country": ["USA"],
  "cast": ["Actor 1", "Actor 2"],
  "crew": {
    "Director": ["Director Name"],
    "Writer": ["Writer Name"]
  },
  "release_date": 2024,
  "runtime_mins": 120
}
```

### Example: Scrape from Letterboxd

```http
POST /api/movies/scrape
Content-Type: application/json

{
  "url": "https://letterboxd.com/film/the-godfather/"
}
```

Response:
```json
{
  "success": true,
  "movie": {
    "url": "https://letterboxd.com/film/the-godfather/",
    "title": "The Godfather",
    "genres": ["Crime", "Drama"],
    ...
  },
  "exists": false
}
```

### Example: Get Random Unwatched Movie

```http
GET /api/movies/random?genre=Horror&country=Japan
```

### Response Formats

Success responses return JSON data:
```json
{
  "id": 1,
  "url": "https://letterboxd.com/film/movie-name/",
  "title": "Movie Title",
  "description": "Synopsis...",
  "alternateTitles": [],
  "genres": ["Drama"],
  "subgenres": [],
  "themes": ["Love"],
  "country": ["USA"],
  "cast": [],
  "crew": {},
  "release_date": 2024,
  "runtime_mins": 120,
  "physicalMedia": [],
  "watchedEntries": []
}
```

Error responses return:
```json
{
  "error": "Error message description"
}
```
