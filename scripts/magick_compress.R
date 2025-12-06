#!/usr/bin/env Rscript

suppressPackageStartupMessages({
    library(magick)
})

# ---- Parse command arguments ----
args <- commandArgs(trailingOnly = TRUE)

# Simple key=value or --flag value parser
parse_args <- function(args) {
    out <- list()
    i <- 1
    while (i <= length(args)) {
        key <- args[[i]]
        if (startsWith(key, "--")) {
            key <- sub("^--", "", key)
            if (i == length(args) || startsWith(args[[i + 1]], "--")) {
                # boolean flag
                out[[key]] <- TRUE
                i <- i + 1
            } else {
                out[[key]] <- args[[i + 1]]
                i <- i + 2
            }
        } else {
            i <- i + 1
        }
    }
    out
}

opts <- parse_args(args)

# ---- Defaults ----
dir       <- opts$dir
quality   <- if (!is.null(opts$quality)) as.integer(opts$quality) else 15
pattern   <- if (!is.null(opts$pattern)) opts$pattern else "\\.(jpg|jpeg|png)$"
recursive <- if (!is.null(opts$recursive)) TRUE else FALSE

# ---- Input checks ----
if (is.null(dir)) {
    stop("Missing required argument: --dir <path>")
}

if (!dir.exists(dir)) {
    stop("Directory does not exist: ", dir)
}

# ---- Compression function ----
compress_dir <- function(dir, quality = 15, pattern = "\\.(jpg|jpeg|png)$", recursive = FALSE) {
    files <- list.files(
        dir,
        pattern      = pattern,
        full.names   = TRUE,
        ignore.case  = TRUE,
        recursive    = recursive
    )

    if (length(files) == 0L) {
        message("No files found matching pattern: ", pattern)
        return(invisible(NULL))
    }

    message("Found ", length(files), " file(s). Starting compression...")

    for (f in files) {
        img <- image_read(f)
        image_write(img, path = f, quality = quality)
        message("Compressed: ", f)
    }

    message("Done.")
}

# ---- Run ----
compress_dir(
    dir       = dir,
    quality   = quality,
    pattern   = pattern,
    recursive = recursive
)
