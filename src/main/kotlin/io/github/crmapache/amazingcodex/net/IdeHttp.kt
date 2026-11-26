package io.github.crmapache.amazingcodex.net

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.net.JdkProxyProvider
import com.intellij.util.net.ssl.CertificateManager
import java.net.http.HttpClient
import java.time.Duration

/**
 * An HTTP client that goes out the way the IDE does.
 *
 * Corporate networks are the ordinary case rather than the exception, and both halves matter: the proxy
 * because there is no way out without it, the certificate store because a network that inspects TLS
 * presents a certificate the JDK's own trust store has never heard of.
 *
 * One place for all of the plugin's ways out - the relay, the usage report and Deepgram. It was three
 * copies of these fifteen lines, which is a very quiet way to fail: mend the proxy in one of them
 * and the other two go on being the parts of the plugin that a company's network refuses, with nothing
 * on screen to say why.
 *
 * The two halves are wrapped separately on purpose. A company that has no proxy but does have its own
 * certificate authority should not lose the second because the first was not there to be read.
 */
internal object IdeHttp {

    /**
     * The client the plugin goes out through, built once.
     *
     * Once because building it reads the IDE's proxy settings and its certificate store, and because a
     * client of one's own per caller is a connection pool of one's own: three of them means the same
     * host is reconnected to three times over.
     */
    val shared: HttpClient by lazy { client() }

    fun client(connectSeconds: Long = CONNECT_SECONDS): HttpClient {
        val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectSeconds))

        runCatching {
            val provider = JdkProxyProvider.getInstance()
            builder.proxy(provider.proxySelector)
            builder.authenticator(provider.authenticator)
        }.onFailure { thisLogger().info("The IDE's proxy settings were not available: ${it.message}") }

        runCatching {
            builder.sslContext(CertificateManager.getInstance().sslContext)
        }.onFailure { thisLogger().info("The IDE's certificate store was not available: ${it.message}") }

        return builder.build()
    }

    /**
     * A line for the log out of a failure that frequently has nothing to say for itself.
     *
     * The most ordinary failure of a request is a name that will not resolve - no network, a proxy that
     * eats DNS, an address whose record has not spread yet - and it is precisely the one that arrives
     * mute: the JDK's client wraps it in a ConnectException with no message, whose cause is another one
     * with no message, and only at the bottom of that chain is there an UnresolvedAddressException, which
     * carries nothing but its own name either. Logging the message alone wrote "did not go: null" and left
     * no way to tell a machine with no network from a service answering nonsense.
     *
     * So the chain is walked: the names of everything in it, and the first thing any of them thought to
     * say. Names only, never a stack - this goes to the IDE's log, which the person may well be reading.
     */
    fun describe(failure: Throwable): String {
        val chain = generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(CAUSE_DEPTH).toList()
        val names = chain.joinToString(" <- ") { it.javaClass.simpleName }
        val said = chain.firstNotNullOfOrNull { it.message?.trim()?.ifEmpty { null } }

        return if (said == null) names else "$names: $said"
    }

    /** Long enough for a proxy that thinks about it, short enough not to look like a hang. */
    private const val CONNECT_SECONDS = 15L

    /** Far enough down a chain of causes for the one that means something; a guard against a cycle too. */
    private const val CAUSE_DEPTH = 6
}
