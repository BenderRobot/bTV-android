package com.btv.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import com.btv.data.model.AuthSession
import com.btv.data.model.UserInfo
import com.btv.data.model.XtreamVod
import com.btv.data.store.CredentialsStore
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryStreamCancellationTest {
    @Test(timeout = 15_000) fun cancellingCatalogScanClosesStalledResponse() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val firstItem = CountDownLatch(1)
        val responder = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* request headers */ }
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000000\r\n\r\n".toByteArray())
                    output.write(("[" + "{\"stream_id\":\"1\",\"name\":\"First\"},".repeat(1_000)).toByteArray())
                    output.flush()
                    Thread.sleep(10_000)
                }
            } catch (_: SocketException) {
                // Closing the response or test server is expected on cancellation.
            } catch (_: InterruptedException) {
                // The test has finished.
            }
        }
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = AuthRepository(CredentialsStore(context))
            val session = AuthSession("http://127.0.0.1:${server.localPort}", "u", "p", UserInfo(auth = 1))
            val job = launch(Dispatchers.Default) {
                repository.streamCatalog(session, "get_vod_streams", XtreamVod.serializer()) {
                    firstItem.countDown()
                    true
                }
            }
            assertTrue(firstItem.await(5, TimeUnit.SECONDS))
            Thread.sleep(500) // Let the parser consume the sent records and block on the open socket.
            withTimeout(4_000) { job.cancelAndJoin() }
        } finally {
            server.close()
            responder.interrupt()
        }
    }
}
