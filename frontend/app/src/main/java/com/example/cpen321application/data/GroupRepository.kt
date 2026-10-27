package com.example.cpen321application.data

import com.example.cpen321application.model.Group
import org.json.JSONArray
import org.json.JSONObject

class GroupRepository(private val api: ApiClient) {
    suspend fun removeMember(groupId: String, memberId: String): ApiResult<Unit> =
        api.delete("groups/$groupId/members/$memberId").parse { Unit }

    suspend fun list(): ApiResult<List<Group>> =
        api.get("groups").parse { JSONArray(it).mapObjects(::parseGroup) }

    suspend fun detail(groupId: String): ApiResult<Group> =
        api.get("groups/$groupId").parse { parseGroup(JSONObject(it)) }

    suspend fun create(name: String): ApiResult<Group> =
        api.post("groups", JSONObject().put("name", name)).parse { parseGroup(JSONObject(it)) }

    suspend fun join(inviteCode: String): ApiResult<Group> =
        api.post("groups/join", JSONObject().put("inviteCode", inviteCode)).parse { parseGroup(JSONObject(it)) }
}
