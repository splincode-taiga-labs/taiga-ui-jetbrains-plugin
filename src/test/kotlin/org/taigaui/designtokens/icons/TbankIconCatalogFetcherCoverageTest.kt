package org.taigaui.designtokens.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession

class TbankIconCatalogFetcherCoverageTest {
    @Test
    fun `default fetcher returns successful response body`() {
        val fetcher = newFetcher(200, """{"icons":{"flags":["ab"]}}""")

        assertEquals(
            """{"icons":{"flags":["ab"]}}""",
            fetcher.fetch(),
        )
    }

    @Test
    fun `default fetcher ignores non successful response`() {
        val fetcher = newFetcher(500, "broken")

        assertNull(fetcher.fetch())
    }

    private fun newFetcher(
        status: Int,
        body: String,
    ): IconCatalogFetcher {
        val type = Class.forName("org.taigaui.designtokens.icons.TbankIconCatalogFetcher")
        val constructor = type.getDeclaredConstructor().apply { isAccessible = true }
        val instance = constructor.newInstance()
        val clientField = type.getDeclaredField("client").apply { isAccessible = true }

        clientField.set(instance, StubHttpClient(status, body))

        return instance as IconCatalogFetcher
    }

    private class StubHttpClient(
        private val status: Int,
        private val responseBody: String,
    ) : HttpClient() {
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

        override fun connectTimeout(): Optional<Duration> = Optional.of(Duration.ofSeconds(1))

        override fun followRedirects(): Redirect = Redirect.NORMAL

        override fun proxy(): Optional<ProxySelector> = Optional.empty()

        override fun sslContext(): SSLContext = SSLContext.getDefault()

        override fun sslParameters(): SSLParameters = SSLParameters()

        override fun authenticator(): Optional<Authenticator> = Optional.empty()

        override fun version(): Version = Version.HTTP_1_1

        override fun executor(): Optional<Executor> = Optional.empty()

        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            @Suppress("UNCHECKED_CAST")
            return StubResponse(
                request = request,
                status = status,
                body = responseBody as T,
            )
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.completedFuture(send(request, responseBodyHandler))

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> =
            CompletableFuture.completedFuture(send(request, responseBodyHandler))

        override fun newWebSocketBuilder(): WebSocket.Builder = throw UnsupportedOperationException()
    }

    private class StubResponse<T>(
        private val request: HttpRequest,
        private val status: Int,
        private val body: T,
    ) : HttpResponse<T> {
        override fun statusCode(): Int = status

        override fun request(): HttpRequest = request

        override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()

        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

        override fun body(): T = body

        override fun sslSession(): Optional<SSLSession> = Optional.empty()

        override fun uri(): URI = request.uri()

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
