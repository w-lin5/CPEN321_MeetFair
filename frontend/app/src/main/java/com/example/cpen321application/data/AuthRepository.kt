package com.example.cpen321application.data

import com.example.cpen321application.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AuthRepository(private val api: ApiClient, private val session: SessionStore) {
    // Exchanges Google ID token for a backend sesion token and persists authenticated user
    suspend fun signInWithGoogle(idToken: String): ApiResult<User> =
        when (val result = api.post("auth/google", JSONObject().put("idToken", idToken)).parse(::parseAuth)) {
            is ApiResult.Success -> {
                withContext(Dispatchers.IO) { session.save(result.data.token, result.data.user) }
                ApiResult.Success(result.data.user)
            }
            is ApiResult.Failure -> result
        }
    
    // Clears locally persisted session data w/o making backend request
    suspend fun signOut() = withContext(Dispatchers.IO) { session.clear() }
}
