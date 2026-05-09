package sh.haven.core.tunnel

import com.jcraft.jsch.Proxy
import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.ProxySOCKS4
import com.jcraft.jsch.ProxySOCKS5
import sh.haven.core.data.db.entities.ConnectionProfile
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

/**
 * Single dispatch point for "how does this connection profile reach the
 * network?". Replaces ad-hoc `if (profile.tunnelConfigId != null) ...`
 * checks scattered across per-transport viewmodels.
 *
 * Three surfaces, each picked by the consumer based on what its transport
 * library can accept:
 *
 *  - [dial] — direct in-process [TunneledConnection]. Simplest path; for
 *    transports that own their socket creation in Kotlin (VNC, Reticulum).
 *  - [socketFactory] — javax.net.SocketFactory wrapping the same tunnel.
 *    For transports that take a factory (smbj's SmbConfig).
 *  - [socksEndpoint] — localhost SOCKS5 listener address. For transports
 *    behind FFI (rclone gomobile, IronRDP Rust) that can talk SOCKS5 but
 *    can't have their socket creation intercepted from Kotlin. Currently
 *    returns null; wired up when [Tunnel.socksAddress] (and the matching
 *    Go / tsnet listener) lands.
 *
 * All three return null when the profile has no tunnel selected — the
 * caller falls through to dialling a real Socket directly.
 *
 * A fourth surface, [jschProxy], is for JSch consumers. It wraps the
 * tunnel as a `com.jcraft.jsch.Proxy` and *also* honours the legacy
 * `proxyType`/`proxyHost`/`proxyPort` columns on [ConnectionProfile]
 * (SOCKS5 / SOCKS4 / HTTP). [dial] and [socketFactory] don't yet honour
 * the SOCKS columns; that will land alongside non-SSH transport adoption
 * (step 7 of #149).
 */
@Singleton
class TunnelResolver @Inject constructor(
    private val tunnelManager: TunnelManager,
) {

    suspend fun dial(
        profile: ConnectionProfile,
        host: String,
        port: Int,
        timeoutMs: Int,
    ): TunneledConnection? {
        tunnelFor(profile)?.let { return it.dial(host, port, timeoutMs) }
        // Fall through to legacy SOCKS / HTTP proxy if configured.
        proxySocketFactoryFor(profile, timeoutMs)?.let { factory ->
            val socket = factory.createSocket(host, port)
            return SocketTunneledConnection(socket)
        }
        return null
    }

    suspend fun socketFactory(profile: ConnectionProfile): SocketFactory? {
        tunnelFor(profile)?.let { return TunnelSocketFactory(it) }
        return proxySocketFactoryFor(profile)
    }

    suspend fun socksEndpoint(profile: ConnectionProfile): InetSocketAddress? {
        val tunnel = tunnelFor(profile) ?: return null
        return tunnel.socksAddress()
    }

    /**
     * com.jcraft.jsch.Proxy chain for a profile. WireGuard / Tailscale
     * tunnel takes precedence over the legacy `proxyType` columns; both
     * are mutually exclusive at the UI level. Returns null for direct
     * dialling.
     *
     * Does **not** handle SSH jump-host — that needs a live SSH session,
     * so callers resolve it before delegating here.
     */
    suspend fun jschProxy(profile: ConnectionProfile): Proxy? {
        tunnelFor(profile)?.let { return TunnelProxy(it) }
        val proxyHost = profile.proxyHost ?: return null
        return when (profile.proxyType) {
            "SOCKS5" -> ProxySOCKS5(proxyHost, profile.proxyPort)
            "SOCKS4" -> ProxySOCKS4(proxyHost, profile.proxyPort)
            "HTTP" -> ProxyHTTP(proxyHost, profile.proxyPort)
            else -> null
        }
    }

    /**
     * Release the tunnel acquired for [profileId]. Pair with any prior
     * call that returned a non-null result from [dial], [socketFactory],
     * [socketDialer], or [jschProxy]. Idempotent — safe to call on
     * disconnect even if the profile never acquired anything.
     */
    suspend fun release(profileId: String) {
        tunnelManager.release(profileId)
    }

    /**
     * Returns a non-suspend dialer closure that the caller invokes once
     * per TCP connection — useful for libraries that own their socket
     * lifecycle and dial outside a coroutine context (reticulum-kt's
     * `TCPClientInterface`, for example). The tunnel is acquired
     * up-front; subsequent dials reuse the same handle.
     *
     * Returns null when the profile has neither a tunnel nor a proxy
     * configured — caller falls through to its default `Socket()` dial.
     */
    suspend fun socketDialer(
        profile: ConnectionProfile,
    ): ((String, Int, Int) -> java.net.Socket)? {
        tunnelFor(profile)?.let { tunnel ->
            return { host, port, timeoutMs ->
                val conn = tunnel.dial(host, port, timeoutMs)
                TunneledSocket(conn, host, port)
            }
        }
        proxySocketFactoryFor(profile)?.let { factory ->
            return { host, port, _ ->
                factory.createSocket(host, port)
            }
        }
        return null
    }

    private suspend fun tunnelFor(profile: ConnectionProfile): Tunnel? {
        val tunnelId = profile.tunnelConfigId ?: return null
        return tunnelManager.acquire(tunnelId, profile.id)
    }

    private fun proxySocketFactoryFor(
        profile: ConnectionProfile,
        timeoutMs: Int = 30_000,
    ): ProxySocketFactory? {
        val type = profile.proxyType ?: return null
        val host = profile.proxyHost ?: return null
        return ProxySocketFactory(type, host, profile.proxyPort, timeoutMs)
    }
}

/**
 * Adapts a [Socket] (typically returned by [ProxySocketFactory]) to the
 * [TunneledConnection] interface so callers of [TunnelResolver.dial]
 * see a uniform shape regardless of whether the routing layer is a
 * userspace tunnel or a JDK-style proxy.
 */
private class SocketTunneledConnection(
    private val socket: java.net.Socket,
) : TunneledConnection {
    override val inputStream: java.io.InputStream = socket.getInputStream()
    override val outputStream: java.io.OutputStream = socket.getOutputStream()
    override fun close() {
        try { socket.close() } catch (_: Throwable) { /* best-effort */ }
    }
}
