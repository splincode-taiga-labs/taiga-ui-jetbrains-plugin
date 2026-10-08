package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import kotlin.concurrent.thread

class HttpTaigaDocsFetcherCoverageTest {
    @Test
    fun `http fetcher returns body for successful response`() {
        withServer(status = 200, body = "Taiga docs") { uri ->
            val fetcher = newFetcher()

            assertEquals("Taiga docs", fetcher.fetch(source(uri)))
        }
    }

    @Test
    fun `http fetcher ignores non successful response`() {
        withServer(status = 503, body = "Unavailable") { uri ->
            val fetcher = newFetcher()

            assertNull(fetcher.fetch(source(uri)))
        }
    }

    @Test
    fun `http fetcher converts connection failures to null`() {
        val socket = ServerSocket(0)
        val port = socket.localPort

        socket.close()

        val fetcher = newFetcher()

        assertNull(fetcher.fetch(source(URI.create("http://127.0.0.1:$port/docs"))))
    }

    private fun newFetcher(): TaigaDocsFetcher {
        val type =
            Class.forName(
                "org.taigaui.designtokens.documentation.HttpTaigaDocsFetcher",
            )
        val constructor = type.getDeclaredConstructor().apply { isAccessible = true }

        return constructor.newInstance() as TaigaDocsFetcher
    }

    private fun source(uri: URI): TaigaDocsSource =
        TaigaDocsSource(
            majorVersion = 5,
            contentUri = uri,
            documentationBaseUri = uri.resolve("/"),
            cacheKey = "coverage",
        )

    private fun withServer(
        status: Int,
        body: String,
        block: (URI) -> Unit,
    ) {
        ServerSocket(0).use { server ->
            val worker =
                thread(start = true, isDaemon = true) {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()

                        generateSequence(reader::readLine)
                            .takeWhile(String::isNotEmpty)
                            .forEach { }

                        val bytes = body.toByteArray()
                        val reason = if (status in 200..299) "OK" else "Service Unavailable"
                        val output = socket.getOutputStream()

                        output.write(
                            (
                                "HTTP/1.1 $status $reason\r\n" +
                                    "Content-Type: text/plain; charset=utf-8\r\n" +
                                    "Content-Length: ${bytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(),
                        )
                        output.write(bytes)
                        output.flush()
                    }
                }

            block(URI.create("http://127.0.0.1:${server.localPort}/docs"))
            worker.join(5_000)
        }
    }
}
