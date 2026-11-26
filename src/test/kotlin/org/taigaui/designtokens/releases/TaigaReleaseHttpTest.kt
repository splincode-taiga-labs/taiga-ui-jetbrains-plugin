package org.taigaui.designtokens.releases

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.concurrency.AppExecutorUtil
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Callable
import kotlin.concurrent.thread

class TaigaReleaseHttpTest : BasePlatformTestCase() {
    fun testSuccessfulResponseSelectsLatestStableRelease() {
        val body =
            """
            [
                {"tag_name":"v5.34.0","draft":false,"prerelease":false},
                {"tag_name":"v5.35.0","draft":false,"prerelease":false},
                {"tag_name":"v5.36.0","draft":false,"prerelease":true}
            ]
            """.trimIndent()

        val releases = response(200, body)
        assertEquals(TaigaReleaseVersion.parse("5.35.0"), releases[5])
    }

    fun testInvalidJsonAndHttpErrorsReturnEmptyFeed() {
        assertTrue(response(200, "{").isEmpty())
        assertTrue(response(503, "Unavailable").isEmpty())
    }

    private fun response(
        status: Int,
        body: String,
    ): Map<Int, TaigaReleaseVersion> =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker =
                thread(isDaemon = true) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        generateSequence(reader::readLine).takeWhile(String::isNotEmpty).forEach { }
                        val bytes = body.toByteArray()
                        val reason = if (status == 200) "OK" else "Service Unavailable"
                        val output = socket.getOutputStream()
                        output.write(
                            (
                                "HTTP/1.1 $status $reason\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${bytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(),
                        )
                        output.write(bytes)
                        output.flush()
                    }
                }

            try {
                PlatformTestUtil.waitForFuture(
                    AppExecutorUtil.getAppExecutorService().submit(
                        Callable { fetchStableReleases("http://127.0.0.1:${server.localPort}/releases") },
                    ),
                )
            } finally {
                server.close()
                worker.join(5_000)
            }
        }
}
