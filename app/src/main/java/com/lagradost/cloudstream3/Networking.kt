package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlin.reflect.KClass

private val jsonResponseParser = object : ResponseParser {
    override fun <T : Any> parse(text: String, kClass: KClass<T>): T {
        return parseJson(text, kClass)
    }
    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? {
        return try { parse(text, kClass) } catch (_: Exception) { null }
    }
    override fun writeValueAsString(obj: Any): String = obj.toJson()
}

var app = Requests(responseParser = jsonResponseParser).apply {
    defaultHeaders = mapOf("user-agent" to USER_AGENT)
}

@UnsafeSSL
var insecureApp = Requests(responseParser = jsonResponseParser).apply {
    defaultHeaders = mapOf("user-agent" to USER_AGENT)
}
