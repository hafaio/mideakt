package io.hafa.mideakt

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Thrown when a cloud request fails; [code] is the cloud or HTTP error code, if known. */
class CloudException(message: String, val code: Int?) : Exception(message)

/** A NetHome Plus region and its shared community account — public throwaway logins, not personal credentials. */
enum class Region(internal val account: String, internal val password: String) {
    US("nethome+us@mailinator.com", "password1"),
    DE("nethome+de@mailinator.com", "password1"),
    KR("nethome+sea@mailinator.com", "password1"),
}

/**
 * NetHome Plus cloud client — just enough to fetch a device's token/key.
 *
 * Pass your own [account]/[password] for reliability, or use [forRegion] for a
 * region's shared community account (convenient and registration-free, but shared
 * — prone to rate-limiting and token churn).
 *
 * [baseUrl], [appId], and [appKey] identify the NetHome Plus app to Midea's cloud:
 * fixed public constants, the same for every user, not personal credentials. They
 * default to the correct values and are overridable only to impersonate a
 * different Midea app.
 */
class NetHomePlusCloud(
    private val account: String,
    private val password: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val appId: String = DEFAULT_APP_ID,
    private val appKey: String = DEFAULT_APP_KEY,
) {
    private val deviceId = (0 until 8).joinToString("") { "%02x".format((0..255).random()) }
    private var sessionId = ""
    private var loginId: String? = null

    /** Authenticates with the cloud, establishing the session that [getToken] needs. */
    fun login() {
        if (loginId == null) loginId = getLoginId()
        val result = apiRequest(
            "/v1/user/login",
            mapOf(
                "loginAccount" to account,
                "password" to encryptPassword(loginId!!, password),
            ),
        )
        sessionId = result.string("sessionId") ?: throw CloudException("No sessionId", null)
    }

    /** Fetches the local token/key pair for the device identified by [udpid]. */
    fun getToken(udpid: String): Pair<String, String> {
        val result = apiRequest("/v1/iot/secure/getToken", mapOf("udpid" to udpid))
        val list = result["tokenlist"] as? JsonArray ?: throw CloudException("No tokenlist", null)
        for (entry in list) {
            val entryObject = entry as? JsonObject ?: continue
            if (entryObject.string("udpId") == udpid) {
                val token = entryObject.string("token")
                val key = entryObject.string("key")
                if (token != null && key != null) return token to key
            }
        }
        throw CloudException("No token/key for udpid $udpid", null)
    }

    private fun getLoginId(): String {
        val result = apiRequest("/v1/user/login/id/get", mapOf("loginAccount" to account))
        return result.string("loginId") ?: throw CloudException("No loginId", null)
    }

    private fun apiRequest(endpoint: String, data: Map<String, String>): JsonObject {
        val body = baseBody().toMutableMap()
        body.putAll(data)
        body["sign"] = sign(endpoint, body)

        val connection = URI(baseUrl + endpoint).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        OutputStreamWriter(connection.outputStream).use { it.write(formEncode(body)) }

        // On an HTTP error `inputStream` throws and the response body is on `errorStream`.
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        val parsed = if (text.isNotBlank()) runCatching { Json.parseToJsonElement(text) }.getOrNull() else null
        val json = parsed as? JsonObject
            ?: throw CloudException("HTTP $status from cloud: ${text.take(200).ifBlank { "empty body" }}", status)
        val code = json.string("errorCode")?.toIntOrNull()
        if (code == 0) return json["result"] as? JsonObject ?: JsonObject(emptyMap())
        throw CloudException(json.string("msg") ?: "Unknown cloud error", code)
    }

    private fun baseBody(): Map<String, String> = mapOf(
        "appId" to appId,
        "src" to appId,
        "format" to "2",
        "clientType" to "1",
        "language" to "en_US",
        "deviceId" to deviceId,
        "stamp" to timestamp(),
        "sessionId" to sessionId,
    )

    private fun timestamp(): String {
        val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    private fun sign(path: String, body: Map<String, String>): String {
        val query = body.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        return Crypto.sha256((path + query + appKey).toByteArray(Charsets.US_ASCII)).toHex()
    }

    private fun encryptPassword(loginId: String, password: String): String {
        val passwordHash = Crypto.sha256(password.toByteArray(Charsets.US_ASCII)).toHex()
        return Crypto.sha256((loginId + passwordHash + appKey).toByteArray(Charsets.US_ASCII)).toHex()
    }

    private fun formEncode(body: Map<String, String>): String =
        body.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

    companion object {
        // Public NetHome Plus app identity, shared by every Midea LAN implementation.
        const val DEFAULT_BASE_URL = "https://mapp.appsmb.com"
        const val DEFAULT_APP_ID = "1017"
        const val DEFAULT_APP_KEY = "3742e9e5842d4ad59c2db887e12449f9"

        /**
         * A client backed by a [Region]'s shared community account — convenient
         * and registration-free, but shared (rate-limiting and token churn), so
         * prefer the constructor with your own account. Optionally override the
         * app identity ([baseUrl]/[appId]/[appKey]).
         */
        fun forRegion(
            region: Region = Region.US,
            baseUrl: String = DEFAULT_BASE_URL,
            appId: String = DEFAULT_APP_ID,
            appKey: String = DEFAULT_APP_KEY,
        ): NetHomePlusCloud = NetHomePlusCloud(region.account, region.password, baseUrl, appId, appKey)
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Computes the udpid the cloud uses to look up a device's token/key. */
object UDPID {
    /**
     * The udpid for [deviceId]. The cloud may key on either byte order, so a
     * manual flow should try [bigEndian] = false then true (as [Setup] does).
     */
    fun compute(deviceId: Long, bigEndian: Boolean): String {
        val bytes = ByteArray(6)
        var value = deviceId
        for (i in 0 until 6) {
            bytes[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        if (bigEndian) bytes.reverse()
        val hash = Crypto.sha256(bytes)
        return ByteArray(16) { (hash[it].toInt() xor hash[it + 16].toInt()).toByte() }.toHex()
    }
}
