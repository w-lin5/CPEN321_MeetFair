package com.example.cpen321application.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

data class AppError(val message: String, val httpCode: Int? = null)

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Failure(val error: AppError) : ApiResult<Nothing>
}

// Shared JSON media type and HTTP status constants
private val JSON_MEDIA = "application/json".toMediaType()
private const val HTTP_UNAUTHORIZED = 401

class ApiClient(baseUrl: String, private val http: OkHttpClient) {
    // Normalize base URL to avoid malformed endpoint paths
    private val base = baseUrl.trimEnd('/') + "/api/"

    suspend fun get(path: String): ApiResult<String> =
        execute(Request.Builder().url(base + path).get().build())

    suspend fun post(path: String, body: JSONObject? = null): ApiResult<String> =
        execute(Request.Builder().url(base + path).post(asJsonBody(body)).build())

    suspend fun put(path: String, body: JSONObject): ApiResult<String> =
        execute(Request.Builder().url(base + path).put(asJsonBody(body)).build())

    private fun asJsonBody(body: JSONObject?): RequestBody =
        (body ?: JSONObject()).toString().toRequestBody(JSON_MEDIA)

    // Blocking network I/O on IO dispatcher and normalize HTTP/network failures
    private suspend fun execute(request: Request): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    ApiResult.Success(text)
                } else {
                    ApiResult.Failure(AppError(serverMessage(text, response.code), response.code))
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure(AppError("Network error: ${e.message ?: "unreachable"}"))
        }
    }

    // Prioritize server provided err message, with error code as fallback
    private fun serverMessage(body: String, code: Int): String =
        try {
            JSONObject(body).optString("error").ifBlank { "HTTP $code" }
        } catch (_: JSONException) {
            "HTTP $code"
        }
}

// Parses a successful body; malformed JSON becomes a Failure instead of a crash
fun <T> ApiResult<String>.parse(transform: (String) -> T): ApiResult<T> = when (this) {
    is ApiResult.Success -> try {
        ApiResult.Success(transform(data))
    } catch (e: JSONException) {
        ApiResult.Failure(AppError("Unexpected server response: ${e.message}"))
    }
    is ApiResult.Failure -> this
}

// Adds the Bearer token; a 401 clears the session so the app returns to sign-in
class AuthInterceptor(private val sessionStore: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = sessionStore.currentToken()
        val request = if (token == null) {
            chain.request()
        } else {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        }
        
        val response = chain.proceed(request)
        if (response.code == HTTP_UNAUTHORIZED && token != null) {
            sessionStore.clear()
        }
        
        return response
    }
}
