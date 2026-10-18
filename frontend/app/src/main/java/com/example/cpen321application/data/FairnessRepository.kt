package com.example.cpen321application.data

import com.example.cpen321application.model.FairnessHistory
import org.json.JSONObject

class FairnessRepository(private val api: ApiClient) {
    /** The caller's fairness debt and commute history in one of their groups. */
    suspend fun fairnessHistory(groupId: String): ApiResult<FairnessHistory> =
        api.get("users/me/groups/$groupId/fairness").parse { parseFairnessHistory(JSONObject(it)) }
}
