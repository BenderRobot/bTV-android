package com.btv.util

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamExtensionTest {
    @Test fun matroskaKeepsNativeRouteEvenWhenHlsIsAllowed() {
        assertEquals("mkv", resolveExtension(".MKV", listOf("m3u8", "ts"), "mp4"))
    }

    @Test fun unsupportedContainerUsesHlsOnlyWhenProviderAllowsIt() {
        assertEquals("m3u8", resolveExtension("avi", listOf("m3u8"), "mp4"))
        assertEquals("avi", resolveExtension("avi", emptyList(), "mp4"))
    }

    @Test fun missingContainerUsesDeclaredDefault() {
        assertEquals("mp4", resolveExtension(null, listOf("m3u8"), "mp4"))
    }
}
