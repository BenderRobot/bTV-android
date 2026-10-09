package com.btv.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class CastControllerTest {

    @Test
    fun liveTsBecomesHls() {
        assertEquals("http://srv/live/u/p/123.m3u8", CastController.castableUrl("http://srv/live/u/p/123.ts", isLive = true))
        assertEquals("http://srv/live/u/p/123.m3u8?t=1", CastController.castableUrl("http://srv/live/u/p/123.ts?t=1", isLive = true))
    }

    @Test
    fun filmsAndHlsAreSentAsIs() {
        assertEquals("http://srv/movie/u/p/9.mkv", CastController.castableUrl("http://srv/movie/u/p/9.mkv", isLive = false))
        assertEquals("http://srv/live/u/p/1.m3u8", CastController.castableUrl("http://srv/live/u/p/1.m3u8", isLive = true))
    }

    @Test
    fun contentTypeFollowsTheExtension() {
        assertEquals("application/x-mpegURL", CastController.contentTypeOf("http://srv/live/u/p/1.m3u8?x=1"))
        assertEquals("video/x-matroska", CastController.contentTypeOf("http://srv/movie/u/p/9.MKV"))
        assertEquals("video/mp4", CastController.contentTypeOf("http://srv/movie/u/p/9.mp4"))
        assertEquals("video/mp4", CastController.contentTypeOf("http://srv/movie/u/p/9"))
    }
}
