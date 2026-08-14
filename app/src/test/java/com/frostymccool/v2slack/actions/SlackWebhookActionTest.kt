package com.frostymccool.v2slack.actions

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the webhook output path, including the "webhook failure" and "no network" personas
 * called out as required test scenarios: server rejects the post, the server is unreachable,
 * and the request times out.
 */
@RunWith(RobolectricTestRunner::class)
class SlackWebhookActionTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        // A couple of tests shut the server down themselves to simulate an unreachable host,
        // so tolerate an already-closed server here.
        runCatching { server.shutdown() }
    }

    @Test
    fun `successful post returns Success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val action = SlackWebhookAction(webhookUrlProvider = { server.url("/services/hook").toString() }, httpClient = client)

        val result = action.execute(context = context, text = "standup notes for today")

        assertThat(result).isEqualTo(ActionResult.Success)
        val recorded = server.takeRequest()
        assertThat(recorded.body.readUtf8()).contains("standup notes for today")
    }

    @Test
    fun `server rejection (4xx) is a non-retryable failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("invalid_token"))
        val action = SlackWebhookAction(webhookUrlProvider = { server.url("/services/hook").toString() }, httpClient = client)

        val result = action.execute(context, "text") as ActionResult.Failure

        assertThat(result.retryable).isFalse()
        assertThat(result.message).contains("403")
    }

    @Test
    fun `server error (5xx) is a retryable failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val action = SlackWebhookAction(webhookUrlProvider = { server.url("/services/hook").toString() }, httpClient = client)

        val result = action.execute(context, "text") as ActionResult.Failure

        assertThat(result.retryable).isTrue()
    }

    @Test
    fun `no webhook configured fails fast without a network call`() = runBlocking {
        val action = SlackWebhookAction(webhookUrlProvider = { null }, httpClient = client)

        val result = action.execute(context, "text") as ActionResult.Failure

        assertThat(result.retryable).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `malformed webhook url fails fast without a network call`() = runBlocking {
        val action = SlackWebhookAction(webhookUrlProvider = { "not a url" }, httpClient = client)

        val result = action.execute(context, "text") as ActionResult.Failure

        assertThat(result.retryable).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `server unreachable (connection dropped) is a retryable failure -- no network persona`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val action = SlackWebhookAction(webhookUrlProvider = { server.url("/services/hook").toString() }, httpClient = client)

        val result = action.execute(context, "text") as ActionResult.Failure

        assertThat(result.retryable).isTrue()
    }

    @Test
    fun `unresponsive server (timeout) is a retryable failure`() = runBlocking {
        // Delay the response headers, not just the body: the action never reads the response
        // body (it only inspects the status code), so a body-only delay would never be
        // noticed -- onResponse() fires as soon as headers arrive.
        server.enqueue(
            MockResponse()
                .setHeadersDelay(5, TimeUnit.SECONDS),
        )
        val action = SlackWebhookAction(webhookUrlProvider = { server.url("/services/hook").toString() }, httpClient = client)

        val result = action.execute(context, "text") as ActionResult.Failure

        assertThat(result.retryable).isTrue()
    }

    @Test
    fun `truly unreachable host surfaces as retryable failure, never throws`() = runBlocking {
        server.shutdown()
        val action = SlackWebhookAction(
            webhookUrlProvider = { server.url("/services/hook").toString() },
            httpClient = client,
        )

        val result = action.execute(context, "text")

        assertThat(result).isInstanceOf(ActionResult.Failure::class.java)
        assertThat((result as ActionResult.Failure).retryable).isTrue()
    }
}
