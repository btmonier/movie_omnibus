package org.btmonier

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.nodes.Document

/**
 * Reads a price off a store nobody has written a reader for.
 *
 * Boutique labels sell exclusives from their own shops, and there are far too
 * many of them to give each one a reader. Most publish the price in a form
 * meant for machines all the same - a schema.org `Product` in a JSON-LD block,
 * or Open Graph / microdata price tags - which is what this reads. It is a
 * best effort by design: when a page says nothing readable the link falls back
 * to prices typed in by hand rather than to a wrong number.
 */
class StructuredDataPriceScraper(
    override val vendorName: String,
    private val fetcher: PageFetcher = JsoupPageFetcher
) : PriceScraper {

    /**
     * Never claims a URL on its own. A page is only read this way once its
     * link has been confirmed by hand, so a guessed price can never displace a
     * store that has a reader of its own.
     */
    override fun handles(url: String): Boolean = false

    override suspend fun fetch(url: String): ScrapedPrices =
        ScrapedPrices.Vendor(vendorName, extractStructuredPrice(fetcher.document(url), url))
}

private val structuredJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * What a page publishes about its own price, read from its structured data.
 *
 * JSON-LD is tried first because it is the only source that also states stock
 * and a comparison price; the Open Graph and microdata tags are a fallback for
 * pages carrying nothing richer. A page with none of them yields a
 * [VendorPrice] with no price, which is the signal to keep the link manual.
 */
fun extractStructuredPrice(doc: Document, url: String): VendorPrice {
    val fromJsonLd = jsonLdOffer(doc)
    val price = fromJsonLd?.price ?: metaPrice(doc)
    val listPrice = fromJsonLd?.listPrice

    return VendorPrice(
        price = price,
        // As with the stores, a struck-through price that merely repeats the
        // selling price would read as a 0% discount, so it is dropped.
        listPrice = listPrice?.takeIf { price == null || it > price },
        inStock = fromJsonLd?.inStock ?: metaAvailability(doc),
        title = fromJsonLd?.title ?: doc.select("meta[property=og:title]").attr("content").trim()
            .takeIf { it.isNotBlank() } ?: doc.title().trim().takeIf { it.isNotBlank() },
        imageUrl = fromJsonLd?.imageUrl
            ?: doc.select("meta[property=og:image]").attr("content").trim().takeIf { it.isNotBlank() },
        buyUrl = url
    )
}

/** The fields worth taking from a schema.org `Product` and its offer. */
private data class JsonLdOffer(
    val price: Double?,
    val listPrice: Double?,
    val inStock: Boolean?,
    val title: String?,
    val imageUrl: String?
)

/**
 * The first `Product` with a priced offer among the page's JSON-LD blocks.
 *
 * A block can be an object, an array of them, or a `@graph` holding the page's
 * whole node set, so all three are flattened before looking. Blocks that do
 * not parse are skipped: a broken one elsewhere on the page should not stop
 * the product being found.
 */
private fun jsonLdOffer(doc: Document): JsonLdOffer? =
    doc.select("script[type=application/ld+json]")
        .asSequence()
        .mapNotNull { runCatching { structuredJson.parseToJsonElement(it.data()) }.getOrNull() }
        .flatMap { flattenNodes(it) }
        .filter { it.typeNames().any { type -> type.equals("Product", ignoreCase = true) } }
        .mapNotNull { product ->
            val offers = flattenNodes(product["offers"] ?: return@mapNotNull null)
            val priced = offers.firstOrNull { offerPrice(it) != null } ?: return@mapNotNull null
            val price = offerPrice(priced) ?: return@mapNotNull null

            JsonLdOffer(
                price = roundToCents(price),
                listPrice = (priced.amount("highPrice") ?: priced.amount("listPrice"))?.let { roundToCents(it) },
                inStock = priced.string("availability")?.let { availability ->
                    when {
                        availability.contains("OutOfStock", ignoreCase = true) -> false
                        availability.contains("SoldOut", ignoreCase = true) -> false
                        availability.contains("Discontinued", ignoreCase = true) -> false
                        availability.contains("InStock", ignoreCase = true) -> true
                        availability.contains("PreOrder", ignoreCase = true) -> true
                        else -> null
                    }
                },
                title = product.string("name"),
                imageUrl = product["image"]?.let { image ->
                    when (image) {
                        is JsonArray -> image.firstOrNull()?.let { asImageUrl(it) }
                        else -> asImageUrl(image)
                    }
                }
            )
        }
        .firstOrNull()

/** An offer's asking price, taking the low end of an aggregate offer. */
private fun offerPrice(offer: JsonObject): Double? =
    offer.amount("price")
        ?: offer.amount("lowPrice")
        ?: (offer["priceSpecification"] as? JsonObject)?.amount("price")

/**
 * Every object reachable from [element], unwrapping arrays and `@graph`, so
 * one traversal covers all the shapes JSON-LD is written in.
 */
private fun flattenNodes(element: JsonElement): List<JsonObject> = when (element) {
    is JsonArray -> element.flatMap { flattenNodes(it) }
    is JsonObject -> element["@graph"]?.let { flattenNodes(it) } ?: listOf(element)
    else -> emptyList()
}

private fun JsonObject.typeNames(): List<String> = when (val type = this["@type"]) {
    is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNullIfNull() }
    is JsonPrimitive -> listOfNotNull(type.contentOrNullIfNull())
    else -> emptyList()
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNullIfNull()?.trim()?.takeIf { it.isNotBlank() }

private fun JsonObject.amount(key: String): Double? = parseAmount(string(key))

private fun JsonPrimitive.contentOrNullIfNull(): String? = content.takeIf { it != "null" }

private fun asImageUrl(element: JsonElement): String? = when (element) {
    is JsonPrimitive -> element.contentOrNullIfNull()?.trim()?.takeIf { it.isNotBlank() }
    is JsonObject -> element.string("url")
    else -> null
}

/**
 * The price tags a page carries when it has no JSON-LD: Open Graph product
 * tags first, then microdata.
 */
private fun metaPrice(doc: Document): Double? = sequenceOf(
    "meta[property=product:price:amount]",
    "meta[property=og:price:amount]",
    "meta[itemprop=price]",
    "[itemprop=price][content]"
).mapNotNull { selector -> parseAmount(doc.select(selector).attr("content")) }
    .firstOrNull()
    ?.let { roundToCents(it) }

private fun metaAvailability(doc: Document): Boolean? {
    val text = sequenceOf(
        "meta[property=product:availability]",
        "meta[property=og:availability]",
        "[itemprop=availability][content]",
        "link[itemprop=availability]"
    ).map { selector ->
        doc.select(selector).let { it.attr("content").ifBlank { it.attr("href") } }
    }.firstOrNull { it.isNotBlank() } ?: return null

    return when {
        text.contains("OutOfStock", ignoreCase = true) || text.contains("out of stock", ignoreCase = true) -> false
        text.contains("SoldOut", ignoreCase = true) || text.contains("sold out", ignoreCase = true) -> false
        text.contains("InStock", ignoreCase = true) || text.contains("in stock", ignoreCase = true) -> true
        text.contains("PreOrder", ignoreCase = true) -> true
        else -> null
    }
}

/**
 * A price written for a machine, which may still arrive dressed as money:
 * "24.99", "$24.99", "1,299.00" or "USD 24.99" all read the same.
 */
private fun parseAmount(raw: String?): Double? {
    val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val match = Regex("""\d[\d,]*(?:\.\d+)?""").find(text) ?: return null
    return match.value.replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 }
}
