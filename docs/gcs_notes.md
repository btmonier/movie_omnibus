## Google Cloud Storage Notes

### Goal
Have image previews of physical media entries but not load directly into PostgreSQL DB - only link to URIs...

### Steps
* Made bucket called `movie_omnibus`
* Did not make bucket public - using private entry and URL authentication
* Using local JSON-based key for authentication:
  * Set up using [GCS Service Accounts](https://console.cloud.google.com/iam-admin/serviceaccounts)
    * Click your service account name
    * Open the "Keys" tab
    * Click "Add Key" -> "Create New Key"
    * Choose "JSON"
    * Download the JSON file (example: `movie-media-app-123abc456def.json`)
    * Store locally and use environment variables for local path link