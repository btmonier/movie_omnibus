package org.btmonier

import org.btmonier.database.CategoryDao
import org.btmonier.database.CategoryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The category types and how they are addressed. `/api/categories/{type}`
 * resolves a request by slug, and the DAO resolves a slug to a lookup table, so
 * a type that is missing from either is a 404 or a crash on the tab that uses
 * it rather than a compile error.
 */
class CategoryTypeTest {

    @Test
    fun `every category type has a lookup mapping`() {
        // The DAO checks this when it is built, so constructing it is the test
        CategoryDao()
    }

    @Test
    fun `every type round-trips through its slug`() {
        CategoryType.entries.forEach { type ->
            assertEquals(type, CategoryType.fromSlug(type.slug), "Slug \"${type.slug}\" did not resolve")
            assertEquals(type, CategoryType.fromSlug(type.slug.uppercase()), "Slugs should ignore case")
        }
    }

    @Test
    fun `slugs are unique and unknown slugs resolve to nothing`() {
        assertEquals(CategoryType.entries.size, CategoryType.entries.map { it.slug }.toSet().size)
        assertNull(CategoryType.fromSlug(""))
        assertNull(CategoryType.fromSlug("publishers"))
    }

    @Test
    fun `stores are a category of their own`() {
        assertNotNull(CategoryType.fromSlug("stores"))
        assertEquals(CategoryType.STORE, CategoryType.fromSlug("stores"))
    }
}
