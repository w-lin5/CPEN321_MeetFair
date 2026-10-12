package com.example.cpen321application

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cpen321application.data.ApiClient
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.AuthInterceptor
import com.example.cpen321application.data.SessionStore
import com.example.cpen321application.data.parseAuth
import com.example.cpen321application.model.User
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// TODO: delete scratch tests, dont rly need this after attaching main screen to these functionalities

@RunWith(AndroidJUnit4::class)
class SessionStoreTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sessionSurvivesNewInstance() {
        SessionStore(ctx).clear()
        SessionStore(ctx).save("tok", User("1", "a@b.c", "Ada"))
        val reread = SessionStore(ctx) // fresh instance reads from disk
        assertEquals("tok", reread.currentToken())
        assertEquals("1", reread.currentUserId())
        reread.clear()
        assertNull(SessionStore(ctx).currentToken())
    }

    @Test
    fun parsesAuthResponse() {
        val r = parseAuth("""{"token":"t","user":{"id":"1","email":"a@b.c","name":"Ada"}}""")
        assertEquals("t", r.token)
        assertEquals("Ada", r.user.name)
    }

    @Test
    fun unauthenticatedCallReturns401() = runBlocking {
        val r = ApiClient(BuildConfig.API_BASE_URL, OkHttpClient()).get("users/me")
        assertTrue(r is ApiResult.Failure && r.error.httpCode == 401)
    }

    @Test
    fun interceptorClearsSessionOn401() = runBlocking {
        val store = SessionStore(ctx)
        store.save("garbage", User("1", "a@b.c", "Ada"))
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(store)).build()
        val r = ApiClient(BuildConfig.API_BASE_URL, client).get("users/me")
        assertTrue(r is ApiResult.Failure)
        assertNull(store.currentToken()) // 401 wiped the bad session
    }
}
