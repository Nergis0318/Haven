package sh.haven.core.data.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How aggressively a tool gates a call on user consent.
 *
 * - [NEVER]              — read-only or otherwise harmless; no prompt.
 * - [ONCE_PER_SESSION]   — prompt once per (client, tool) pair, then
 *                          remember the decision until either the
 *                          session is cleared or the app is killed.
 * - [EVERY_CALL]         — prompt on every call. For the most
 *                          destructive operations.
 */
enum class ConsentLevel { NEVER, ONCE_PER_SESSION, EVERY_CALL }

/** Outcome the requesting RPC should act on. */
enum class ConsentDecision { ALLOW, DENY }

/**
 * One pending consent request the UI is expected to render and resolve.
 * `id` lets the UI route a tap on "Allow" / "Deny" back to the right
 * waiting RPC even when several pile up.
 */
data class ConsentRequest(
    val id: Long,
    val toolName: String,
    val clientHint: String?,
    /** Short human summary of what the tool will do, for the prompt body. */
    val summary: String,
    val requestedAt: Long = System.currentTimeMillis(),
)

/**
 * Coordinates "agent wants to do something destructive — does the user
 * consent?" interactions. Designed but not yet wired: every tool in
 * MCP v1 is read-only, so there are no callers today. The shape needs
 * to exist before the first write tool lands so we don't have to
 * retrofit it under pressure.
 *
 * ### Failure model
 *
 * The brand promise (VISION.md §85) is that the user always keeps the
 * wheel — *never* a silent automation channel. That has one
 * unforgiving consequence: if no Haven activity is in the foreground,
 * we cannot ask the user to consent, so the request **must** fail
 * closed. This manager tracks foreground state via
 * [setForegroundActive] and refuses non-NEVER requests when no
 * activity is visible. The user gets a notification (wired by the UI
 * layer when this becomes load-bearing) explaining what was blocked.
 *
 * ### Memoisation
 *
 * For [ConsentLevel.ONCE_PER_SESSION] decisions are cached against
 * the `(clientHint, toolName)` key for the lifetime of the manager
 * (i.e. until process death) or until [clearMemoised] is called from
 * Settings. The cache only stores ALLOW outcomes; a DENY is never
 * remembered, so a misclick can't lock the agent out forever.
 */
@Singleton
class AgentConsentManager @Inject constructor() {

    private val nextId = AtomicLong(1)
    private val mutex = Mutex()

    /**
     * Per-pending-request bundle: the deferred the caller is awaiting,
     * plus the `clientHint` carried with the request. The hint is needed
     * at respond-time so the bypass checkbox can attribute the bypass to
     * the right client. Replaces a plain `Map<Long, CompletableDeferred>`.
     */
    private data class PendingEntry(
        val deferred: CompletableDeferred<ConsentDecision>,
        val clientHint: String?,
    )

    private val pendingEntries = mutableMapOf<Long, PendingEntry>()
    private val sessionAllowed = mutableSetOf<String>()

    /**
     * Clients that the user has elected to bypass per-call prompts for
     * (the "Allow all MCP requests from 'X' until app restart" checkbox
     * on the consent sheet). Process-scoped, never persisted —
     * specifically cleared on app kill so a misclick can't survive an
     * unattended reboot. `clearMemoised()` also wipes this so Settings
     * → Forget remembered allows fully resets agent trust.
     */
    private val sessionBypassClients = mutableSetOf<String>()

    @Volatile
    private var foregroundActive: Boolean = false

    private val _pending = MutableStateFlow<List<ConsentRequest>>(emptyList())
    /** All currently-waiting requests, oldest first. Drives the bottom sheet. */
    val pending: StateFlow<List<ConsentRequest>> = _pending.asStateFlow()

    /**
     * Activity layer reports its visibility through this so we can
     * fail-closed when no one can answer the prompt.
     */
    fun setForegroundActive(active: Boolean) {
        foregroundActive = active
    }

    /**
     * Suspend until the user resolves the consent prompt for [toolName],
     * or [timeoutMs] elapses. Returns DENY on timeout, on a backgrounded
     * app, or on an explicit deny tap. Returns ALLOW if memoised at
     * [ConsentLevel.ONCE_PER_SESSION] or if the user taps allow.
     *
     * [level] of [ConsentLevel.NEVER] always returns ALLOW immediately
     * without touching the queue — the parameter exists so callers can
     * thread the level uniformly through the dispatcher.
     */
    suspend fun requestConsent(
        toolName: String,
        clientHint: String?,
        summary: String,
        level: ConsentLevel,
        timeoutMs: Long = 60_000,
    ): ConsentDecision {
        if (level == ConsentLevel.NEVER) return ConsentDecision.ALLOW

        // Session-wide bypass: if the user has previously checked
        // "Allow all MCP requests from '<client>' until app restart",
        // skip prompts for any non-NEVER tool from that client. Applies
        // to EVERY_CALL too — that's the "ill-advised" part the UX copy
        // warns about.
        if (clientHint != null) {
            mutex.withLock {
                if (clientHint in sessionBypassClients) return ConsentDecision.ALLOW
            }
        }

        val memoKey = memoKey(clientHint, toolName)
        if (level == ConsentLevel.ONCE_PER_SESSION) {
            mutex.withLock {
                if (memoKey in sessionAllowed) return ConsentDecision.ALLOW
            }
        }

        if (!foregroundActive) {
            // Fail closed: nothing can render the prompt right now,
            // and the §85 rule forbids letting the call proceed
            // anyway. Caller will translate this into the audit log
            // as Outcome.DENIED.
            return ConsentDecision.DENY
        }

        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<ConsentDecision>()
        val request = ConsentRequest(id = id, toolName = toolName, clientHint = clientHint, summary = summary)

        mutex.withLock {
            pendingEntries[id] = PendingEntry(deferred, clientHint)
            _pending.value = _pending.value + request
        }

        val decision = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: ConsentDecision.DENY

        mutex.withLock {
            pendingEntries.remove(id)
            _pending.value = _pending.value.filterNot { it.id == id }
            if (decision == ConsentDecision.ALLOW && level == ConsentLevel.ONCE_PER_SESSION) {
                sessionAllowed.add(memoKey)
            }
        }
        return decision
    }

    /**
     * Suspend until the user resolves a pairing prompt for a new MCP
     * client, or [timeoutMs] elapses. Distinct from [requestConsent] —
     * a paired client is a *connection-time* gate (in `initialize`),
     * not a *per-tool* gate. Returns DENY on timeout / background.
     *
     * The caller (McpServer) is responsible for persisting the client
     * name on ALLOW; this manager doesn't touch DataStore.
     */
    suspend fun requestClientPairing(
        clientName: String,
        clientVersion: String?,
        timeoutMs: Long = 60_000,
    ): ConsentDecision {
        if (!foregroundActive) {
            Log.w(LOG_TAG, "requestClientPairing('$clientName'): foreground=false — failing closed")
            return ConsentDecision.DENY
        }

        val id = nextId.getAndIncrement()
        Log.i(LOG_TAG, "requestClientPairing('$clientName' v${clientVersion ?: "?"}): queueing prompt id=$id")
        val deferred = CompletableDeferred<ConsentDecision>()
        val versionSuffix = clientVersion?.takeIf { it.isNotBlank() }?.let { " v$it" } ?: ""
        val request = ConsentRequest(
            id = id,
            toolName = PAIRING_TOOL_NAME,
            clientHint = clientName,
            summary = "MCP client '$clientName'$versionSuffix wants to connect to Haven. " +
                "Once paired, it will be able to call tools — each tool still asks for " +
                "consent unless you elect to bypass that with the per-call checkbox.",
        )

        mutex.withLock {
            pendingEntries[id] = PendingEntry(deferred, clientName)
            _pending.value = _pending.value + request
        }

        val raw = withTimeoutOrNull(timeoutMs) { deferred.await() }
        val decision = raw ?: ConsentDecision.DENY
        Log.i(LOG_TAG, "requestClientPairing('$clientName') id=$id resolved: ${if (raw == null) "TIMEOUT->DENY" else decision.toString()}")

        mutex.withLock {
            pendingEntries.remove(id)
            _pending.value = _pending.value.filterNot { it.id == id }
        }
        return decision
    }

    /**
     * Called by the bottom-sheet UI when the user taps Allow / Deny.
     * When [bypassClient] is true and the decision is ALLOW, the
     * pending request's clientHint is added to [sessionBypassClients]
     * so subsequent per-tool prompts from that client short-circuit
     * to ALLOW. The flag is ignored on DENY or when the request had no
     * clientHint (e.g. an `_pairing` request — bypass on pairing is a
     * contradiction the UI prevents at render time).
     */
    suspend fun respond(
        requestId: Long,
        decision: ConsentDecision,
        bypassClient: Boolean = false,
    ) {
        mutex.withLock {
            val entry = pendingEntries[requestId] ?: return
            if (decision == ConsentDecision.ALLOW && bypassClient && entry.clientHint != null) {
                sessionBypassClients.add(entry.clientHint)
            }
            entry.deferred.complete(decision)
        }
    }

    /** Used by Settings → "Forget remembered allows". */
    suspend fun clearMemoised() {
        mutex.withLock {
            sessionAllowed.clear()
            sessionBypassClients.clear()
        }
    }

    private fun memoKey(clientHint: String?, toolName: String): String =
        "${clientHint ?: "unknown"}::$toolName"

    companion object {
        /**
         * Sentinel tool name carried by client-pairing prompts so the UI
         * can render a "Pair MCP client?" header instead of an
         * "Agent action requested" one, and so the bypass checkbox can
         * be suppressed on the pairing prompt itself (the user isn't
         * agreeing to specific calls yet — they're agreeing to let this
         * client even speak).
         */
        const val PAIRING_TOOL_NAME = "_pairing"

        private const val LOG_TAG = "AgentConsent"
    }
}
