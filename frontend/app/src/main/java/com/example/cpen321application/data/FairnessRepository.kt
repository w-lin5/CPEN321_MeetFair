package com.example.cpen321application.data

import com.example.cpen321application.model.GroupFairness
import org.json.JSONObject

class FairnessRepository(private val api: ApiClient) {
    /** Every member's Fair Credit in the group and the group's ledger; members only. */
    suspend fun groupFairness(groupId: String): ApiResult<GroupFairness> =
        api.get("groups/$groupId/fairness").parse { parseGroupFairness(JSONObject(it)) }
}
