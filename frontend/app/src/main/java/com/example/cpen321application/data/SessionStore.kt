package com.example.cpen321application.data

import android.content.Context
import com.example.cpen321application.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS = "session"
private const val KEY_TOKEN = "token"
private const val KEY_USER_ID = "user_id"
private const val KEY_USER_NAME = "user_name"

// Token + current user in SharedPreferences, so the session survives process death
class SessionStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _token = MutableStateFlow(prefs.getString(KEY_TOKEN, null))
    private val _userName = MutableStateFlow(prefs.getString(KEY_USER_NAME, null))

    val token: StateFlow<String?> = _token.asStateFlow()
    val userName: StateFlow<String?> = _userName.asStateFlow()

    fun currentToken(): String? = _token.value
    fun currentUserId(): String? = prefs.getString(KEY_USER_ID, null)

    // Blocking disk write: call from a background dispatcher
    fun save(token: String, user: User) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, user.id)
            .putString(KEY_USER_NAME, user.name)
            .commit()
        _userName.value = user.name
        _token.value = token
    }

    fun clear() {
        com.example.cpen321application.notifications.MeetupReminders(context).clear()
        prefs.edit().clear().commit()
        _token.value = null
        _userName.value = null
    }
}
