package org.btmonier

import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.columnNames
import org.jetbrains.kotlinx.dataframe.api.toList
import org.jetbrains.kotlinx.dataframe.io.readCSV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsvReadingTest {

    @Test
    fun `reads CSV with skip lines correctly`() {
        val csvPath = this::class.java.getResource("/test_movies.csv")?.path
            ?: error("Could not find test_movies.csv")

        val df = DataFrame.readCSV(csvPath, skipLines = 4)

        assertTrue(df.columnNames().contains("URL"))
        assertTrue(df.columnNames().contains("Title"))
        assertTrue(df.columnNames().contains("Year"))
    }

    @Test
    fun `extracts URLs from CSV correctly`() {
        val csvPath = this::class.java.getResource("/test_movies.csv")?.path
            ?: error("Could not find test_movies.csv")

        val df = DataFrame.readCSV(csvPath, skipLines = 4)
        val urls = df["URL"].toList().map { it.toString() }

        assertEquals(3, urls.size)
        assertTrue(urls.any { it.contains("shawshank-redemption") })
        assertTrue(urls.any { it.contains("godfather") })
        assertTrue(urls.any { it.contains("pulp-fiction") })
    }

    @Test
    fun `extracts all columns from CSV correctly`() {
        val csvPath = this::class.java.getResource("/test_movies.csv")?.path
            ?: error("Could not find test_movies.csv")

        val df = DataFrame.readCSV(csvPath, skipLines = 4)
        val titles = df["Title"].toList().map { it.toString() }
        val years = df["Year"].toList().map { it.toString() }

        assertEquals(3, titles.size)
        assertTrue(titles.contains("The Shawshank Redemption"))
        assertTrue(titles.contains("The Godfather"))
        assertTrue(titles.contains("Pulp Fiction"))

        assertEquals(3, years.size)
        assertTrue(years.contains("1994"))
        assertTrue(years.contains("1972"))
    }

    @Test
    fun `CSV has expected number of rows`() {
        val csvPath = this::class.java.getResource("/test_movies.csv")?.path
            ?: error("Could not find test_movies.csv")

        val df = DataFrame.readCSV(csvPath, skipLines = 4)

        assertEquals(3, df.rowsCount())
    }
}
