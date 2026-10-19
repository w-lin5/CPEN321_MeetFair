package com.example.cpen321application.data

import com.example.cpen321application.model.UserProfile
import org.json.JSONObject

class ProfileRepository(private val api: ApiClient) {
    suspend fun get(): ApiResult<UserProfile> =
        api.get("users/me/profile").parse { parseUserProfile(JSONObject(it)) }

    /** A blank or null [displayName] resets it to the Google account name. */
    suspend fun updateDisplayName(displayName: String?): ApiResult<UserProfile> {
        val name = displayName?.trim()?.ifBlank { null }
        val body = JSONObject().put("displayName", name ?: JSONObject.NULL)
        return api.put("users/me/profile", body).parse { parseUserProfile(JSONObject(it)) }
    }
}
