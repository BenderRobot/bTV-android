package com.btv.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ResilientLiveDataSourceTest {
    private val liveSpec = DataSpec(Uri.parse("http://panel.test/live/u/p/42.ts"))

    /** One scripted connection: either refuses to open, or serves [bytes] then ends the way [end] says. */
    private class Script(val openError: IOException? = null, val bytes: ByteArray = ByteArray(0), val end: IOException? = null)

    private class ScriptedUpstream(private val scripts: ArrayDeque<Script>) : DataSource.Factory {
        val openedPositions = mutableListOf<Long>()
        override fun createDataSource(): DataSource = object : DataSource {
            private var script: Script? = null
            private var cursor = 0
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long {
                openedPositions += dataSpec.position
                val next = scripts.removeFirstOrNull() ?: throw IOException("no more connections")
                next.openError?.let { throw it }
                script = next
                return C.LENGTH_UNSET.toLong()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val current = checkNotNull(script)
                if (cursor >= current.bytes.size) {
                    current.end?.let { throw it }
                    return C.RESULT_END_OF_INPUT
                }
                val count = minOf(length, current.bytes.size - cursor)
                System.arraycopy(current.bytes, cursor, buffer, offset, count)
                cursor += count
                return count
            }
            override fun getUri(): Uri? = null
            override fun close() = Unit
        }
    }

    private fun source(upstream: ScriptedUpstream, clock: LongArray = longArrayOf(0L)) = ResilientLiveDataSource(
        upstream,
        nowMs = { clock[0] },
        sleepMs = { clock[0] += it }
    )

    private fun readAll(source: DataSource, expected: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4)
        while (out.size() < expected) {
            val read = source.read(buffer, 0, buffer.size)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun httpError(code: Int) = HttpDataSource.InvalidResponseCodeException(
        code, null, null, emptyMap(), DataSpec(Uri.EMPTY), ByteArray(0)
    )

    @Test fun dropMidStreamIsStitchedWithAFreshConnection() {
        val upstream = ScriptedUpstream(ArrayDeque(listOf(
            Script(bytes = byteArrayOf(1, 2, 3), end = IOException("reset")),
            Script(openError = httpError(403)), // panel still counts the dead socket
            Script(bytes = byteArrayOf(4, 5)),   // clean EOF: a live stream never ends
            Script(bytes = byteArrayOf(6))
        )))
        val source = source(upstream)
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(liveSpec.buildUpon().setPosition(1234).build()))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), readAll(source, 6))
        assertEquals(2, source.reconnectCount)
        // Never asks a live panel for a byte offset.
        assertTrue(upstream.openedPositions.all { it == 0L })
    }

    @Test fun givesUpOnceTheReconnectionWindowIsSpent() {
        val failures = List(50) { Script(openError = IOException("down")) }
        val upstream = ScriptedUpstream(ArrayDeque(listOf(Script(bytes = byteArrayOf(1), end = IOException("reset"))) + failures))
        val clock = longArrayOf(0L)
        val source = source(upstream, clock)
        source.open(liveSpec)
        readAll(source, 1)
        try {
            source.read(ByteArray(4), 0, 4)
            fail("expected the outage to surface")
        } catch (expected: IOException) {
            assertTrue(clock[0] <= ResilientLiveDataSource.RECONNECT_WINDOW_MS)
        }
    }

    @Test fun unknownChannelFailsFastWithoutRetrying() {
        val upstream = ScriptedUpstream(ArrayDeque(listOf(Script(openError = httpError(404)), Script(bytes = byteArrayOf(1)))))
        try {
            source(upstream).open(liveSpec)
            fail("expected 404 to surface")
        } catch (expected: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(1, upstream.openedPositions.size)
        }
    }

    @Test fun nonLiveUrlsPassThroughUntouched() {
        val upstream = ScriptedUpstream(ArrayDeque(listOf(Script(bytes = byteArrayOf(9), end = IOException("reset")))))
        val source = source(upstream)
        source.open(DataSpec(Uri.parse("http://panel.test/movie/u/p/7.mkv")))
        assertEquals(1, source.read(ByteArray(4), 0, 4))
        try {
            source.read(ByteArray(4), 0, 4)
            fail("VOD errors must reach ExoPlayer's own retry policy")
        } catch (expected: IOException) {
            assertEquals(0, source.reconnectCount)
        }
    }
}
