package io.github.crmapache.amazingcodex.usage

import com.intellij.openapi.diagnostic.thisLogger
import io.github.crmapache.amazingcodex.feedback.FeedbackSender
import io.github.crmapache.amazingcodex.net.IdeHttp
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The two requests the usage report makes: a report, and "forget everything under this identifier".
 *
 * Through the IDE's own proxy and certificates (see IdeHttp), like the feedback form's request - inside a
 * company neither is optional. Both answer only whether they went through: the caller has nothing to show
 * anybody about a report that did not arrive, it simply tries again later.
 */
internal object UsageSender {

    /** Post a report. True when the service took it. */
    fun post(body: String): Boolean = send(
        HttpRequest.newBuilder(URI("${base()}/v1/usage"))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)),
        "report",
    )

    /** Ask the service to delete everything kept under an identifier. True when it has. */
    fun forget(id: String): Boolean = send(HttpRequest.newBuilder(forgetAddress(id)).DELETE(), "forget")

    /**
     * Where a deletion goes. The plugin is named in the address, since a deletion has no body (see
     * UsageReport.PRODUCT). Without it the service would read the request as the original plugin's, delete
     * nothing of this one's under the identifier - and still answer that it had, so the request would
     * never be made again.
     */
    fun forgetAddress(id: String): URI = URI("${base()}/v1/usage/$id?product=${UsageReport.PRODUCT}")

    private fun send(builder: HttpRequest.Builder, what: String): Boolean = runCatching {
        val request = builder
            // Not authentication - it sits in a published plugin. It keeps scanners from being answered. The
            // original plugin's header and key, as they are: one service, one secret for both plugins.
            .header("x-acc-key", key())
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .build()
        val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        if (status !in 200..299) thisLogger().info("The usage service answered $status to a $what")
        status in 200..299
    }.getOrElse { failure ->
        // The kind of failure only: the most ordinary one is no network, and it is not worth more than a line.
        thisLogger().info("The usage $what did not go: ${FeedbackSender.describe(failure)}")
        false
    }

    private val client: HttpClient by lazy { IdeHttp.client(CONNECT_TIMEOUT_SECONDS) }

    /**
     * Where the reports go: the service shared with the original plugin, which keeps each plugin's figures
     * apart (see UsageReport.PRODUCT). Overridable by a system property ([URL_PROPERTY]) so the whole chain
     * can be tried against a service running on this machine - the published address is not a thing to
     * test against, and a sandbox IDE's make-believe days do not belong in its figures.
     */
    fun base(): String = (System.getProperty(URL_PROPERTY).orEmpty().trim().ifEmpty { DEFAULT_URL }).trimEnd('/')

    /** Whether the reports go somewhere other than the published service - a sandbox, a test. */
    fun isCustom(): Boolean = System.getProperty(URL_PROPERTY).orEmpty().isNotBlank()

    private fun key(): String = System.getProperty(KEY_PROPERTY).orEmpty().trim().ifEmpty { DEFAULT_KEY }

    const val DEFAULT_URL = "https://usage.mzpizote.com"

    const val URL_PROPERTY = "acx.usage.url"

    const val KEY_PROPERTY = "acx.usage.key"

    private const val DEFAULT_KEY = "sFHLt_PxGnU8HeR2s3lMjPONtaL798Wk"

    private const val CONNECT_TIMEOUT_SECONDS = 10L

    private const val REQUEST_TIMEOUT_SECONDS = 20L
}
