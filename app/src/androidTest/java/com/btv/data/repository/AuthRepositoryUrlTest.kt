package com.btv.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import com.btv.data.model.AuthSession
import com.btv.data.model.UserInfo
import com.btv.data.store.CredentialsStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthRepositoryUrlTest {
    private val repository by lazy {
        AuthRepository(CredentialsStore(InstrumentationRegistry.getInstrumentation().targetContext))
    }
    private val session = AuthSession(
        "https://example.test/panel", "ben+tv", "p/a ss?#", UserInfo(auth = 1)
    )

    @Test fun streamUrlEncodesCredentialsAndMediaIdAsSegments() {
        val url = repository.buildStreamUrl(session, "id/42", "movie", "mkv").toHttpUrl()

        assertEquals("https", url.scheme)
        assertEquals(listOf("panel", "movie", "ben+tv", "p/a ss?#", "id/42.mkv"), url.pathSegments)
        assertEquals(null, url.query)
    }

    @Test fun timeshiftUrlPreservesLocalStartAndEncodesCredentials() {
        val url = repository.buildTimeshiftUrl(session, "id/42", "2026-10-06 21:07:00", 90)!!.toHttpUrl()

        assertEquals(
            listOf("panel", "timeshift", "ben+tv", "p/a ss?#", "90", "2026-10-06:21-07", "id/42.ts"),
            url.pathSegments
        )
        assertNull(repository.buildTimeshiftUrl(session, "id/42", "invalid", 90))
    }
}
