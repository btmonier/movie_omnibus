package org.btmonier.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.btmonier.AppSettings
import org.btmonier.Purchase
import org.btmonier.database.PurchaseDao

/**
 * Runtime settings the client needs, such as the default tax rate for the
 * purchase form.
 */
@Serializable
data class SettingsResponse(
    val defaultTaxRate: Double,
    val wishlistPriceRefreshHours: Double
)

/**
 * Routes for what was paid for a release, plus the settings endpoint that
 * feeds the purchase form its defaults.
 */
fun Route.purchaseRoutes(purchaseDao: PurchaseDao) {

    // GET /api/settings
    get("/api/settings") {
        call.respond(HttpStatusCode.OK, SettingsResponse(
            defaultTaxRate = AppSettings.defaultTaxRate,
            wishlistPriceRefreshHours = AppSettings.wishlistPriceRefreshHours
        ))
    }

    // GET /api/releases/{id}/purchase
    get("/api/releases/{id}/purchase") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid release ID"))
            return@get
        }
        val purchase = purchaseDao.getForRelease(id)
        if (purchase == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "No purchase recorded for this release"))
        } else {
            call.respond(HttpStatusCode.OK, purchase)
        }
    }

    // PUT /api/releases/{id}/purchase - Create or replace
    put("/api/releases/{id}/purchase") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid release ID"))
            return@put
        }
        try {
            val purchase = call.receive<Purchase>()
            if (purchase.subtotal < 0 || purchase.shipping < 0 || (purchase.taxAmount ?: 0.0) < 0) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Amounts must not be negative"))
                return@put
            }
            val saved = purchaseDao.upsertForRelease(id, purchase)
            if (saved == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Release not found"))
            } else {
                call.respond(HttpStatusCode.OK, saved)
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
        }
    }

    // DELETE /api/releases/{id}/purchase
    delete("/api/releases/{id}/purchase") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid release ID"))
            return@delete
        }
        if (purchaseDao.deleteForRelease(id)) {
            call.respond(HttpStatusCode.OK, mapOf("message" to "Purchase removed"))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "No purchase recorded for this release"))
        }
    }
}
