package sh.haven.app.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.ffmpeg.FfmpegExecutor
import sh.haven.core.ffmpeg.HlsStreamServer
import sh.haven.core.ffmpeg.TranscodeCommand
import sh.haven.core.local.WaylandSocketHelper
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.sftp.SftpStreamServer
import java.io.File

/**
 * Tool implementations for the MCP agent transport.
 *
 * Every tool:
 * 1. Has a name, a human description, and a JSON Schema for its
 *    arguments, discoverable via `tools/list`.
 * 2. Runs its work on the same repositories and session managers
 *    the UI uses, so "what the agent sees" and "what the user sees"
 *    come from the same source of truth.
 * 3. Returns a JSON object that both serialises to text (for MCP's
 *    `content[].text` field) and is structured (for
 *    `structuredContent`).
 *
 * v1 is read-only. Mutating tools (convert, upload, disconnect,
 * delete, add-port-forward, ...) will ship in v2 behind a per-action
 * consent mechanism that lives in the UI.
 */
internal class McpTools(
    private val context: Context,
    private val connectionRepository: ConnectionRepository,
    private val portForwardRepository: PortForwardRepository,
    private val sshSessionManager: SshSessionManager,
    private val sessionManagerRegistry: SessionManagerRegistry,
    private val rcloneClient: RcloneClient,
    private val sftpStreamServer: SftpStreamServer,
    private val hlsStreamServer: HlsStreamServer,
    private val ffmpegExecutor: FfmpegExecutor,
    private val preferencesRepository: UserPreferencesRepository,
    private val terminalFontInstaller: TerminalFontInstaller,
    private val localSessionManager: LocalSessionManager,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val transportSelector: sh.haven.feature.sftp.transport.TransportSelector,
    private val workspaceRepository: sh.haven.core.data.repository.WorkspaceRepository,
    private val workspaceLauncher: sh.haven.app.workspace.WorkspaceLauncher,
    private val tunnelConfigRepository: sh.haven.core.data.repository.TunnelConfigRepository,
    private val tunnelManager: sh.haven.core.tunnel.TunnelManager,
    private val terminalSessionRegistry: sh.haven.feature.terminal.agent.TerminalSessionRegistry,
    private val portKnocker: sh.haven.core.knock.PortKnocker,
    private val connectionLogRepository: sh.haven.core.data.repository.ConnectionLogRepository,
    private val servedFileTracker: sh.haven.core.data.agent.ServedFileTracker,
    private val syncProfileRepository: sh.haven.core.data.repository.SyncProfileRepository,
    private val terminalInputQueue: TerminalInputQueue,
    private val prootInstallLogRepository: sh.haven.core.data.repository.ProotInstallLogRepository,
    private val sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository,
    private val totpSecretRepository: sh.haven.core.data.repository.TotpSecretRepository,
    private val desktopSessionRegistry: sh.haven.core.data.desktop.DesktopSessionRegistry,
    /**
     * The MCP HTTP server's live bound port. Evaluated lazily so the
     * reverse-tunnel auto-detect follows an 8731+ fallback instead of a
     * hardcoded 8730. Defaults to the conventional port for tests.
     */
    private val mcpPortProvider: () -> Int = { MCP_REVERSE_TUNNEL_PORT },
) {

    /**
     * Look up a profile's user-facing label for use in consent prompts.
     * Falls back to the profileId if the lookup fails — better to show
     * an opaque ID than to crash the prompt builder.
     *
     * Called from the synchronous summarise lambdas, so wrapping the
     * repository's `suspend fun getById` in [runBlocking] is required.
     * Profile lookups hit a small Room table and always return in <1 ms,
     * so the blocking is harmless.
     */
    private fun profileLabel(profileId: String): String =
        runBlocking {
            connectionRepository.getById(profileId)?.label
        } ?: profileId

    /**
     * Same shape as [profileLabel], for workspace ids. Used by the
     * compose_workspace consent prompt so the user sees the workspace
     * name rather than a UUID.
     */
    private fun workspaceLabel(workspaceId: String): String =
        runBlocking {
            workspaceRepository.getWorkspace(workspaceId)?.profile?.name
        } ?: workspaceId

    /** Tool registry: name → handler. */
    private val tools: Map<String, ToolHandler> = linkedMapOf(
        "get_app_info" to ToolHandler(
            description = "Return Haven version, active rclone remotes, and which optional features are available in this build.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> getAppInfo() },

        "list_connections" to ToolHandler(
            description = "List saved connection profiles (SSH, Mosh, VNC, RDP, SMB, rclone, local, Reticulum). Secrets like passwords and keys are redacted.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listConnections() },

        "list_sessions" to ToolHandler(
            description = "List currently registered sessions across all transports (ssh, mosh, et, reticulum, rdp, smb, local) with sessionId, profileId, label, status (connecting, connected, reconnecting, disconnected, error), and transport. SSH sessions additionally include sessionManager, channel state, jump-session linkage, and active port forwards.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listSessions() },

        "list_desktop_sessions" to ToolHandler(
            description = "List open remote-desktop tabs (VNC/RDP) by connection profile, with their live status (connecting, connected, error). These are Desktop-screen tabs, not transport sessions — a VNC/RDP-over-SSH desktop has its SSH tunnel in list_sessions and its own connect state here. Use after connect_profile to confirm a desktop reached 'connected', and after disconnect_profile to confirm the tab is gone (profile absent from the list).",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listDesktopSessions() },

        "list_rclone_remotes" to ToolHandler(
            description = "List rclone cloud storage remotes configured in Haven.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listRcloneRemotes() },

        "list_directory" to ToolHandler(
            description = "List entries at a path on any connected backend (local, SSH/SFTP, SMB, rclone). Resolves the right driver from profileId — pass the literal string 'local' for the device filesystem, otherwise a profile ID from list_connections. Returns name, path, isDir, size, modTime, permissions, and mimeType for each entry. Replaces list_sftp_directory and list_rclone_directory; those still work as deprecated aliases.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Connection profile ID, or 'local' for the device filesystem.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Directory path to list. Default '/' (POSIX backends), '' (rclone root), '/' for local synthetic-roots view.")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
        ) { args -> listDirectory(args) },

        "list_rclone_directory" to ToolHandler(
            description = "DEPRECATED: prefer list_directory(profileId=..., path=...). List files and subdirectories at a given path on an rclone remote. Returns name, isDir, size, mimeType, and modTime for each entry.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("remote", JSONObject().apply {
                        put("type", "string")
                        put("description", "Name of the rclone remote, e.g. 'gdrive'.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Path within the remote, relative to the remote root. Use '' for the root.")
                    })
                })
                put("required", JSONArray().put("remote"))
            },
        ) { args -> listRcloneDirectory(args) },

        "start_rclone_sync" to ToolHandler(
            description = "Start an async rclone transfer between two remote paths. mode=copy adds new/updated files (no deletes); mode=sync (a.k.a. \"Mirror\" in the UI) makes destination identical to source and deletes extras; mode=move copies then removes source files. srcFs/dstFs use rclone's remote-prefixed notation, e.g. \"gdrive:Backup/Photos\" or \"gdrive:\" for the remote root. Returns { jobId, mode } — poll get_rclone_sync_status to read finished/success and the transfer/delete counters. Honours the same optional filter and dryRun fields the SFTP sync dialog exposes.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("srcFs", JSONObject().apply { put("type", "string"); put("description", "Source remote path, e.g. \"gdrive:Backup/Photos\".") })
                    put("dstFs", JSONObject().apply { put("type", "string"); put("description", "Destination remote path, e.g. \"gdrive:Mirror/Photos\".") })
                    put("mode", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("copy").put("sync").put("move"))
                        put("description", "copy = add/update only, sync = mirror (deletes extras in dst), move = copy then delete from src.")
                    })
                    put("dryRun", JSONObject().apply { put("type", "boolean"); put("description", "If true, simulate without writing.") })
                    put("includePatterns", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                        put("description", "Optional include globs, e.g. [\"*.jpg\"].")
                    })
                    put("excludePatterns", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                        put("description", "Optional exclude globs.")
                    })
                    put("minSize", JSONObject().apply { put("type", "string"); put("description", "Optional minimum file size, e.g. \"1K\", \"5M\".") })
                    put("maxSize", JSONObject().apply { put("type", "string"); put("description", "Optional maximum file size.") })
                    put("bandwidthLimit", JSONObject().apply { put("type", "string"); put("description", "Optional bandwidth limit, e.g. \"10M\".") })
                })
                put("required", JSONArray().put("srcFs").put("dstFs").put("mode"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val mode = args.optString("mode", "?")
                val src = args.optString("srcFs", "?")
                val dst = args.optString("dstFs", "?")
                val dry = if (args.optBoolean("dryRun")) " (dry run)" else ""
                "rclone $mode$dry: $src → $dst?"
            },
        ) { args -> startRcloneSync(args) },

        "get_rclone_sync_status" to ToolHandler(
            description = "Poll the status of an async rclone job started by start_rclone_sync. Returns finished/success/error plus live transfer stats: bytes, totalBytes, speed, transfers, totalTransfers, errors, deletes, deletedDirs. If jobId is omitted, reports on the most recently started job (or returns active=false if none).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("jobId", JSONObject().apply { put("type", "integer"); put("description", "Job id returned by start_rclone_sync. Optional — defaults to the active job.") })
                })
            },
        ) { args -> getRcloneSyncStatus(args) },

        "cancel_rclone_sync" to ToolHandler(
            description = "Cancel a running rclone job started by start_rclone_sync. If jobId is omitted, cancels the active job. No-op if the job is already finished or never existed.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("jobId", JSONObject().apply { put("type", "integer"); put("description", "Job id to cancel. Optional — defaults to the active job.") })
                })
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Cancel the running rclone sync job?" },
        ) { args -> cancelRcloneSync(args) },

        "get_rclone_stats" to ToolHandler(
            description = "Return rclone's global transfer counters (bytes, totalBytes, speed, transfers, totalTransfers, errors, deletes, deletedDirs). These reset only when reset_rclone_stats is called or a new sync resets them at start.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> getRcloneStats() },

        "reset_rclone_stats" to ToolHandler(
            description = "Reset rclone's global transfer counters to zero. Useful when running ad-hoc operations outside start_rclone_sync (which already resets on start).",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> resetRcloneStats() },

        "list_saved_sync_profiles" to ToolHandler(
            description = "List the user's saved rclone sync configurations (#159) — the named src/dst/mode/filters bundles surfaced in the SFTP folder-sync dialog's dropdown. Returns id, name, srcFs, dstFs, mode (copy/sync/move), include/exclude patterns, optional minSize/maxSize/bandwidthLimit, createdAt, lastRunAt. Sorted most-recently-run first.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listSavedSyncProfiles() },

        "save_sync_profile" to ToolHandler(
            description = "Create or update a named rclone sync configuration (#159). Pass an `id` to overwrite an existing one; omit it to create. mode accepts copy/sync/move (or \"mirror\" as a sync alias). includePatterns/excludePatterns are arrays of glob strings. Returns the saved profile's id and the full resolved fields. Mutates Haven state, gated by EVERY_CALL consent.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply { put("type", "string"); put("description", "Existing profile id to overwrite. Omit to create a new one.") })
                    put("name", JSONObject().apply { put("type", "string"); put("description", "Display name shown in the dropdown.") })
                    put("srcFs", JSONObject().apply { put("type", "string"); put("description", "Source remote path, e.g. \"gdrive:Backup/Photos\".") })
                    put("dstFs", JSONObject().apply { put("type", "string"); put("description", "Destination remote path.") })
                    put("mode", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("copy").put("sync").put("move"))
                        put("description", "copy/sync/move; \"mirror\" is accepted as a sync alias.")
                    })
                    put("includePatterns", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                    })
                    put("excludePatterns", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                    })
                    put("minSize", JSONObject().apply { put("type", "string") })
                    put("maxSize", JSONObject().apply { put("type", "string") })
                    put("bandwidthLimit", JSONObject().apply { put("type", "string") })
                })
                put("required", JSONArray().put("name").put("srcFs").put("dstFs").put("mode"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val name = args.optString("name", "?")
                val mode = args.optString("mode", "?")
                val verb = if (args.has("id") && !args.isNull("id")) "Update" else "Save"
                "$verb \"$name\" ($mode: ${args.optString("srcFs", "?")} → ${args.optString("dstFs", "?")})?"
            },
        ) { args -> saveSyncProfile(args) },

        "queue_terminal_input" to ToolHandler(
            description = "Power-user: queue text to be typed into any connected SSH session at the next matching prompt. Haven polls the session's stdout, matches `promptPattern` against the tail (ANSI escapes stripped, regex MULTILINE), then types `text + submitKey` via the session's tty when the pattern appears. Use cases include responding to interactive prompts in install scripts (`Continue? [y/N]` → `y`, or `Path:` → `/usr/local`), driving REPLs (Python, psql, Claude Code, etc.), and chaining steps where the agent waits for one prompt then types into the next. Defaults are tuned for the common \"drive an interactive shell or REPL\" case; supply explicit `promptPattern` / `submitKey` / `sessionId` when targeting something specific. Returns immediately with a queueId; delivery happens out-of-turn whenever the prompt appears (or the queue times out). Caveat: within one SSH session, only the *foreground* tmux pane receives the typed text — for multi-pane setups, pass `sessionId` to the right session and ensure the target pane is foreground. Gated by Settings → Agent endpoint → \"Allow agents to queue terminal input\" *and* per-call consent (with an \"Allow for N min\" option for collaborative windows).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().apply { put("type", "string"); put("description", "Text to type. `submitKey` is appended automatically.") })
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "SSH session id from list_sessions. Optional — defaults to the SSH session carrying the MCP reverse tunnel on port 8730 (which, in the agent-drives-its-own-conversation case, is the session running the agent's REPL). For unrelated sessions, pass this explicitly.") })
                    put("promptPattern", JSONObject().apply { put("type", "string"); put("description", "Regex matched against the tail of the SSH scrollback to trigger delivery. Default `[\\\$#%>❯]\\s*\$` matches the trailing prompt glyph of common interactive shells (bash `\$`, root `#`, csh `%`, traditional `>`, fish/Claude Code/starship `❯`) at end-of-line. For specific programs, supply a pattern that matches their input prompt — e.g. `\\[y/N\\]\\s*\$` or `Password:\\s*\$` or `(?:postgres|mydb)=#\\s*\$`. ANSI escapes are stripped before matching; regex is MULTILINE.") })
                    put("submitKey", JSONObject().apply { put("type", "string"); put("description", "Key bytes sent after the text. Default `\\r` — what TTYs in cooked mode translate to NL, and what programs in raw mode (Claude Code, vim, less, fzf, readline-based shells) read as Enter. Use `\\n` for line-buffered programs reading stdin directly without a tty. Use `\"\"` (empty) to leave the text in the input buffer without submitting (e.g. pre-fill a prompt the user will edit).") })
                    put("timeoutSeconds", JSONObject().apply { put("type", "integer"); put("description", "Give up if the prompt hasn't appeared in this many seconds. Default 60.") })
                })
                put("required", JSONArray().put("text"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val text = args.optString("text", "").take(80)
                "Queue \"$text\" to be typed into the SSH session at the next prompt?"
            },
        ) { args -> queueTerminalInput(args) },

        // Deprecated alias for queue_terminal_input — keeps clients that
        // bound against the old name working for one release. Same
        // handler, same gate, same consent shape; just routes through
        // queueTerminalInput so the implementation only lives in one
        // place. Drop after the next final release. (#161)
        "queue_self_message" to ToolHandler(
            description = "DEPRECATED: alias for `queue_terminal_input` kept for one release. Use queue_terminal_input — same arguments, same behaviour, plus a `submitKey` parameter you didn't have here.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().apply { put("type", "string") })
                    put("sessionId", JSONObject().apply { put("type", "string") })
                    put("promptPattern", JSONObject().apply { put("type", "string") })
                    put("timeoutSeconds", JSONObject().apply { put("type", "integer") })
                })
                put("required", JSONArray().put("text"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val text = args.optString("text", "").take(80)
                "Queue \"$text\" to be typed into the SSH session at the next prompt?"
            },
        ) { args -> queueTerminalInput(args) },

        "delete_sync_profile" to ToolHandler(
            description = "Delete a saved rclone sync configuration by id. The dialog's dropdown updates immediately. EVERY_CALL consent because it's destructive (the user's saved config is gone after).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply { put("type", "string"); put("description", "Profile id from list_saved_sync_profiles.") })
                })
                put("required", JSONArray().put("id"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete saved sync configuration ${args.optString("id")}?" },
        ) { args -> deleteSyncProfile(args) },

        "list_sftp_directory" to ToolHandler(
            description = "DEPRECATED: prefer list_directory(profileId=..., path=...). List files at a path on a connected SFTP profile. Requires an already-connected SSH/SFTP session for the profile.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "ID of the connected SSH/SFTP profile.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute directory path to list. Defaults to '.'")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
        ) { args -> listSftpDirectory(args) },

        "stream_sftp_file" to ToolHandler(
            description = "Start an HLS stream for an SFTP file and return the playlist URL. Reads via a loopback HTTP bridge so no bulk download is needed. Requires a connected SSH/SFTP session. Stops any prior HLS stream.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "ID of the connected SSH/SFTP profile.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path of the media file on the SFTP server.")
                    })
                })
                put("required", JSONArray().put("profileId").put("path"))
            },
        ) { args -> streamSftpFile(args) },

        "serve_file" to ToolHandler(
            description = "Publish a single file from any connected backend (local, SFTP, SMB, rclone) as a short-lived loopback HTTP URL the caller can curl to its own filesystem. Returns { url, size, mimeType }. Bytes are streamed over HTTP rather than returned inline through JSON-RPC. Gated by Settings → Agent endpoint → \"Allow agents to read file contents\" and confirmed per-call by a consent prompt.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Connection profile ID, or 'local' for the device filesystem.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path of the file on the chosen backend.")
                    })
                })
                put("required", JSONArray().put("profileId").put("path"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val pid = args.optString("profileId")
                val p = args.optString("path")
                "Let the agent download '$p' from '${profileLabel(pid)}' to its workstation"
            },
        ) { args -> serveFile(args) },

        "stop_stream" to ToolHandler(
            description = "Stop any currently running HLS stream started by stream_sftp_file or the UI.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> stopStream() },

        "play_file" to ToolHandler(
            description = "Open a media URL in the system player (VLC, MX Player, Chrome, etc.) via Android's ACTION_VIEW intent. Typically the playerUrl/playlistUrl returned by stream_sftp_file, or any http/https/content URL the agent already knows. The user's preferred app picker (or default app) decides what handles it — Haven only kicks off the intent.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "URL or content URI to open. http://, https://, file:// (rare; prefer FileProvider URIs) and content:// are accepted.")
                    })
                    put("mimeType", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional MIME hint, e.g. 'video/mp4', 'application/vnd.apple.mpegurl' for HLS. Auto-detected from URL extension when omitted.")
                    })
                })
                put("required", JSONArray().put("url"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> playFile(args) },

        "open_convert_dialog_with_args" to ToolHandler(
            description = "Stage a conversion in the SFTP screen's convert dialog with the given container / codec defaults. Switches to the SFTP tab and opens the dialog; the user reviews and taps Convert to actually run ffmpeg. Tap-equivalent — the agent suggests, the user confirms. Use convert_file (EVERY_CALL consent) to skip the dialog and run the conversion directly.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Connection profile ID. Use list_connections to find IDs.")
                    })
                    put("sourcePath", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path of the source file.")
                    })
                    put("container", JSONObject().apply {
                        put("type", "string")
                        put("description", "Pre-selected container, e.g. 'mp4', 'mkv', 'webm', 'mp3'. Optional — defaults to source extension.")
                    })
                    put("videoEncoder", JSONObject().apply {
                        put("type", "string")
                        put("description", "Pre-selected video codec, e.g. 'libx264', 'libx265', 'copy'. Optional — defaults to 'copy'.")
                    })
                    put("audioEncoder", JSONObject().apply {
                        put("type", "string")
                        put("description", "Pre-selected audio codec, e.g. 'aac', 'libopus', 'copy'. Optional — defaults to 'copy'.")
                    })
                })
                put("required", JSONArray().put("profileId").put("sourcePath"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> openConvertDialogWithArgs(args) },

        "focus_terminal_session" to ToolHandler(
            description = "Switch to the Terminal tab and bring the session with this sessionId to the front. Tap-equivalent — same effect as the user tapping the Terminal tab and tapping the session header. Use list_sessions to discover live sessionIds; stale IDs drop silently without error.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Active session ID (from list_sessions). Must be a session attached to a Terminal tab.")
                    })
                })
                put("required", JSONArray().put("sessionId"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> focusTerminalSession(args) },

        "navigate_sftp_browser" to ToolHandler(
            description = "Switch to the Files tab and open the file browser at the given path on the given profile. Tap-equivalent — same effect as the user tapping into the SFTP screen and entering the path. The path is interpreted by whichever backend the profile resolves to (POSIX absolute for SSH/Local, share-relative for SMB, remote-relative for rclone).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Connection profile ID (or the literal string \"local\" for the device filesystem). Use list_connections to find IDs.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Directory path to open. Default '/' for SSH/Local, '' for rclone (treated as remote root).")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> navigateSftpBrowser(args) },

        "open_file_in_editor" to ToolHandler(
            description = "Open a text file in Haven's built-in editor (TextMate-syntax-highlighted, with Save). Routes to the SFTP/Files tab and loads the file from the given profile's backend (SSH, SMB, rclone, or the literal \"local\" for the device filesystem). The file is read on demand by the active backend; binary files render as garbled UTF-8 — use this for source code, config, logs, etc.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Connection profile ID (or the literal string \"local\" for the device filesystem). Use list_connections to find IDs.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path to the file to open. POSIX absolute for SSH/Local, share-relative for SMB, remote-relative for rclone.")
                    })
                })
                put("required", JSONArray().put("profileId").put("path"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> openFileInEditor(args) },

        "read_terminal_scrollback" to ToolHandler(
            description = "Return the most recent bytes of raw SSH stdout for an active terminal session, exactly as the user sees them (ANSI escapes, OSC markers, control bytes preserved). Use list_sessions to discover sessionIds. The buffer is capped at 256 KiB per session and rolls older bytes off; the human terminal still keeps its own visual scrollback separately.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Active session ID (from list_sessions).")
                    })
                    put("maxBytes", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Maximum bytes to return. Default 16384, hard-capped at 262144.")
                    })
                })
                put("required", JSONArray().put("sessionId"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> readTerminalScrollback(args) },

        "read_terminal_snapshot" to ToolHandler(
            description = "Return a structured snapshot of an active terminal session: dimensions, cursor row/col, terminal title, scrollback line count + current scrollbackPosition, the remote-driven terminal modes (mouseMode / activeMouseMode 1000|1002|1003 / bracketPasteMode), an oscEvents object with the last-seen OSC 52 clipboard-set / OSC 7 cwd / OSC 8 hyperlink / OSC 9|777 notification, and the visible-screen lines as plain text (with `softWrapped` flag per line, and optional OSC 133 semantic segments). Use list_sessions to discover sessionIds. Distinct from read_terminal_scrollback, which returns raw bytes; this is the parsed view useful for cursor-aware tooling, prompt detection, and asserting OSC/mouse-mode round-trips.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Active session ID (from list_sessions). Must have an attached terminal tab.")
                    })
                    put("includeSemanticSegments", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "If true, include each line's OSC 133 prompt-marker segments (PROMPT / COMMAND_INPUT / COMMAND_OUTPUT / COMMAND_FINISHED / ANNOTATION). Default false.")
                    })
                    put("maxLines", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Maximum number of visible-screen lines to include from the top. Default returns all visible rows. Cursor and dimensions are always present regardless.")
                    })
                })
                put("required", JSONArray().put("sessionId"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> readTerminalSnapshot(args) },

        "get_selection" to ToolHandler(
            description = "Return the current text-selection state for an active terminal session: { active, mode (NONE/CHARACTER/WORD/LINE), range: { startRow, startCol, endRow, endCol } | null }. Reads termlib's SelectionController; valid only while the session has an attached terminal tab.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Active session ID with an attached terminal tab.")
                    })
                })
                put("required", JSONArray().put("sessionId"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> getSelection(args) },

        "read_clipboard" to ToolHandler(
            description = "Return the system clipboard's primary plain-text content. Returns { text } where text is null when the clipboard is empty or non-text (image, intent, etc.). On Android 10+ the system enforces foreground/IME restrictions on clipboard reads; this call may return null even when the clipboard has content if Haven isn't currently focused.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> readClipboard() },

        "get_preference" to ToolHandler(
            description = "Read a Haven user preference by key. Whitelisted keys: terminal_scrollback_rows, terminal_tap_to_position_cursor, terminal_font_size, terminal_color_scheme, mouse_input_enabled, mouse_drag_selects, terminal_right_click, mcp_tunnel_endpoint_profile_id, mcp_wireguard_enabled. Returns { key, value } where value's type follows the preference's type (int / boolean / string).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("key", JSONObject().apply {
                        put("type", "string")
                        put("description", "Preference key (see whitelist in description).")
                    })
                })
                put("required", JSONArray().put("key"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> getPreference(args) },

        // --- Write tools (require consent) ------------------------------

        "disconnect_profile" to ToolHandler(
            description = "Disconnect every live session for a profile across all transports (SSH, Mosh, Eternal Terminal, RDP, VNC, SMB, Reticulum, local). Use list_connections to find profileIds.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "ID of the connection profile to disconnect.")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Disconnect from \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> disconnectProfile(args) },

        "add_port_forward" to ToolHandler(
            description = "Save a port-forward rule on an SSH profile. If the profile is currently connected the rule is also activated immediately. Type LOCAL=`-L` (local→remote), REMOTE=`-R` (remote→local), DYNAMIC=`-D` (SOCKS5 proxy server). Returns the saved rule's id and (when activated) the actually-bound port.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Owning profile ID.") })
                    put("type", JSONObject().apply { put("type", "string"); put("description", "LOCAL | REMOTE | DYNAMIC.") })
                    put("bindAddress", JSONObject().apply { put("type", "string"); put("description", "Bind address. Default 127.0.0.1.") })
                    put("bindPort", JSONObject().apply { put("type", "integer"); put("description", "Bind port. 0 = OS picks (REMOTE only).") })
                    put("targetHost", JSONObject().apply { put("type", "string"); put("description", "Target host (LOCAL/REMOTE only). Ignored for DYNAMIC.") })
                    put("targetPort", JSONObject().apply { put("type", "integer"); put("description", "Target port (LOCAL/REMOTE only). Ignored for DYNAMIC.") })
                })
                put("required", JSONArray().put("profileId").put("type").put("bindPort"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId")
                val type = args.optString("type")
                val bp = args.optInt("bindPort")
                val target = if (type == "DYNAMIC") "(SOCKS)"
                    else "${args.optString("targetHost", "?")}:${args.optInt("targetPort", 0)}"
                "Add $type port forward $bp → $target on \"${profileLabel(pid)}\"?"
            },
        ) { args -> addPortForward(args) },

        "remove_port_forward" to ToolHandler(
            description = "Delete a port-forward rule by id, and deactivate it on the live session if the owning profile is currently connected.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("ruleId", JSONObject().apply { put("type", "string"); put("description", "ID of the rule to remove.") })
                })
                put("required", JSONArray().put("ruleId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Remove port-forward rule ${args.optString("ruleId").take(8)}…?" },
        ) { args -> removePortForward(args) },

        "upload_file" to ToolHandler(
            description = "Write a local file to a path on any connected backend (local, SSH, SMB, rclone). Source must live under Haven's app cache (context.cacheDir) — the agent has no other writable surface, so this constraint blocks reads of arbitrary device files via the upload destination. Currently uses small-file semantics (loads the source into memory); streaming variants ship in a later #126 stage. Replaces upload_file_to_sftp; that still works as a deprecated alias for the SSH streaming path.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Connection profile ID, or 'local' for the device filesystem.") })
                    put("localPath", JSONObject().apply { put("type", "string"); put("description", "Absolute path to a file under context.cacheDir on the device.") })
                    put("remotePath", JSONObject().apply { put("type", "string"); put("description", "Destination path on the target backend.") })
                })
                put("required", JSONArray().put("profileId").put("localPath").put("remotePath"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val pid = args.optString("profileId")
                val local = args.optString("localPath", "?")
                val size = try { File(local).length() } catch (_: Exception) { 0L }
                val sizeStr = if (size > 0) "${size / 1024} KiB" else "(unknown size)"
                "Upload $sizeStr → ${args.optString("remotePath")} on \"${profileLabel(pid)}\"?"
            },
        ) { args -> uploadFile(args) },

        "delete_file" to ToolHandler(
            description = "Delete a file (not a directory) on any connected backend (local, SSH, SMB, rclone). Replaces delete_sftp_file; that still works as a deprecated alias.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Connection profile ID, or 'local' for the device filesystem.") })
                    put("path", JSONObject().apply { put("type", "string"); put("description", "Path of the file to delete.") })
                })
                put("required", JSONArray().put("profileId").put("path"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete ${args.optString("path")} on \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> deleteFile(args) },

        "upload_file_to_sftp" to ToolHandler(
            description = "DEPRECATED: prefer upload_file(profileId=..., localPath=..., remotePath=...). Upload a local file to a path on a connected SFTP profile. Source must be a path under Haven's app cache (context.cacheDir) — the agent has no other writable surface, so this constraint blocks reads of arbitrary files via the upload destination. Requires a connected SSH/SFTP session. Uses streaming SFTP put (no in-memory buffer); use this for files larger than ~50 MiB until upload_file gains streaming support.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Connected SSH/SFTP profile ID.") })
                    put("localPath", JSONObject().apply { put("type", "string"); put("description", "Absolute path to a file under context.cacheDir on the device.") })
                    put("remotePath", JSONObject().apply { put("type", "string"); put("description", "Absolute destination path on the SFTP server.") })
                })
                put("required", JSONArray().put("profileId").put("localPath").put("remotePath"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val pid = args.optString("profileId")
                val local = args.optString("localPath", "?")
                val size = try { File(local).length() } catch (_: Exception) { 0L }
                val sizeStr = if (size > 0) "${size / 1024} KiB" else "(unknown size)"
                "Upload $sizeStr → ${args.optString("remotePath")} on \"${profileLabel(pid)}\"?"
            },
        ) { args -> uploadFileToSftp(args) },

        "delete_sftp_file" to ToolHandler(
            description = "DEPRECATED: prefer delete_file(profileId=..., path=...). Delete a file (not directory) from a connected SFTP profile. Requires a connected SSH/SFTP session.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Connected SSH/SFTP profile ID.") })
                    put("path", JSONObject().apply { put("type", "string"); put("description", "Absolute path of the file to delete.") })
                })
                put("required", JSONArray().put("profileId").put("path"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete ${args.optString("path")} on \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> deleteSftpFile(args) },

        "send_terminal_input" to ToolHandler(
            description = "Send UTF-8 text to an active terminal session as if the user typed it. Newlines (\\n) execute the current command line — there is no separate \"send Enter\" mode. Hard cap 4096 bytes per call.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID (from list_sessions). Must have an attached terminal.") })
                    put("text", JSONObject().apply { put("type", "string"); put("description", "UTF-8 text to send. Include trailing \\n to execute.") })
                })
                put("required", JSONArray().put("sessionId").put("text"))
            },
            // Once the user approves the agent typing into a session, per-call
            // re-approval is friction without added safety — the session is
            // already agent-controlled, and EVERY_CALL made interactive shell
            // driving unusable (each keystroke-batch blocked the request on a
            // device tap and timed out when unattended). Matches open_local_shell.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val text = args.optString("text", "")
                val preview = if (text.length > 80) text.substring(0, 80) + "…" else text
                val visible = preview.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                "Send to terminal: \"$visible\"?"
            },
        ) { args -> sendTerminalInput(args) },

        "feed_terminal_output" to ToolHandler(
            description = "Inject raw bytes into a terminal session's OUTPUT stream — as if they had arrived from the remote — running the exact pipeline the live data callback uses (OSC scan → mouse-mode scan → emulator). Distinct from send_terminal_input, which sends to the PTY input as if typed. Use this to deterministically exercise output-side parsing without a cooperating remote: e.g. feed an OSC 52 sequence to test the clipboard round-trip, a DECSET 1000/1002/1003 to flip mouseMode, an OSC 8 hyperlink, or a partial escape split across two calls (the OSC scanner keeps state between calls). Provide exactly one of `text` (UTF-8) or `bytesBase64` (for control bytes / ESC). Hard cap 65536 bytes per call. Not supported for headless agent shells (no UI tab) — returns an error. Returns { sessionId, bytesFed }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID with an attached terminal tab.") })
                    put("text", JSONObject().apply { put("type", "string"); put("description", "UTF-8 text to inject as output. Mutually exclusive with bytesBase64.") })
                    put("bytesBase64", JSONObject().apply { put("type", "string"); put("description", "Base64-encoded raw bytes to inject — use this for escape sequences (ESC = \\u001b) and other control bytes. Mutually exclusive with text.") })
                })
                put("required", JSONArray().put("sessionId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val n = if (args.has("text")) {
                    args.optString("text").toByteArray(Charsets.UTF_8).size
                } else {
                    runCatching { android.util.Base64.decode(args.optString("bytesBase64"), android.util.Base64.DEFAULT).size }
                        .getOrDefault(0)
                }
                "Inject $n bytes into the terminal OUTPUT stream of session ${args.optString("sessionId").take(8)}…? (renders as if sent by the remote — can set the clipboard via OSC 52)"
            },
        ) { args -> feedTerminalOutput(args) },

        "write_clipboard" to ToolHandler(
            description = "Set the system clipboard's primary plain-text content. Replaces whatever's currently on the clipboard. Useful for priming the clipboard before triggering a terminal paste.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", "Text to place on the clipboard. Empty string is allowed and clears the clipboard's primary item.")
                    })
                })
                put("required", JSONArray().put("text"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val text = args.optString("text", "")
                val preview = if (text.length > 80) text.substring(0, 80) + "…" else text
                val visible = preview.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                "Replace clipboard with: \"$visible\"?"
            },
        ) { args -> writeClipboard(args) },

        "set_preference" to ToolHandler(
            description = "Write a Haven user preference. Whitelisted keys (and their types): terminal_scrollback_rows (int 100..25000), terminal_tap_to_position_cursor (bool), terminal_font_size (int 8..32), mouse_input_enabled (bool), mouse_drag_selects (bool), terminal_right_click (bool), mcp_tunnel_endpoint_profile_id (string SSH profile id, empty to clear), mcp_wireguard_enabled (bool). Returns { key, value }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("key", JSONObject().apply { put("type", "string"); put("description", "Preference key (see whitelist).") })
                    put("value", JSONObject().apply { put("description", "New value. Type must match the key's type — int for the *_rows / *_size keys, bool for the rest.") })
                })
                put("required", JSONArray().put("key").put("value"))
            },
            // ONCE_PER_SESSION: settings writes are reversible and the
            // whitelist is small + non-sensitive (terminal display
            // tweaks). One approval per session avoids prompt fatigue
            // when an agent is sweeping multiple settings during a test
            // run; the user retains the destructive-action safety on
            // tools that touch session state or the clipboard.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Set Haven preference ${args.optString("key")} = ${args.opt("value")}?" },
        ) { args -> setPreference(args) },

        "start_selection" to ToolHandler(
            description = "Anchor a new text selection at (row, col) in an active terminal session. Equivalent to a long-press at that cell. Modes: CHARACTER (default), WORD (snaps to word boundaries on creation), LINE (whole-row selection). Subsequent extend_selection / copy_selection / clear_selection calls on the same session operate on this anchor. Replaces any existing selection on this session.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID with an attached terminal tab.") })
                    put("row", JSONObject().apply { put("type", "integer"); put("description", "Viewport-relative row, 0 = top.") })
                    put("col", JSONObject().apply { put("type", "integer"); put("description", "Column, 0 = leftmost.") })
                    put("mode", JSONObject().apply { put("type", "string"); put("description", "CHARACTER | WORD | LINE. Default CHARACTER.") })
                })
                put("required", JSONArray().put("sessionId").put("row").put("col"))
            },
            // ONCE_PER_SESSION: only mutates an in-memory UI selection,
            // doesn't write to the clipboard or send anything to the
            // remote. The destructive step (copy_selection) keeps
            // EVERY_CALL because it writes to the system clipboard.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Start ${args.optString("mode", "CHARACTER")} selection at row=${args.optInt("row")} col=${args.optInt("col")}?" },
        ) { args -> startSelection(args) },

        "extend_selection" to ToolHandler(
            description = "Move the selection's end anchor to (row, col) in an active terminal session. Equivalent to dragging the selection handle to that cell. Pairs with start_selection — call start_selection first to set the anchor, then extend_selection to move the far end. Does NOT scroll the viewport — use drag_selection_to to extend a selection past the top or bottom of the viewport into scrollback.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID.") })
                    put("row", JSONObject().apply { put("type", "integer"); put("description", "Viewport-relative row, 0 = top.") })
                    put("col", JSONObject().apply { put("type", "integer"); put("description", "Column, 0 = leftmost.") })
                })
                put("required", JSONArray().put("sessionId").put("row").put("col"))
            },
            // ONCE_PER_SESSION for the same reason as start_selection —
            // pure UI-state move, the clipboard-touching follow-up
            // (copy_selection) gates separately.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Extend selection to row=${args.optInt("row")} col=${args.optInt("col")}?" },
        ) { args -> extendSelection(args) },

        "drag_selection_to" to ToolHandler(
            description = "Drag the selection's end anchor toward (toRow, toCol), auto-scrolling the viewport into scrollback (or back toward live) when toRow lies outside [0, rows-1]. Mirrors the Compose drag gesture that runs when the finger crosses the top or bottom edge zone: each row of out-of-viewport target is one ScrollController.scrollBy(±1) paired with one shiftSelectionStartByRows(±1) in lockstep. toRow < 0 scrolls back |toRow| rows (end anchor lands at viewport row 0). toRow >= rows scrolls forward toRow-(rows-1) rows (end anchor lands at viewport row rows-1). toRow in [0, rows-1] is equivalent to extend_selection. Clamps at the live screen and at scrollback.size; the response's `clamped` field indicates whether the full requested distance was achieved. Returns the resolved selection range, the new scrollbackPosition, the number of scroll steps actually taken (signed), the clamp flag, and the selection text via SelectionController.getSelectedText (libvterm softWrapped-aware, scrollback-aware).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID with an attached terminal tab.") })
                    put("toRow", JSONObject().apply { put("type", "integer"); put("description", "Target row. May be negative (above viewport top — drag into scrollback) or >= rows (below viewport bottom — drag toward live).") })
                    put("toCol", JSONObject().apply { put("type", "integer"); put("description", "Target column.") })
                })
                put("required", JSONArray().put("sessionId").put("toRow").put("toCol"))
            },
            // Same rationale as start_selection / extend_selection — pure
            // UI-state moves, the clipboard-touching follow-up gates
            // separately on copy_selection.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Drag selection toward row=${args.optInt("toRow")} col=${args.optInt("toCol")} (scrolls viewport into scrollback as needed)?" },
        ) { args -> dragSelectionTo(args) },

        "copy_selection" to ToolHandler(
            description = "Copy the current selection to the system clipboard and return the copied text. Goes through Haven's smart-copy interceptor (TUI border-strip + soft-wrap rejoin). Clears the selection after copying. Returns { text }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID with a current selection.") })
                })
                put("required", JSONArray().put("sessionId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { _ -> "Copy current terminal selection to clipboard?" },
        ) { args -> copySelection(args) },

        "tap_terminal" to ToolHandler(
            description = "Simulate a tap inside an active terminal session at (row, col). When the user has Settings → Terminal → Tap to move cursor on supported prompts enabled, and the tap lands on a row carrying an OSC 133 COMMAND_INPUT segment with no matching COMMAND_FINISHED, Haven dispatches arrow keys so the readline cursor lands at the tapped column. Returns { handled, deltaCols, dispatched } describing what happened. handled=false means no OSC 133 prompt at the tap row — falls through silently.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID.") })
                    put("row", JSONObject().apply { put("type", "integer"); put("description", "Viewport-relative row.") })
                    put("col", JSONObject().apply { put("type", "integer"); put("description", "Column.") })
                })
                put("required", JSONArray().put("sessionId").put("row").put("col"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Tap terminal at row=${args.optInt("row")} col=${args.optInt("col")}?" },
        ) { args -> tapTerminal(args) },

        "scroll_terminal" to ToolHandler(
            description = "Scroll an active terminal session's viewport by N lines. Positive lines = back into scrollback (older content); negative lines = toward the live screen. Clamps at 0 (live) and scrollback.size. Returns { scrollbackPosition } — the new position after clamping.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID with an attached terminal tab.") })
                    put("lines", JSONObject().apply { put("type", "integer"); put("description", "Lines to scroll. Positive = into scrollback, negative = toward live.") })
                })
                put("required", JSONArray().put("sessionId").put("lines"))
            },
            // ONCE_PER_SESSION: pure view-state change — no PTY input,
            // no clipboard write, fully reversible by the user.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val n = args.optInt("lines")
                if (n >= 0) "Scroll terminal $n lines into scrollback?"
                else "Scroll terminal ${-n} lines toward live screen?"
            },
        ) { args -> scrollTerminal(args) },

        "drag_terminal" to ToolHandler(
            description = "Drive a synthetic touch-drag through the terminal's REAL pointer pipeline — the same awaitEachGesture handler a physical finger feeds. Unlike start_selection / extend_selection / drag_selection_to, which mutate the selection model directly, this exercises the actual gesture code: long-press classification, drag-extend, edge-zone detection, and the held-still auto-repeat edge-scroll ticker. The gesture is: touch-down at path[0]; hold still for pressMs (long enough for the long-press selection timeout, ~500ms, to fire); move through path[1..] one stepMs apart; hold still at path.last() for holdMs (the window the edge-scroll ticker runs in); lift. Rows are viewport-relative; out-of-viewport rows (e.g. -3, or rows beyond the bottom) still map to valid pixels so a path can target the top/bottom edge zone. Blocks until the gesture completes (~pressMs + path*stepMs + holdMs). Requires the session's terminal tab to be the foreground tab (the gesture injector mounts with the Composable). Returns { sessionId, cells, approxDurationMs }. Verify the effect with get_selection + read_terminal_snapshot afterwards.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID; its terminal tab must be the foreground tab.") })
                    put("path", JSONObject().apply {
                        put("type", "array")
                        put("description", "Ordered cells to traverse. Each element is { row, col } (viewport-relative; row may be negative or >= rows to reach the edge zones). path[0] = touch-down, last = lift. Must be non-empty; a single cell is a long-press with no drag.")
                        put("items", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("row", JSONObject().apply { put("type", "integer") })
                                put("col", JSONObject().apply { put("type", "integer") })
                            })
                            put("required", JSONArray().put("row").put("col"))
                        })
                    })
                    put("pressMs", JSONObject().apply { put("type", "integer"); put("description", "Initial still-hold before the first move. Default 900. Must clear the long-press timeout (~500ms).") })
                    put("stepMs", JSONObject().apply { put("type", "integer"); put("description", "Delay between successive move events. Default 30.") })
                    put("holdMs", JSONObject().apply { put("type", "integer"); put("description", "Still-hold at the final cell before lifting — the edge-scroll ticker's window. Default 1000.") })
                })
                put("required", JSONArray().put("sessionId").put("path"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val n = args.optJSONArray("path")?.length() ?: 0
                "Inject a synthetic $n-cell touch-drag into the terminal of session ${args.optString("sessionId").take(8)}…? (drives the real gesture handler)"
            },
        ) { args -> dragTerminal(args) },

        "open_developer_settings" to ToolHandler(
            description = "Open Android's Developer Options screen via ACTION_APPLICATION_DEVELOPMENT_SETTINGS so the user can flip Wireless debugging or other developer toggles. Tap-equivalent — the screen opens but no setting is changed without the user touching it.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> openDeveloperSettings() },

        "install_apk_from_url" to ToolHandler(
            description = "Download an APK from a URL and install it on the device. With Shizuku running and granted, install is silent via `pm install`. Without Shizuku, falls back to firing the system installer dialog (single user tap to confirm) — response includes `pending: true` so the caller knows to wait for the user. Useful for agent-driven self-update or sideloading over VPN where wireless ADB isn't reachable.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "http(s) URL pointing at a signed APK file. Should resolve to APK bytes (no HTML wrapper).")
                    })
                })
                put("required", JSONArray().put("url"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "INSTALL APK from ${args.optString("url").take(60)} — runs as code on this device. Only allow trusted sources."
            },
        ) { args -> installApkFromUrl(args) },

        "install_apk_from_backend" to ToolHandler(
            description = "Install an APK from a path on any connected backend (local, SSH/SFTP, SMB, rclone, Reticulum). Streams APK bytes via the existing FileBackend abstraction. Same Shizuku/system-installer fallback as install_apk_from_url. Gated by Settings → Agent endpoint → \"Allow agents to read file contents\" and confirmed per-call.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Connection profile ID, or 'local' for the device filesystem.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path to the APK file on the chosen backend.")
                    })
                })
                put("required", JSONArray().put("profileId").put("path"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "INSTALL APK from ${args.optString("path").take(60)} on profile ${args.optString("profileId")} — runs as code on this device. Only allow trusted sources."
            },
        ) { args -> installApkFromBackend(args) },

        "enable_wireless_adb" to ToolHandler(
            description = "Turn on Android's Wireless debugging (`adb connect` over WiFi) by setting `adb_wifi_enabled=1` via Shizuku. Requires Shizuku to be running and Haven to have its permission granted. NOTE: on Android 11+, a host that has never paired with this device must still complete the pairing-code flow manually — this tool cannot bypass that. For an already-paired host (the common case after a phone reboot) flipping the flag is enough.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { _ -> "Enable Wireless debugging on this device?" },
        ) { _ -> enableWirelessAdb() },

        "open_local_shell" to ToolHandler(
            description = "Open a fresh local Alpine PRoot shell session and return its sessionId. Equivalent to tapping the Terminal icon in the Connections top bar — creates the local shell profile if missing, registers a session, and connects it. The returned sessionId is immediately usable with send_terminal_input and read_terminal_scrollback. Use this when you need a clean bash REPL (e.g. when an existing session has Claude Code, vim, or another stdin-capturing process in front of it). Pass `plain: true` to bypass the user's session-manager preference (tmux / zellij / screen / byobu) and exec a bare login shell — required when the agent needs Haven's own scrollback ring to capture output, which doesn't happen when a multiplexer's status bar uses DECSTBM to reserve the bottom row. NOTE: if a live local shell already exists, this returns that sessionId regardless of `plain` (response `reused: true`); call disconnect_profile on the existing profile first if you need a fresh plain-shell respawn.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("plain", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Skip the user's sessionManager preference and exec /bin/busybox sh -l directly. Default false (UI-equivalent behaviour).")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> openLocalShell(args) },

        // --- proot distro / desktop environment tools (issue #162 Phase 3b) ---
        // These let an agent (and the Settings → PRoot install log page)
        // drive and observe the proot install pipeline without ADB or
        // logcat. Failures land in the Room-backed log so they survive
        // app restarts and rotated logcat buffers.

        "inspect_proot" to ToolHandler(
            description = "Single rich read of the proot subsystem: active distro id, every Distro (id, label, family, installed, sizeMb, bytesOnDisk, postExtractHookIds, and installedDesktops — the desktop ids installed on THAT distro's rootfs, so the cross-distro picture is visible without switching active distro), every DesktopEnvironment with per-family Stable/Experimental/Broken compatibility and Experimental notes, current osSetupState (phase / step / progress / errorPhase / errorMessage / errorTail), current desktopSetupState (phase / errorMessage / errorTail), and the last 50 install-log events. The single endpoint to drive issue #162 verification.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("eventLimit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Max install-log events to return. Default 50.")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> inspectProot(args) },

        "list_distros" to ToolHandler(
            description = "Slim distro-only read of inspect_proot. Returns each Distro with installed/active/sizeMb/family. Use this when you only need the catalog and not the live state or log events.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listDistros() },

        "list_desktop_environments" to ToolHandler(
            description = "Slim DE-only read of inspect_proot. Filters to DEs that have a package list for the active distro's family (matches the UI filter). Each entry includes per-family compatibility (Stable/Experimental/Broken), an Experimental note when relevant, installed?, and running? state.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listDesktopEnvironments() },

        "install_distro" to ToolHandler(
            description = "Set the given distro as active and trigger installRootfs(). Returns immediately; poll `inspect_proot.osSetupState` for progress (Downloading → Extracting → BootstrapHook → Baseline → Ready, or Error with attribution). Idempotent: if the distro is already installed, just switches active.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("distroId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Distro id from DistroCatalog (e.g. \"alpine-3.21\", \"debian-bookworm\", \"archlinux\", \"void-musl\").")
                    })
                })
                put("required", JSONArray().put("distroId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val id = args.optString("distroId")
                val d = sh.haven.core.local.proot.DistroCatalog.lookup(id)
                if (d != null) "Install Linux distribution \"${d.label}\" (≈${d.sizeEstimateMb} MB download)?"
                else "Install distro \"$id\"?"
            },
        ) { args -> installDistro(args) },

        "delete_distro" to ToolHandler(
            description = "Wipe a distro's rootfs and remove all installed DEs on it. Stops any running DEs first. Destructive — frees the disk space and is also the recovery path when an install lands in a broken state.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("distroId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Distro id to delete.")
                    })
                })
                put("required", JSONArray().put("distroId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val id = args.optString("distroId")
                val d = sh.haven.core.local.proot.DistroCatalog.lookup(id)
                if (d != null) "Delete \"${d.label}\" and all its data? This cannot be undone."
                else "Delete distro \"$id\" and all its data? This cannot be undone."
            },
        ) { args -> deleteDistroTool(args) },

        "install_desktop" to ToolHandler(
            description = "Install a desktop environment on the active distro. Calls ProotManager.setupDesktop which downloads packages, configures VNC, and writes the launcher. Poll `inspect_proot.desktopSetupState` for progress. Failures are attributed to a DePhase (Packages / VncConfig / Marker) in both the state and the install log.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id (e.g. \"xfce4\", \"openbox\", \"labwc-native\").")
                    })
                    put("vncPassword", JSONObject().apply {
                        put("type", "string")
                        put("description", "VNC password. Defaults to empty (SecurityTypes None).")
                    })
                })
                put("required", JSONArray().put("deId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val deId = args.optString("deId")
                val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries.firstOrNull { it.spec.id == deId }
                val active = localSessionManager.prootManager.activeDistro
                val family = active.family
                val compat = de?.spec?.compatibilityOn(family)
                val suffix = when (compat) {
                    sh.haven.core.local.proot.Compatibility.Experimental -> " — experimental on ${family.name}"
                    sh.haven.core.local.proot.Compatibility.Broken -> " — known broken on ${family.name}"
                    else -> ""
                }
                val label = de?.label ?: deId
                "Install ${label} on ${active.label}${suffix}?"
            },
        ) { args -> installDesktopTool(args) },

        "uninstall_desktop" to ToolHandler(
            description = "Remove a desktop environment from the active distro. Stops it first if running. Calls ProotManager.uninstallDesktop.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id to uninstall.")
                    })
                })
                put("required", JSONArray().put("deId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val deId = args.optString("deId")
                val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries.firstOrNull { it.spec.id == deId }
                val active = localSessionManager.prootManager.activeDistro
                "Uninstall ${de?.label ?: deId} from ${active.label}?"
            },
        ) { args -> uninstallDesktopTool(args) },

        "start_desktop" to ToolHandler(
            description = "Start an installed desktop environment on the active distro. Calls DesktopManager.startDesktop; the launch is asynchronous. Returns the allocated display + vncPort so callers can connect a VNC client. Poll `inspect_proot.desktopEnvironments[].running` (or list_desktop_environments) to confirm RUNNING state. NestedWayland DEs (Sway, Hyprland, niri) bring up a wlroots/smithay compositor on the headless backend inside the rootfs and expose it via wayvnc on the returned port; X11Vnc DEs spawn Xvnc + the desktop; NativeCompositor runs the JNI labwc bridge.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id to start.")
                    })
                })
                put("required", JSONArray().put("deId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val deId = args.optString("deId")
                val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries.firstOrNull { it.spec.id == deId }
                val active = localSessionManager.prootManager.activeDistro
                "Start ${de?.label ?: deId} on ${active.label}?"
            },
        ) { args -> startDesktopTool(args) },

        "stop_desktop" to ToolHandler(
            description = "Stop a running desktop environment. Tears down the compositor / Xvnc process tree and releases the display number.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id to stop.")
                    })
                })
                put("required", JSONArray().put("deId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val deId = args.optString("deId")
                val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries.firstOrNull { it.spec.id == deId }
                "Stop ${de?.label ?: deId}?"
            },
        ) { args -> stopDesktopTool(args) },

        "read_desktop_log" to ToolHandler(
            description = "Read a running (or just-failed) desktop's RUNTIME logs — distinct from inspect_proot, which only covers install state. For nested-Wayland DEs (Sway / Hyprland / niri) returns the compositor's own stdout/stderr (compositor.log: the wlr/[ERROR] lines, output-enable, buffer-allocation failures) plus Haven's captured launch-process output (the `[haven]` progress markers + wayvnc lines). This is the diagnostic for grey-screen / no-frames / compositor-refuses-to-start issues — the data that otherwise requires opening a proot shell. Pass deId to target one DE; omit for all running desktops.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id (e.g. \"sway\"). Omit for all running desktops.")
                    })
                    put("maxChars", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Cap compositor.log to its last N chars. Default 4000.")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> readDesktopLog(args) },

        "list_desktop_windows" to ToolHandler(
            description = "Enumerate the visible top-level windows on a running X11/VNC desktop (deId), so an agent can target a specific application window (e.g. KiCad's schematic editor vs. PCB editor) before capturing it. Returns { deId, count, windows:[{id,title,x,y,width,height}] }. X11/VNC desktops only (not nested-Wayland). Installs the capture toolset (xdotool + ImageMagick) on first use.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id (e.g. \"xfce4\") of a RUNNING X11/VNC desktop.")
                    })
                })
                put("required", JSONArray().put("deId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                "Let the agent list the windows open on desktop '${args.optString("deId")}'"
            },
        ) { args -> listDesktopWindows(args) },

        "capture_desktop" to ToolHandler(
            description = "Capture a screenshot of a running X11/VNC desktop (deId) and return it INLINE as an image the agent can see directly — no second port or file download. Whole screen by default, or a single window when windowId (from list_desktop_windows) is given. The image is downscaled to maxWidth and JPEG-encoded by default to stay cheap over the MCP tunnel. Captures inside the guest, so it works even when the user isn't on the VNC tab. Returns the image plus { deId, width, height, format, source, windowId?, windowTitle? }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id (e.g. \"xfce4\") of a RUNNING X11/VNC desktop.")
                    })
                    put("windowId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional X11 window id from list_desktop_windows. Captures just that window (cropped to its geometry). Omit for the whole screen.")
                    })
                    put("maxWidth", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Downscale so the image is at most this many pixels wide. Default 1024 (clamped 160–4096).")
                    })
                    put("format", JSONObject().apply {
                        put("type", "string")
                        put("description", "\"jpeg\" (default, smaller) or \"png\" (lossless, larger).")
                    })
                })
                put("required", JSONArray().put("deId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val win = args.optString("windowId").takeIf { it.isNotBlank() }
                val what = if (win != null) "a window on" else "the screen of"
                "Let the agent see $what desktop '${args.optString("deId")}'"
            },
        ) { args -> captureDesktop(args) },

        "get_proot_install_log" to ToolHandler(
            description = "Return install-log events from the Room-backed ProotInstallLog table. Survives logcat rotation and app restarts. Filter by distroId and/or sinceMs (millis since epoch) to poll incrementally. Each event: id, timestamp, distroId, phase, deId?, exit?, ok, message?, logTail?.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("distroId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Filter to a single distro. Omit for all distros.")
                    })
                    put("sinceMs", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Return only events with timestamp > sinceMs. Default 0 (return all).")
                    })
                    put("limit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Max events. Default 100.")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> getProotInstallLog(args) },

        "run_in_proot" to ToolHandler(
            description = "Run a shell command inside the ACTIVE distro's proot guest (the same rootfs the running desktop uses) and return its combined stdout+stderr. Distro-agnostic: invokes /bin/sh -lc in whatever distro is active (check inspect_proot.activeDistroId), so it works on Debian/Arch/Void, not just Alpine like open_local_shell. For long jobs (apt-get install, pip install) pass background:true to start it and return a jobId immediately, then poll by calling again with that jobId — the response carries the accumulated output and, once finished, the exitCode. Without background, the call blocks until the command exits (use only for quick commands). This is the agent's headless way to provision the guest (install packages, run kicad-cli ERC/DRC, etc.).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "Shell command to run via /bin/sh -lc in the active proot. Required unless polling via jobId.")
                    })
                    put("background", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "If true, start the command and return a jobId immediately instead of blocking. Poll with that jobId. Default false.")
                    })
                    put("jobId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Poll a previously started background job. When set, `command` is ignored and the current status + accumulated output are returned.")
                    })
                    put("maxOutputChars", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Cap the returned output to its last N chars. Default 8000.")
                    })
                })
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val jid = args.optString("jobId").takeIf { it.isNotBlank() }
                if (jid != null) "Let the agent read output of guest job $jid"
                else "Let the agent run in the guest: ${args.optString("command").take(120)}"
            },
        ) { args -> runInProot(args) },

        "set_terminal_font_from_url" to ToolHandler(
            description = "Download a font from a URL, validate it, install it as Haven's terminal font (replacing any prior custom font), and return the saved path. The URL may point at a .ttf/.otf, or a .zip containing them (a Regular face is auto-extracted) — useful for repos like Maple/Nerd Fonts that ship only zips (#123, #177). WOFF/WOFF2 web fonts are rejected (Android can't render them). Requires the URL to be reachable from the device — use a tunneled URL (via add_port_forward LOCAL) to expose a workstation HTTP server back through the existing SSH session.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "http(s) URL resolving to a .ttf/.otf, or a .zip of them (no HTML wrapper). WOFF/WOFF2 are not supported.")
                    })
                })
                put("required", JSONArray().put("url"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Download terminal font from ${args.optString("url").take(80)}?" },
        ) { args -> setTerminalFontFromUrl(args) },

        "convert_file" to ToolHandler(
            description = "Run ffmpeg to transcode a source URL (typically the playerUrl/playlistUrl from stream_sftp_file, or any URL ffmpeg can read) into a new file in Haven's app cache. Returns the cache path on success — use upload_file_to_sftp to put the result on a remote.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sourceUrl", JSONObject().apply { put("type", "string"); put("description", "URL ffmpeg should read from. http(s)://, file://, or any protocol ffmpeg supports.") })
                    put("container", JSONObject().apply { put("type", "string"); put("description", "Output container, e.g. 'mp4', 'mkv', 'webm'. Determines the file extension.") })
                    put("videoEncoder", JSONObject().apply { put("type", "string"); put("description", "Video codec, e.g. 'libx264', 'libx265', 'copy'. Default: copy.") })
                    put("audioEncoder", JSONObject().apply { put("type", "string"); put("description", "Audio codec, e.g. 'aac', 'libopus', 'copy'. Default: copy.") })
                    put("outputName", JSONObject().apply { put("type", "string"); put("description", "Optional output filename (without extension). Default: 'agent-convert-<timestamp>'.") })
                })
                put("required", JSONArray().put("sourceUrl").put("container"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val container = args.optString("container", "mp4")
                val ve = args.optString("videoEncoder", "copy")
                val ae = args.optString("audioEncoder", "copy")
                "Transcode source → $container (video: $ve, audio: $ae)?"
            },
        ) { args -> convertFile(args) },

        "list_workspaces" to ToolHandler(
            description = "List the user's saved workspace profiles — named bundles of terminal sessions, file-browser tabs, remote desktops, and Wayland that compose_workspace can reopen in one shot. Each entry has id, name, and itemCount. The kinds of items inside come from the same Kind enum the UI uses (TERMINAL / FILE_BROWSER / DESKTOP / WAYLAND).",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listWorkspaces() },

        "compose_workspace" to ToolHandler(
            description = "Launch every item of a saved workspace through the same WorkspaceLauncher the user's tap goes through — terminal sessions, file-browser tabs, remote desktops, and the Wayland tab open in dependency-friendly order (TERMINAL first so tunneled DESKTOPs attach to live SSH sessions). Returns the workspace id and item count; progress is surfaced live in the Connections screen banner.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("workspaceId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Workspace id from list_workspaces.")
                    })
                })
                put("required", JSONArray().put("workspaceId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val id = args.optString("workspaceId")
                val name = workspaceLabel(id)
                "Open workspace \"$name\"?"
            },
        ) { args -> composeWorkspace(args) },

        "list_ssh_keys" to ToolHandler(
            description = "List saved SSH keys available for SSH / Mosh / SFTP profiles. Returns id, label, keyType (e.g. ed25519, rsa), publicKeyOpenSsh, fingerprintSha256, isEncrypted (passphrase-protected), biometricProtected, and createdAt. Private key bytes are NEVER returned — they stay encrypted at rest.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listSshKeys() },

        "import_ssh_key" to ToolHandler(
            description = "Import an OpenSSH / PEM / PKCS#8 / PuTTY PPK private key into the Haven key store. Pass `privateKey` (the text body, e.g. starting with `-----BEGIN OPENSSH PRIVATE KEY-----`), `label` (user-facing name), and optional `passphrase` (only if the key is encrypted). Returns the new key id, keyType, publicKeyOpenSsh (suitable for an `authorized_keys` line), and fingerprintSha256.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("privateKey", JSONObject().apply {
                        put("type", "string")
                        put("description", "Private key body in OpenSSH / PEM / PKCS#8 / PuTTY format. Pass the file's text contents verbatim, including BEGIN/END lines.")
                    })
                    put("label", JSONObject().apply {
                        put("type", "string")
                        put("description", "User-facing label shown on the Keys screen and in profile pickers.")
                    })
                    put("passphrase", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional. Only required if the private key is encrypted at rest. Stored only briefly to decrypt the key for parsing; the saved entity keeps the original (still-encrypted) bytes.")
                    })
                })
                put("required", JSONArray().put("privateKey").put("label"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val label = args.optString("label", "(unnamed)")
                "Import SSH key \"$label\" into the Haven key store?"
            },
        ) { args -> importSshKey(args) },

        "delete_ssh_key" to ToolHandler(
            description = "Delete a saved SSH key by id. Profiles that referenced it via sshKeyId will fall through to password auth (or fail) on next connect — no cascade rewrite. Irreversible: the encrypted private key bytes are removed.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sshKeyId", JSONObject().apply {
                        put("type", "string")
                        put("description", "SSH key id from list_ssh_keys.")
                    })
                })
                put("required", JSONArray().put("sshKeyId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val id = args.optString("sshKeyId")
                val label = runBlocking { sshKeyRepository.getById(id)?.label } ?: id.take(8) + "…"
                "Delete SSH key \"$label\"? Cannot be undone."
            },
        ) { args -> deleteSshKey(args) },

        "list_totp_secrets" to ToolHandler(
            description = "List saved OATH-TOTP authenticator secrets (#178). Returns id, label, issuer, accountName, algorithm, digits, periodSeconds, and createdAt. The base32 secret itself is NEVER returned — it stays encrypted at rest. Reference an id as a `TOTP:<id>` token in create_connection's authMethods to auto-fill the SSH 'Verification code:' prompt.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listTotpSecrets() },

        "create_totp_secret" to ToolHandler(
            description = "Store an OATH-TOTP secret so it can auto-fill an SSH keyboard-interactive OTP prompt (#178). Pass `otpauth` (an `otpauth://totp/...` URI) OR `secret` (a raw base32 string) plus an optional `label`. Returns the new secret id; reference it via a `TOTP:<id>` token in create_connection's authMethods.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("otpauth", JSONObject().apply {
                        put("type", "string")
                        put("description", "An otpauth://totp/Issuer:account?secret=...&... URI. Mutually exclusive with `secret`.")
                    })
                    put("secret", JSONObject().apply {
                        put("type", "string")
                        put("description", "A raw base32 TOTP secret (SHA1, 6 digits, 30s period assumed). Use `otpauth` instead when you have the full URI.")
                    })
                    put("label", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional user-facing label. Defaults to the otpauth issuer/account or 'Authenticator'.")
                    })
                })
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val l = args.optString("label").ifBlank { "(from otpauth)" }
                "Store a TOTP authenticator secret \"$l\" in the Haven key store?"
            },
        ) { args -> createTotpSecret(args) },

        "delete_totp_secret" to ToolHandler(
            description = "Delete a saved TOTP secret by id. Profiles referencing it via a TOTP auth element fall through to a manual OTP prompt on next connect. Irreversible.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("totpSecretId", JSONObject().apply {
                        put("type", "string")
                        put("description", "TOTP secret id from list_totp_secrets.")
                    })
                })
                put("required", JSONArray().put("totpSecretId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val id = args.optString("totpSecretId")
                val label = runBlocking { totpSecretRepository.getById(id)?.label } ?: id.take(8) + "…"
                "Delete TOTP secret \"$label\"? Cannot be undone."
            },
        ) { args -> deleteTotpSecret(args) },

        "list_tunnels" to ToolHandler(
            description = "List saved WireGuard / Tailscale tunnel configs available for Route-through on connection profiles. Returns id, label, type (WIREGUARD or TAILSCALE), and createdAt for each. The encrypted configText (wg-quick payload or Tailscale authkey blob) is NOT returned.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listTunnels() },

        "list_live_tunnels" to ToolHandler(
            description = "Return the live-tunnel snapshot from TunnelManager — every tunnel currently up, paired with the set of profile ids holding it. Useful for verifying refcount semantics in #149 integration tests: confirm the tunnel stays open while a sibling transport keeps it acquired, and that it tears down on the last release.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listLiveTunnels() },

        "create_tunnel" to ToolHandler(
            description = "Add a new WireGuard, Tailscale, or Cloudflare Tunnel config. WIREGUARD: pass `configText` (wg-quick INI body). TAILSCALE: pass `tailscaleAuthKey` (and optional `tailscaleControlUrl` for Headscale). CLOUDFLARE_ACCESS: pass `accessHostname`; for Access-protected routes also pass `accessJwt` (from `cloudflared access token --app https://<host>`); optional `accessJumpDestination` for bastion-mode multi-target tunnels. Returns the new tunnel id, which can then be passed to set_profile_routing.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("label", JSONObject().apply {
                        put("type", "string")
                        put("description", "User-facing label (also used to derive the Tailscale hostname).")
                    })
                    put("type", JSONObject().apply {
                        put("type", "string")
                        put("description", "WIREGUARD, TAILSCALE, or CLOUDFLARE_ACCESS.")
                    })
                    put("configText", JSONObject().apply {
                        put("type", "string")
                        put("description", "WireGuard wg-quick INI body. Required when type=WIREGUARD.")
                    })
                    put("tailscaleAuthKey", JSONObject().apply {
                        put("type", "string")
                        put("description", "Tailscale single-use authkey (tskey-auth-...). Required when type=TAILSCALE.")
                    })
                    put("tailscaleControlUrl", JSONObject().apply {
                        put("type", "string")
                        put("description", "Self-hosted Headscale coordination URL. Optional — empty defaults to controlplane.tailscale.com.")
                    })
                    put("accessHostname", JSONObject().apply {
                        put("type", "string")
                        put("description", "Cloudflare Tunnel published hostname (e.g. ssh.example.com). Required when type=CLOUDFLARE_ACCESS.")
                    })
                    put("accessJwt", JSONObject().apply {
                        put("type", "string")
                        put("description", "Cloudflare Access JWT (`CF_Authorization` value). Optional — only needed when the Tunnel route is Access-protected.")
                    })
                    put("accessTeamDomain", JSONObject().apply {
                        put("type", "string")
                        put("description", "Cloudflare Access team domain (myteam.cloudflareaccess.com). Optional; only meaningful for Access-protected routes.")
                    })
                    put("accessExpiresAt", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Optional explicit JWT expiry (Unix epoch seconds). Defaults to parsing the `exp` claim out of accessJwt.")
                    })
                    put("accessJumpDestination", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional `Cf-Access-Jump-Destination` value for bastion-mode multi-target tunnels (e.g. internal-host:22).")
                    })
                })
                put("required", JSONArray().put("label").put("type"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val type = args.optString("type")
                val label = args.optString("label", "(unnamed)")
                "Add $type tunnel \"$label\" to the keystore?"
            },
        ) { args -> createTunnel(args) },

        "delete_tunnel" to ToolHandler(
            description = "Delete a saved tunnel config by id. Profiles that referenced it via tunnelConfigId will fall through to direct dialling on next connect.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("tunnelConfigId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Tunnel id from list_tunnels.")
                    })
                })
                put("required", JSONArray().put("tunnelConfigId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Delete tunnel ${args.optString("tunnelConfigId").take(8)}…?" },
        ) { args -> deleteTunnel(args) },

        "set_profile_routing" to ToolHandler(
            description = "Set or clear the Route-through configuration on a connection profile. Pass either tunnelConfigId (WireGuard / Tailscale tunnel from list_tunnels) OR proxyType+proxyHost+proxyPort (legacy SOCKS5 / SOCKS4 / HTTP), and the other field set is cleared — mutually exclusive at the data layer too. Pass `clear=true` to drop both and route direct.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Profile id from list_connections.")
                    })
                    put("tunnelConfigId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Tunnel id to route through. Mutually exclusive with proxyType.")
                    })
                    put("proxyType", JSONObject().apply {
                        put("type", "string")
                        put("description", "SOCKS5 | SOCKS4 | HTTP. Pair with proxyHost + proxyPort.")
                    })
                    put("proxyHost", JSONObject().apply {
                        put("type", "string")
                        put("description", "Proxy host. Required when proxyType is set.")
                    })
                    put("proxyPort", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Proxy port. Default 1080 (SOCKS) / 8080 (HTTP).")
                    })
                    put("clear", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "If true, clear both tunnelConfigId and proxyType. Profile routes direct.")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId")
                val target = when {
                    args.optBoolean("clear", false) -> "direct"
                    args.has("tunnelConfigId") -> "tunnel ${args.optString("tunnelConfigId").take(8)}…"
                    args.has("proxyType") -> "${args.optString("proxyType")} ${args.optString("proxyHost")}:${args.optInt("proxyPort")}"
                    else -> "(unchanged)"
                }
                "Route \"${profileLabel(pid)}\" through $target?"
            },
        ) { args -> setProfileRouting(args) },

        "create_connection" to ToolHandler(
            description = "Create a saved connection profile. Supports connectionType=SSH, SMB, VNC, RDP. SSH-family fields: username (required), password (optional, stored), keyId (optional — references list_ssh_keys), useMosh (turn an SSH profile into a Mosh profile). SMB: smbShare (required), username + password, smbDomain. VNC: vncUsername, vncPassword, vncPort. RDP: rdpUsername (required), rdpPassword, rdpDomain, rdpPort. The new profile id is returned for follow-up calls (set_profile_routing, connect_profile). For Reticulum / rclone / local create the profile in the UI — those paths need OAuth / destination-hash flows the agent can't drive.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("label", JSONObject().apply { put("type", "string"); put("description", "User-facing label.") })
                    put("connectionType", JSONObject().apply { put("type", "string"); put("description", "SSH | SMB | VNC | RDP.") })
                    put("host", JSONObject().apply { put("type", "string"); put("description", "Target hostname or IP.") })
                    put("port", JSONObject().apply { put("type", "integer"); put("description", "TCP port. Defaults: SSH 22, SMB 445, VNC 5900, RDP 3389.") })
                    put("username", JSONObject().apply { put("type", "string"); put("description", "Username for SSH/SMB.") })
                    put("password", JSONObject().apply { put("type", "string"); put("description", "Password (stored). Optional for SSH if a key is used; some VNC/SMB setups allow guest.") })
                    put("smbShare", JSONObject().apply { put("type", "string"); put("description", "Share name (SMB). Required when connectionType=SMB.") })
                    put("smbDomain", JSONObject().apply { put("type", "string"); put("description", "AD/workgroup domain (SMB). Optional.") })
                    put("vncUsername", JSONObject().apply { put("type", "string"); put("description", "Username for VeNCrypt VNC.") })
                    put("vncPassword", JSONObject().apply { put("type", "string"); put("description", "VNC password.") })
                    put("rdpUsername", JSONObject().apply { put("type", "string"); put("description", "Windows username (RDP). Required when connectionType=RDP.") })
                    put("rdpPassword", JSONObject().apply { put("type", "string"); put("description", "Windows password (RDP).") })
                    put("rdpDomain", JSONObject().apply { put("type", "string"); put("description", "AD domain (RDP). Optional.") })
                    put("tunnelConfigId", JSONObject().apply { put("type", "string"); put("description", "Optional: route the new profile through this tunnel (from list_tunnels). Equivalent to follow-up set_profile_routing.") })
                    put("tunnelOnly", JSONObject().apply { put("type", "boolean"); put("description", "SSH only: tunnel-only mode (#150). When true, the profile brings up the SSH transport and registers port forwards but does not open a terminal. Default false. Pair with auto_reconnect for autossh-style keepalive.") })
                    put("useMosh", JSONObject().apply { put("type", "boolean"); put("description", "SSH only: when true, the profile uses Mosh on top of the SSH bootstrap. SSH execs `mosh-server new -s`, parses MOSH CONNECT, then the UDP transport takes over. Default false.") })
                    put("keyId", JSONObject().apply { put("type", "string"); put("description", "SSH only: id of a saved SSH key (from list_ssh_keys) to authenticate with. Mutually optional with password.") })
                    put("authMethods", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().apply { put("type", "string") })
                        put("description", "SSH only (#166): ordered multi-factor auth methods attempted in one connect, for servers requiring a chain like publickey,password. Each element is a token: \"PASSWORD\", \"KEY\" (any saved key), \"KEY:<keyId>\", \"KEYBOARD_INTERACTIVE\", or \"TOTP:<id>\" (auto-fill an OATH-TOTP code from list_totp_secrets, #178). Omit for the single-method default derived from keyId/password.")
                    })
                    put("portKnockSequence", JSONObject().apply { put("type", "string"); put("description", "Optional port-knock sequence fired before the real connect. Format: whitespace/comma-separated 'port[/proto]' tokens — e.g. '7000 8000 9000' (all TCP) or '7000/tcp 8000/udp 9000/tcp'. Empty = disabled.") })
                    put("portKnockDelayMs", JSONObject().apply { put("type", "integer"); put("description", "Inter-knock delay in ms (default 100). Ignored when portKnockSequence is empty.") })
                })
                put("required", JSONArray().put("label").put("connectionType").put("host"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val type = args.optString("connectionType")
                val label = args.optString("label", "(unnamed)")
                val host = args.optString("host", "?")
                val tunnelTag = if (args.optBoolean("tunnelOnly", false)) " [tunnel-only]" else ""
                val knockTag = args.optString("portKnockSequence").let {
                    if (it.isNotBlank()) " [knock: $it]" else ""
                }
                "Create $type profile \"$label\" → $host$tunnelTag$knockTag?"
            },
        ) { args -> createConnection(args) },

        "set_port_knock" to ToolHandler(
            description = "Update the port-knock fields on an existing profile. Pass portKnockSequence='' (empty) to disable knocking. Format: 'port[/proto]' tokens — e.g. '7000 8000 9000' or '7000/tcp 8000/udp'. Returns the updated profile summary.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Profile id from list_connections.") })
                    put("portKnockSequence", JSONObject().apply { put("type", "string"); put("description", "Sequence string ('' to disable).") })
                    put("portKnockDelayMs", JSONObject().apply { put("type", "integer"); put("description", "Inter-knock delay in ms. Optional; default 100 when sequence becomes non-empty.") })
                })
                put("required", JSONArray().put("profileId").put("portKnockSequence"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val seq = args.optString("portKnockSequence")
                val target = profileLabel(args.optString("profileId"))
                if (seq.isBlank()) "Disable port knock on \"$target\"?"
                else "Set port knock on \"$target\" to: $seq?"
            },
        ) { args -> setPortKnock(args) },

        "test_port_knock" to ToolHandler(
            description = "Send a port-knock sequence to a host without committing or connecting anything. Bypasses the saved profile state — pass host + sequence directly. Returns ok/sent/durationMs/error so an agent can verify a knockd config end-to-end without opening a real session.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("host", JSONObject().apply { put("type", "string"); put("description", "Hostname or IP literal to knock against.") })
                    put("portKnockSequence", JSONObject().apply { put("type", "string"); put("description", "Sequence string — e.g. '7000 8000 9000' or '7000/tcp 8000/udp'.") })
                    put("portKnockDelayMs", JSONObject().apply { put("type", "integer"); put("description", "Inter-knock delay in ms (default 100).") })
                })
                put("required", JSONArray().put("host").put("portKnockSequence"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                "Send test knock '${args.optString("portKnockSequence")}' to ${args.optString("host")}?"
            },
        ) { args -> testPortKnock(args) },

        "get_connection_log" to ToolHandler(
            description = "Read the most recent ConnectionLog entries for a profile. Use this to verify post-hoc what happened during a connect — including knock results (look for '[knock]' lines in verboseLog), TLS handshakes, and authentication. limit defaults to 10.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Profile id from list_connections.") })
                    put("limit", JSONObject().apply { put("type", "integer"); put("description", "Max entries to return (default 10, max 100).") })
                })
                put("required", JSONArray().put("profileId"))
            },
        ) { args -> getConnectionLog(args) },

        "delete_connection" to ToolHandler(
            description = "Delete a saved connection profile by id. Disconnects any live session for the profile first. Use this after integration tests to clean up agent-created profiles.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Profile id from list_connections.") })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete profile \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> deleteConnection(args) },

        "connect_profile" to ToolHandler(
            description = "Initiate a connection for a saved profile via the same code path a UI tap uses (route-through, stored password, key auth all apply). Posts an AgentUiCommand.ConnectProfile that ConnectionsViewModel observes — the actual connect happens asynchronously. Use list_sessions afterwards to confirm the session reached CONNECTED. If the profile needs a password that isn't stored and isn't a key, the UI password prompt will surface to the user.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Profile id from list_connections.")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Connect to \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> connectProfile(args) },
    )

    /** Return the list of tool definitions for MCP `tools/list`. */
    fun definitions(): List<JSONObject> = tools.map { (name, handler) ->
        JSONObject().apply {
            put("name", name)
            put("description", handler.description)
            put("inputSchema", handler.inputSchema)
        }
    }

    /**
     * Look up the consent gating metadata for a tool. Returns null for an
     * unknown tool (the dispatcher will surface that as an MCP error
     * regardless). Exposed to [McpServer] so consent prompting happens
     * before the handler runs.
     */
    fun consentFor(name: String): ToolConsent? {
        val handler = tools[name] ?: return null
        return ToolConsent(level = handler.consentLevel, summary = handler.summarise)
    }

    /**
     * Per-call context that handlers can read. McpServer sets
     * [currentClientHint] right before invoking [call]; handlers like
     * `queue_terminal_input` consult it to thread the calling MCP
     * client's name through to the AgentConsentManager pre-delivery
     * prompt. Volatile because, while the JSON-RPC dispatcher is
     * sequential per connection, multiple HTTP connections can race —
     * the worst case is a stale hint, which is benign (the prompt
     * just shows the wrong client name).
     */
    @Volatile
    private var currentClientHint: String? = null

    /** Call a tool by name. Throws [McpError] for bad input. */
    suspend fun call(name: String, arguments: JSONObject, clientHint: String? = null): JSONObject {
        val handler = tools[name] ?: throw McpError(-32602, "Unknown tool: $name")
        currentClientHint = clientHint
        return handler.handle(arguments)
    }

    // --- Tool implementations ---

    private fun getAppInfo(): JSONObject = JSONObject().apply {
        put("app", "haven")
        put("version", sh.haven.app.BuildConfig.VERSION_NAME)
        put("versionCode", sh.haven.app.BuildConfig.VERSION_CODE)
        put("buildType", sh.haven.app.BuildConfig.BUILD_TYPE)
        // Loosely-advertised feature flags — what's reachable from which tool
        put("capabilities", JSONArray().apply {
            put("ssh")
            put("sftp")
            put("smb")
            put("rclone")
            put("vnc")
            put("rdp")
            put("reticulum")
            put("mosh")
            put("eternal_terminal")
            put("proot")
            put("wayland")
            put("ffmpeg")
        })
    }

    private suspend fun listConnections(): JSONObject {
        val profiles = connectionRepository.getAll()
        val arr = JSONArray()
        for (p in profiles) arr.put(profileToJson(p))
        return JSONObject().apply {
            put("count", profiles.size)
            put("connections", arr)
        }
    }

    private fun profileToJson(p: ConnectionProfile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("label", p.label)
        put("connectionType", p.connectionType)
        put("host", p.host)
        put("port", p.port)
        put("username", p.username)
        put("groupId", p.groupId ?: JSONObject.NULL)
        put("jumpProfileId", p.jumpProfileId ?: JSONObject.NULL)
        put("authType", p.authType.name)
        put("hasStoredPassword", !p.sshPassword.isNullOrEmpty())
        put("hasKey", p.keyId != null)
        if (p.authMethodSpecs.size > 1) {
            put("authMethods", org.json.JSONArray(p.authMethodSpecs.map { it.serialize() }))
        }
        put("lastConnected", p.lastConnected ?: JSONObject.NULL)
        if (p.useMosh) put("useMosh", true)
        if (p.useEternalTerminal) put("useEternalTerminal", true)
        // VNC-specific fields
        if (p.vncPort != null) put("vncPort", p.vncPort)
        if (!p.vncUsername.isNullOrEmpty()) put("vncUsername", p.vncUsername)
        // RDP
        if (!p.rdpUsername.isNullOrEmpty()) put("rdpUsername", p.rdpUsername)
        if (!p.rdpDomain.isNullOrEmpty()) put("rdpDomain", p.rdpDomain)
        // SMB
        if (!p.smbShare.isNullOrEmpty()) put("smbShare", p.smbShare)
        // Rclone
        if (!p.rcloneRemoteName.isNullOrEmpty()) put("rcloneRemote", p.rcloneRemoteName)
        if (!p.rcloneProvider.isNullOrEmpty()) put("rcloneProvider", p.rcloneProvider)
        // Proxy
        if (!p.proxyType.isNullOrEmpty()) {
            put("proxyType", p.proxyType)
            put("proxyHost", p.proxyHost ?: JSONObject.NULL)
            put("proxyPort", p.proxyPort)
        }
        // WireGuard / Tailscale routing — tunnel config id (resolve via list_tunnels).
        if (!p.tunnelConfigId.isNullOrEmpty()) {
            put("tunnelConfigId", p.tunnelConfigId)
        }
        // #150 — non-default reconnect / tunnel-only flags. Defaults
        // (autoReconnect=true, max=5, networkChange=true, tunnelOnly=false)
        // are omitted to keep the JSON terse for the common case.
        if (!p.autoReconnect) put("autoReconnect", false)
        if (p.reconnectMaxAttempts != 5) put("reconnectMaxAttempts", p.reconnectMaxAttempts)
        if (!p.reconnectOnNetworkChange) put("reconnectOnNetworkChange", false)
        if (p.tunnelOnly) put("tunnelOnly", true)
        // Per-profile session manager (tmux / zellij / screen / null = bare
        // shell). Surfaced so agents picking a clean-bash test target can
        // filter on it without first connecting.
        if (!p.sessionManager.isNullOrEmpty()) put("sessionManager", p.sessionManager)
        if (p.disableAltScreen) put("disableAltScreen", true)
        if (!p.portKnockSequence.isNullOrEmpty()) {
            put("portKnockSequence", p.portKnockSequence)
            put("portKnockDelayMs", p.portKnockDelayMs)
        }
    }

    private fun listSessions(): JSONObject {
        val sessions = sessionManagerRegistry.allSessions
        val sshStates = sshSessionManager.sessions.value
        val arr = JSONArray()
        for (s in sessions) {
            arr.put(JSONObject().apply {
                put("sessionId", s.sessionId)
                put("profileId", s.profileId)
                put("label", s.label)
                put("status", s.status.name)
                put("transport", s.transport.name)
                if (s.transport == sh.haven.core.ssh.Transport.SSH) {
                    val sshState = sshStates[s.sessionId]
                    if (sshState != null) {
                        put("sessionManager", sshState.sessionManager.name)
                        put("chosenSessionName", sshState.chosenSessionName ?: JSONObject.NULL)
                        put("hasShell", sshState.shellChannel != null)
                        put("hasSftp", sshState.sftpChannel != null)
                        put("jumpSessionId", sshState.jumpSessionId ?: JSONObject.NULL)
                        put("activeForwards", JSONArray().apply {
                            for (f in sshState.activeForwards) {
                                put(JSONObject().apply {
                                    put("ruleId", f.ruleId)
                                    put("type", f.type.name)
                                    put("bindAddress", f.bindAddress)
                                    put("bindPort", f.bindPort)
                                    put("actualBoundPort", f.actualBoundPort)
                                    put("targetHost", f.targetHost)
                                    put("targetPort", f.targetPort)
                                })
                            }
                        })
                    }
                }
            })
        }
        return JSONObject().apply {
            put("count", sessions.size)
            put("sessions", arr)
        }
    }

    private fun listDesktopSessions(): JSONObject {
        val statuses = desktopSessionRegistry.statuses.value
        val arr = JSONArray()
        for ((profileId, status) in statuses) {
            arr.put(JSONObject().apply {
                put("profileId", profileId)
                put("status", status.name.lowercase())
            })
        }
        return JSONObject().apply {
            put("count", statuses.size)
            put("desktops", arr)
        }
    }

    /** RcloneClient must be initialised before any RPC calls — idempotent. */
    private fun ensureRcloneReady() {
        rcloneClient.initialize()
    }

    private fun listRcloneRemotes(): JSONObject {
        return try {
            ensureRcloneReady()
            val remotes = rcloneClient.listRemotes()
            JSONObject().apply {
                put("count", remotes.size)
                put("remotes", JSONArray().apply { remotes.forEach { put(it) } })
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list rclone remotes: ${e.message}")
        }
    }

    private suspend fun startRcloneSync(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val srcFs = args.optString("srcFs").ifEmpty {
            throw McpError(-32602, "Missing required argument: srcFs")
        }
        val dstFs = args.optString("dstFs").ifEmpty {
            throw McpError(-32602, "Missing required argument: dstFs")
        }
        val modeStr = args.optString("mode").ifEmpty {
            throw McpError(-32602, "Missing required argument: mode")
        }
        val mode = when (modeStr.lowercase()) {
            "copy" -> sh.haven.core.rclone.SyncMode.COPY
            "sync", "mirror" -> sh.haven.core.rclone.SyncMode.SYNC
            "move" -> sh.haven.core.rclone.SyncMode.MOVE
            else -> throw McpError(-32602, "Unknown mode: $modeStr (expected copy/sync/move)")
        }
        val includes = args.optJSONArray("includePatterns")?.let { arr ->
            List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val excludes = args.optJSONArray("excludePatterns")?.let { arr ->
            List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val filters = sh.haven.core.rclone.SyncFilters(
            includePatterns = includes,
            excludePatterns = excludes,
            minSize = args.optString("minSize").ifBlank { null },
            maxSize = args.optString("maxSize").ifBlank { null },
            bandwidthLimit = args.optString("bandwidthLimit").ifBlank { null },
        )
        val config = sh.haven.core.rclone.SyncConfig(
            srcFs = srcFs,
            dstFs = dstFs,
            mode = mode,
            filters = filters,
            dryRun = args.optBoolean("dryRun", false),
        )
        try {
            ensureRcloneReady()
            rcloneClient.resetStats()
            val jobId = rcloneClient.startSync(config)
            JSONObject().apply {
                put("jobId", jobId)
                put("mode", mode.name.lowercase())
                put("rcMethod", mode.rcMethod)
                put("srcFs", srcFs)
                put("dstFs", dstFs)
                put("dryRun", config.dryRun)
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to start rclone sync: ${e.message}")
        }
    }

    private suspend fun getRcloneSyncStatus(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            ensureRcloneReady()
            val jobId = if (args.has("jobId") && !args.isNull("jobId")) {
                args.optLong("jobId")
            } else {
                rcloneClient.activeSyncJobId ?: return@withContext JSONObject().apply {
                    put("active", false)
                }
            }
            val status = rcloneClient.getJobStatus(jobId)
            val stats = rcloneClient.getStats()
            JSONObject().apply {
                put("active", true)
                put("jobId", jobId)
                put("finished", status.finished)
                put("success", status.success)
                status.error?.let { put("error", it) }
                put("duration", status.duration)
                put("stats", JSONObject().apply {
                    put("bytes", stats.bytes)
                    put("totalBytes", stats.totalBytes)
                    put("speed", stats.speed)
                    put("transfers", stats.transfers)
                    put("totalTransfers", stats.totalTransfers)
                    put("errors", stats.errors)
                    put("deletes", stats.deletes)
                    put("deletedDirs", stats.deletedDirs)
                })
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to get rclone sync status: ${e.message}")
        }
    }

    private suspend fun cancelRcloneSync(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            ensureRcloneReady()
            val jobId = if (args.has("jobId") && !args.isNull("jobId")) {
                args.optLong("jobId")
            } else {
                rcloneClient.activeSyncJobId ?: return@withContext JSONObject().apply {
                    put("cancelled", false)
                    put("reason", "no active job")
                }
            }
            rcloneClient.cancelJob(jobId)
            JSONObject().apply {
                put("cancelled", true)
                put("jobId", jobId)
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to cancel rclone sync: ${e.message}")
        }
    }

    private fun getRcloneStats(): JSONObject {
        return try {
            ensureRcloneReady()
            val s = rcloneClient.getStats()
            JSONObject().apply {
                put("bytes", s.bytes)
                put("totalBytes", s.totalBytes)
                put("speed", s.speed)
                put("transfers", s.transfers)
                put("totalTransfers", s.totalTransfers)
                put("errors", s.errors)
                put("deletes", s.deletes)
                put("deletedDirs", s.deletedDirs)
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to get rclone stats: ${e.message}")
        }
    }

    private fun resetRcloneStats(): JSONObject {
        return try {
            ensureRcloneReady()
            rcloneClient.resetStats()
            JSONObject().apply { put("reset", true) }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to reset rclone stats: ${e.message}")
        }
    }

    private suspend fun listSavedSyncProfiles(): JSONObject = withContext(Dispatchers.IO) {
        val rows = syncProfileRepository.observeAll().first()
        val arr = JSONArray()
        for (p in rows) {
            arr.put(syncProfileToJson(p))
        }
        JSONObject().apply {
            put("count", rows.size)
            put("profiles", arr)
        }
    }

    private suspend fun saveSyncProfile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val name = args.optString("name").ifBlank {
            throw McpError(-32602, "Missing required argument: name")
        }
        val srcFs = args.optString("srcFs").ifBlank {
            throw McpError(-32602, "Missing required argument: srcFs")
        }
        val dstFs = args.optString("dstFs").ifBlank {
            throw McpError(-32602, "Missing required argument: dstFs")
        }
        val modeStr = args.optString("mode").ifBlank {
            throw McpError(-32602, "Missing required argument: mode")
        }
        val mode = when (modeStr.lowercase()) {
            "copy" -> "COPY"
            "sync", "mirror" -> "SYNC"
            "move" -> "MOVE"
            else -> throw McpError(-32602, "Unknown mode: $modeStr (expected copy/sync/move)")
        }
        val includes = args.optJSONArray("includePatterns")?.let { a ->
            List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val excludes = args.optJSONArray("excludePatterns")?.let { a ->
            List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val existingId = if (args.has("id") && !args.isNull("id")) args.optString("id").ifBlank { null } else null
        val existing = existingId?.let { syncProfileRepository.getById(it) }
        val profile = sh.haven.core.data.db.entities.SyncProfile(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            srcFs = srcFs,
            dstFs = dstFs,
            mode = mode,
            includePatterns = includes.joinToString("\n"),
            excludePatterns = excludes.joinToString("\n"),
            minSize = args.optString("minSize").ifBlank { null },
            maxSize = args.optString("maxSize").ifBlank { null },
            bandwidthLimit = args.optString("bandwidthLimit").ifBlank { null },
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastRunAt = existing?.lastRunAt,
        )
        syncProfileRepository.save(profile)
        syncProfileToJson(profile)
    }

    private suspend fun queueTerminalInput(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val text = args.optString("text").also {
            if (it.isEmpty()) throw McpError(-32602, "Missing required argument: text")
        }
        val explicitSessionId = args.optString("sessionId").ifEmpty { null }
        val mcpPort = mcpPortProvider()
        val sessionId = explicitSessionId
            ?: sshSessionManager.findRemoteForwardSession(mcpPort)
            ?: throw McpError(
                -32603,
                "No SSH session carries the MCP reverse tunnel on port $mcpPort; " +
                    "pass sessionId explicitly (list_sessions). For the auto-detection default " +
                    "to work, an SSH profile must have the MCP reverse-tunnel toggle on and be " +
                    "connected; for unrelated sessions just pass sessionId.",
            )
        val promptPattern = args.optString("promptPattern").ifEmpty { DEFAULT_PROMPT_PATTERN }
        val timeoutSeconds = args.optInt("timeoutSeconds", 60).coerceIn(1, 600)
        // submitKey: see [DEFAULT_SUBMIT_KEY] for the rationale on `\r`
        // vs `\n` vs `""`. Callers override to fit the target program.
        val submitKey = if (args.has("submitKey") && !args.isNull("submitKey")) {
            args.optString("submitKey")
        } else DEFAULT_SUBMIT_KEY
        val queueId = terminalInputQueue.enqueue(
            sessionId = sessionId,
            text = text,
            promptPattern = promptPattern,
            timeoutSeconds = timeoutSeconds,
            submitKey = submitKey,
            clientHint = currentClientHint,
        )
        JSONObject().apply {
            put("queueId", queueId)
            put("sessionId", sessionId)
            put("promptPattern", promptPattern)
            put("timeoutSeconds", timeoutSeconds)
            put("submitKey", submitKey)
            put("textLength", text.length)
        }
    }

    private suspend fun deleteSyncProfile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val id = args.optString("id").ifBlank {
            throw McpError(-32602, "Missing required argument: id")
        }
        val existed = syncProfileRepository.getById(id) != null
        syncProfileRepository.delete(id)
        JSONObject().apply {
            put("deleted", existed)
            put("id", id)
        }
    }

    private fun syncProfileToJson(p: sh.haven.core.data.db.entities.SyncProfile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("srcFs", p.srcFs)
        put("dstFs", p.dstFs)
        put("mode", p.mode.lowercase())
        put("includePatterns", JSONArray().apply {
            p.includePatterns.split("\n").filter { it.isNotBlank() }.forEach { put(it) }
        })
        put("excludePatterns", JSONArray().apply {
            p.excludePatterns.split("\n").filter { it.isNotBlank() }.forEach { put(it) }
        })
        p.minSize?.let { put("minSize", it) }
        p.maxSize?.let { put("maxSize", it) }
        p.bandwidthLimit?.let { put("bandwidthLimit", it) }
        put("createdAt", p.createdAt)
        p.lastRunAt?.let { put("lastRunAt", it) }
    }

    /**
     * Backend-agnostic listing — collapses [listSftpDirectory] and
     * [listRcloneDirectory] (which stay registered as deprecated
     * aliases). Resolves the [FileBackend] from `profileId` via
     * [transportSelector], so adding a new backend lights this up
     * automatically.
     */
    private suspend fun listDirectory(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path", "/").ifEmpty { "/" }
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        val arr = JSONArray()
        try {
            for (entry in resolution.backend.list(path)) {
                arr.put(JSONObject().apply {
                    put("name", entry.name)
                    put("path", entry.path)
                    put("isDir", entry.isDirectory)
                    put("size", entry.size)
                    put("mtime", entry.modifiedTime)
                    put("permissions", entry.permissions)
                    entry.mimeType?.let { put("mimeType", it) }
                })
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list $path: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("backend", resolution.backend.label)
            put("path", path)
            put("count", arr.length())
            put("entries", arr)
        }
    }

    /**
     * Backend-agnostic upload via [FileBackend.writeBytes]. Loads the
     * source into memory; agents wanting streaming for large files
     * still use the SSH-only [uploadFileToSftp] until #126 stage 4+
     * promotes streaming to [FileBackend].
     */
    private suspend fun uploadFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val localPath = args.optString("localPath").ifEmpty {
            throw McpError(-32602, "Missing required argument: localPath")
        }
        val remotePath = args.optString("remotePath").ifEmpty {
            throw McpError(-32602, "Missing required argument: remotePath")
        }
        val cacheRoot = context.cacheDir.canonicalFile
        val source = try {
            File(localPath).canonicalFile
        } catch (e: Exception) {
            throw McpError(-32602, "Cannot resolve localPath: ${e.message}")
        }
        if (!source.path.startsWith(cacheRoot.path + File.separator) && source.path != cacheRoot.path) {
            throw McpError(-32602, "localPath must be inside Haven's app cache (${cacheRoot.path})")
        }
        if (!source.exists() || !source.isFile) {
            throw McpError(-32602, "localPath does not point to a regular file")
        }
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        val data = source.readBytes()
        try {
            resolution.backend.writeBytes(remotePath, data)
        } catch (e: Exception) {
            throw McpError(-32603, "Upload failed: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("backend", resolution.backend.label)
            put("remotePath", remotePath)
            put("bytesUploaded", source.length())
        }
    }

    /**
     * Backend-agnostic file delete via [FileBackend.delete]. Refuses
     * directories — `delete_file` always passes `isDirectory = false`
     * so an agent that wants directory deletion has to use a separate
     * verb when one ships.
     */
    private suspend fun deleteFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        try {
            resolution.backend.delete(path, isDirectory = false)
        } catch (e: Exception) {
            throw McpError(-32603, "Delete failed: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("backend", resolution.backend.label)
            put("path", path)
            put("deleted", true)
        }
    }

    private suspend fun listSftpDirectory(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path", ".").ifEmpty { "." }
        val session = sshSessionManager.openSftpSession(profileId)
            ?: throw McpError(-32603, "No connected SFTP session for profile $profileId")
        val arr = JSONArray()
        try {
            session.list(path) { attrs ->
                arr.put(JSONObject().apply {
                    put("name", attrs.filename)
                    put("isDir", attrs.isDirectory)
                    put("size", attrs.size)
                    put("mtime", attrs.modifiedTimeSeconds.toLong())
                    put("permissions", attrs.permissions)
                })
                sh.haven.core.ssh.sftp.ListResult.CONTINUE
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list $path: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("path", path)
            put("count", arr.length())
            put("entries", arr)
        }
    }

    private suspend fun streamSftpFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        val session = sshSessionManager.openSftpSession(profileId)
            ?: throw McpError(-32603, "No connected SFTP session for profile $profileId")
        val size = try {
            session.stat(path).size
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to stat $path: ${e.message}")
        }

        val streamPort = sftpStreamServer.start()
        val urlPath = sftpStreamServer.publish(
            path = path,
            size = size,
            contentType = guessContentType(path),
            opener = { offset ->
                val s = sshSessionManager.openSftpSession(profileId)
                    ?: throw java.io.IOException("SFTP not connected for profile $profileId")
                // SftpStreamServer.Opener.open is a non-suspend fun interface,
                // called from the HTTP worker thread — runBlocking is fine here.
                runBlocking {
                    s.openInputStream(path, if (offset > 0) offset else 0L)
                }
            },
        )
        val sourceUrl = "http://127.0.0.1:$streamPort$urlPath"
        val hlsPort = hlsStreamServer.startFile(sourceUrl)
        JSONObject().apply {
            put("profileId", profileId)
            put("path", path)
            put("size", size)
            put("sourceUrl", sourceUrl)
            put("hlsPort", hlsPort)
            put("playlistUrl", "http://127.0.0.1:$hlsPort/stream.m3u8")
            put("playerUrl", "http://127.0.0.1:$hlsPort/")
        }
    }

    private fun stopStream(): JSONObject {
        hlsStreamServer.stop()
        return JSONObject().apply { put("stopped", true) }
    }

    /**
     * Backend-agnostic file download bridge. Resolves the profile via
     * [transportSelector], stats the path for size+mime, publishes a
     * loopback HTTP entry on [sftpStreamServer], and returns the URL.
     *
     * The capability gate ([UserPreferencesRepository.agentAllowFileRead])
     * is also enforced in [McpServer] *before* the consent prompt so a
     * disabled call fails immediately. The check here is belt-and-braces
     * for any in-process caller that might bypass the server dispatcher.
     */
    private suspend fun serveFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }

        if (!preferencesRepository.agentAllowFileRead.first()) {
            throw McpError(
                -32011,
                "agent file read is disabled — enable in Settings → Agent endpoint",
            )
        }

        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        val backend = resolution.backend

        val entry = try {
            backend.stat(path)
        } catch (t: Throwable) {
            throw McpError(-32603, "Failed to stat $path: ${t.message}")
        }
        if (entry.isDirectory) {
            throw McpError(-32602, "$path is a directory; serve_file is for single files")
        }
        val contentType = guessContentType(path)

        val port = sftpStreamServer.start()
        val urlPath = sftpStreamServer.publish(
            path = path,
            size = entry.size,
            contentType = contentType,
            opener = { offset ->
                // Re-resolve on each Range request so a reconnected
                // session picks up the fresh client/channel rather than
                // capturing a stale one from publish-time.
                val live = kotlinx.coroutines.runBlocking {
                    transportSelector.resolveFileBackend(profileId)?.backend
                } ?: throw java.io.IOException("Backend disconnected for $profileId")
                kotlinx.coroutines.runBlocking { live.openInputStream(path, offset) }
            },
        )
        servedFileTracker.register(profileId, path)

        JSONObject().apply {
            put("profileId", profileId)
            put("backend", backend.label)
            put("path", path)
            put("size", entry.size)
            put("mimeType", contentType)
            put("url", "http://127.0.0.1:$port$urlPath")
        }
    }

    private fun guessContentType(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/aac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }

    private suspend fun openConvertDialogWithArgs(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val sourcePath = args.optString("sourcePath").ifEmpty {
            throw McpError(-32602, "Missing required argument: sourcePath")
        }
        if (profileId != "local") {
            connectionRepository.getById(profileId)
                ?: throw McpError(-32602, "Unknown profileId: $profileId")
        }
        val command = sh.haven.core.data.agent.AgentUiCommand.OpenConvertDialog(
            profileId = profileId,
            sourcePath = sourcePath,
            container = args.optString("container").ifEmpty { null },
            videoEncoder = args.optString("videoEncoder").ifEmpty { null },
            audioEncoder = args.optString("audioEncoder").ifEmpty { null },
        )
        val delivered = agentUiCommandBus.emit(command)
        return JSONObject().apply {
            put("delivered", delivered)
            put("profileId", profileId)
            put("sourcePath", sourcePath)
            put("container", command.container ?: JSONObject.NULL)
            put("videoEncoder", command.videoEncoder ?: JSONObject.NULL)
            put("audioEncoder", command.audioEncoder ?: JSONObject.NULL)
        }
    }

    private fun focusTerminalSession(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId").ifEmpty {
            throw McpError(-32602, "Missing required argument: sessionId")
        }
        val command = sh.haven.core.data.agent.AgentUiCommand.FocusTerminalSession(sessionId)
        val delivered = agentUiCommandBus.emit(command)
        return JSONObject().apply {
            put("delivered", delivered)
            put("sessionId", sessionId)
        }
    }

    private suspend fun openFileInEditor(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        if (profileId != "local") {
            connectionRepository.getById(profileId)
                ?: throw McpError(-32602, "Unknown profileId: $profileId")
        }
        val command = sh.haven.core.data.agent.AgentUiCommand.OpenInEditor(
            profileId = profileId,
            path = path,
        )
        val delivered = agentUiCommandBus.emit(command)
        return JSONObject().apply {
            put("delivered", delivered)
            put("profileId", profileId)
            put("path", path)
        }
    }

    private suspend fun navigateSftpBrowser(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty { "/" }
        // Best-effort existence check so a typo'd profileId returns a
        // useful error instead of silently posting a command nobody
        // collects. "local" is a synthetic profile that has no DB row.
        if (profileId != "local") {
            connectionRepository.getById(profileId)
                ?: throw McpError(-32602, "Unknown profileId: $profileId")
        }
        val command = sh.haven.core.data.agent.AgentUiCommand.NavigateToSftpPath(
            profileId = profileId,
            path = path,
        )
        val delivered = agentUiCommandBus.emit(command)
        return JSONObject().apply {
            put("delivered", delivered)
            put("profileId", profileId)
            put("path", path)
        }
    }

    private fun playFile(args: JSONObject): JSONObject {
        val url = args.optString("url").ifEmpty {
            throw McpError(-32602, "Missing required argument: url")
        }
        val explicitMime = args.optString("mimeType", "").ifEmpty { null }
        val resolvedMime = explicitMime ?: run {
            // Best-effort extension-based MIME guess so the system app
            // picker has something to filter on. Falls back to video/*
            // for typical SFTP/rclone playback.
            val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), resolvedMime)
            // Required when launching from a non-Activity context (we
            // hold the application Context here, not the current
            // Activity). Without this Android refuses to start the
            // target activity.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // For content:// URIs the granting flag lets the chosen
            // player read through Haven's FileProvider; harmless on
            // http(s) URLs.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            JSONObject().apply {
                put("dispatched", true)
                put("url", url)
                put("mimeType", resolvedMime)
            }
        } catch (e: android.content.ActivityNotFoundException) {
            throw McpError(-32603, "No installed app can play '$resolvedMime' from this URL")
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to dispatch player intent: ${e.message}")
        }
    }

    private fun readTerminalScrollback(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId").ifEmpty {
            throw McpError(-32602, "Missing required argument: sessionId")
        }
        val requested = args.optInt("maxBytes", 16384)
        val capped = requested.coerceIn(1, 256 * 1024)
        // sessionId can come from either the SSH or the local-shell
        // manager; try both rather than forcing the agent to know
        // which transport it's looking at.
        val sshBytes = sshSessionManager.readAgentScrollback(sessionId, capped)
        val bytes = sshBytes
            ?: localSessionManager.readAgentScrollback(sessionId, capped)
            ?: throw McpError(
                -32603,
                "No scrollback available for session $sessionId — open a terminal tab on this session first",
            )
        // Whether the underlying session has ended. Without this an agent
        // can't tell a quiet shell from a dead one — the ring keeps returning
        // the last output (e.g. frozen at the login banner) with no signal.
        val ended = if (sshBytes != null) {
            sshSessionManager.getSession(sessionId) == null
        } else {
            val ls = localSessionManager.getActiveSession(sessionId)
            ls == null || !ls.isAlive()
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("byteCount", bytes.size)
            // UTF-8 with malformed-replacement so any partial codepoint
            // at the buffer head doesn't poison the whole response.
            put("text", String(bytes, Charsets.UTF_8))
            put("ended", ended)
        }
    }

    // --- Write-tool implementations -------------------------------------

    private suspend fun disconnectProfile(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        // Cross-transport hammer; the registry already knows which
        // transports have sessions for this profile and only acts where
        // there's something to do, so it's safe to call unconditionally.
        sessionManagerRegistry.disconnectProfile(profileId)
        // Match ConnectionsViewModel.disconnect — close the profile's VNC/RDP
        // Desktop tab (via the lease) and release any auto-opened SSH-tunnel
        // dependent (#121) plus the WG / Tailscale refcount (#149). Bypassing
        // these from the MCP path was the original gap — without them, a
        // profile disconnected through the agent path left its tunnel handle
        // live, and live_tunnels surfaced the bogus "still depending" state.
        sshSessionManager.teardownTunnelDependent(profileId)
        tunnelManager.release(profileId)
        return JSONObject().apply {
            put("profileId", profileId)
            put("disconnected", true)
        }
    }

    private suspend fun addPortForward(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val typeStr = args.optString("type").ifEmpty {
            throw McpError(-32602, "Missing required argument: type")
        }
        val type = try {
            PortForwardRule.Type.valueOf(typeStr.uppercase())
        } catch (e: IllegalArgumentException) {
            throw McpError(-32602, "Invalid type '$typeStr' — expected LOCAL, REMOTE, or DYNAMIC")
        }
        val bindPort = args.optInt("bindPort", -1).takeIf { it >= 0 }
            ?: throw McpError(-32602, "Missing required argument: bindPort")
        val rule = PortForwardRule(
            profileId = profileId,
            type = type,
            bindAddress = args.optString("bindAddress", "127.0.0.1"),
            bindPort = bindPort,
            targetHost = args.optString("targetHost", "localhost"),
            targetPort = args.optInt("targetPort", 0),
            enabled = true,
        )
        portForwardRepository.save(rule)

        // Activate immediately if the profile has a connected session.
        // Mirrors the UI's savePortForwardRule path. Multiple connected
        // sessions per profile are unusual but possible (multi-tab); we
        // pick the first connected one rather than fanning out.
        val session = sshSessionManager.getSessionsForProfile(profileId)
            .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }
        var actualBoundPort: Int? = null
        if (session != null) {
            val info = SshSessionManager.PortForwardInfo(
                ruleId = rule.id,
                type = when (rule.type) {
                    PortForwardRule.Type.LOCAL -> SshSessionManager.PortForwardType.LOCAL
                    PortForwardRule.Type.REMOTE -> SshSessionManager.PortForwardType.REMOTE
                    PortForwardRule.Type.DYNAMIC -> SshSessionManager.PortForwardType.DYNAMIC
                },
                bindAddress = rule.bindAddress,
                bindPort = rule.bindPort,
                targetHost = rule.targetHost,
                targetPort = rule.targetPort,
            )
            sshSessionManager.applyPortForwards(session.sessionId, listOf(info))
            // Read the actually-bound port back from the live session so
            // a bindPort=0 (REMOTE OS-pick) request can be reported back
            // to the agent.
            actualBoundPort = sshSessionManager.getSession(session.sessionId)
                ?.activeForwards
                ?.firstOrNull { it.ruleId == rule.id }
                ?.actualBoundPort
        }
        JSONObject().apply {
            put("ruleId", rule.id)
            put("activated", session != null)
            actualBoundPort?.let { put("actualBoundPort", it) }
        }
    }

    private suspend fun removePortForward(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val ruleId = args.optString("ruleId").ifEmpty {
            throw McpError(-32602, "Missing required argument: ruleId")
        }
        // Look up the rule to find the owning profile so we know which
        // session (if any) currently holds the live forward. Repository
        // doesn't expose getById today; iterate everyone's enabled rules
        // — list is tiny in practice (<10 rules per profile).
        val owningProfileId = run {
            val all = connectionRepository.getAll()
            all.firstNotNullOfOrNull { p ->
                portForwardRepository.getEnabledForProfile(p.id)
                    .firstOrNull { it.id == ruleId }
                    ?.let { p.id }
            }
        }
        if (owningProfileId != null) {
            val session = sshSessionManager.getSessionsForProfile(owningProfileId)
                .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }
            if (session != null) {
                val forward = session.activeForwards.firstOrNull { it.ruleId == ruleId }
                if (forward != null) {
                    sshSessionManager.removePortForward(session.sessionId, forward)
                }
            }
        }
        portForwardRepository.delete(ruleId)
        JSONObject().apply {
            put("ruleId", ruleId)
            put("deactivated", owningProfileId != null)
            put("deleted", true)
        }
    }

    private suspend fun uploadFileToSftp(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val localPath = args.optString("localPath").ifEmpty {
            throw McpError(-32602, "Missing required argument: localPath")
        }
        val remotePath = args.optString("remotePath").ifEmpty {
            throw McpError(-32602, "Missing required argument: remotePath")
        }
        // Confine the source to context.cacheDir so a malicious agent
        // can't exfiltrate arbitrary device files via the upload.
        val cacheRoot = context.cacheDir.canonicalFile
        val source = try {
            File(localPath).canonicalFile
        } catch (e: Exception) {
            throw McpError(-32602, "Cannot resolve localPath: ${e.message}")
        }
        if (!source.path.startsWith(cacheRoot.path + File.separator) && source.path != cacheRoot.path) {
            throw McpError(-32602, "localPath must be inside Haven's app cache (${cacheRoot.path})")
        }
        if (!source.exists() || !source.isFile) {
            throw McpError(-32602, "localPath does not point to a regular file")
        }
        val session = sshSessionManager.openSftpSession(profileId)
            ?: throw McpError(-32603, "No connected SFTP session for profile $profileId")
        try {
            source.inputStream().use { input ->
                session.upload(input, source.length(), remotePath) { _, _ -> }
            }
        } catch (e: Exception) {
            throw McpError(-32603, "SFTP upload failed: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("remotePath", remotePath)
            put("bytesUploaded", source.length())
        }
    }

    private suspend fun deleteSftpFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        val session = sshSessionManager.openSftpSession(profileId)
            ?: throw McpError(-32603, "No connected SFTP session for profile $profileId")
        try {
            // Refuse directories — `rm` and `rmdir` are different ops
            // in SFTP and the agent should pick the right verb.
            val attrs = session.stat(path)
            if (attrs.isDirectory) {
                throw McpError(-32602, "Refusing to delete directory '$path' — use a separate rmdir tool when one exists")
            }
            session.rm(path)
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "SFTP delete failed: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("path", path)
            put("deleted", true)
        }
    }

    // --- Terminal snapshot / selection / clipboard / preference helpers ---

    private fun requireRegistryEntry(sessionId: String): sh.haven.feature.terminal.agent.TerminalSessionRegistry.Entry {
        if (sessionId.isEmpty()) throw McpError(-32602, "Missing required argument: sessionId")
        return terminalSessionRegistry.get(sessionId)
            ?: throw McpError(-32603, "No registered terminal tab for session $sessionId — open a terminal tab on this session first")
    }

    private fun readTerminalSnapshot(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId")
        val entry = requireRegistryEntry(sessionId)
        val includeSegs = args.optBoolean("includeSemanticSegments", false)
        val maxLines = if (args.has("maxLines")) args.optInt("maxLines", Int.MAX_VALUE) else Int.MAX_VALUE
        val snap = entry.emulator.buildAgentSnapshot(
            includeSemanticSegments = includeSegs,
            maxLines = maxLines,
        )
        val scrollPos = entry.scrollController?.scrollbackPosition ?: 0
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("rows", snap.rows)
            put("cols", snap.cols)
            put("cursorRow", snap.cursorRow)
            put("cursorCol", snap.cursorCol)
            put("cursorVisible", snap.cursorVisible)
            put("terminalTitle", snap.terminalTitle)
            put("scrollbackSize", snap.scrollbackSize)
            put("scrollbackPosition", scrollPos)
            // Remote-driven terminal modes, scanned from the output stream
            // by MouseModeTracker. Null flows (headless agent shells) report
            // the inactive defaults.
            put("mouseMode", entry.mouseMode?.value ?: false)
            put("activeMouseMode", entry.activeMouseMode?.value ?: JSONObject.NULL)
            put("bracketPasteMode", entry.bracketPasteMode?.value ?: false)
            // Last-seen OSC events from this tab's OscHandler — useful for
            // asserting OSC 52 / 7 / 8 / 9 round-trips. Null when no event
            // of that type has been seen (or no OSC handler is wired).
            val osc = entry.oscHandler
            put("oscEvents", JSONObject().apply {
                put("lastClipboardSet", osc?.lastClipboardSet ?: JSONObject.NULL)
                put("lastCwd", osc?.lastCwd ?: JSONObject.NULL)
                put("lastHyperlink", osc?.lastHyperlink ?: JSONObject.NULL)
                put(
                    "lastNotification",
                    osc?.lastNotification?.let { (title, body) ->
                        JSONObject().apply {
                            put("title", title)
                            put("body", body)
                        }
                    } ?: JSONObject.NULL,
                )
            })
            put("lines", JSONArray().apply {
                snap.lines.forEach { line ->
                    put(JSONObject().apply {
                        put("text", line.text)
                        put("softWrapped", line.softWrapped)
                        if (includeSegs) {
                            put("semanticSegments", JSONArray().apply {
                                line.semanticSegments.forEach { seg ->
                                    put(JSONObject().apply {
                                        put("startCol", seg.startCol)
                                        put("endCol", seg.endCol)
                                        put("type", seg.type)
                                        put("promptId", seg.promptId)
                                        if (seg.metadata != null) put("metadata", seg.metadata)
                                    })
                                }
                            })
                        }
                    })
                }
            })
        }
    }

    private fun getSelection(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId")
        val entry = requireRegistryEntry(sessionId)
        val controller = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController — the tab may not be the foreground tab")
        val range = controller.getSelectionRange()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("active", controller.isSelectionActive)
            put("range", if (range == null) JSONObject.NULL else JSONObject().apply {
                put("startRow", range.startRow)
                put("startCol", range.startCol)
                put("endRow", range.endRow)
                put("endCol", range.endCol)
            })
        }
    }

    private fun readClipboard(): JSONObject {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(context)?.toString()
        } else null
        return JSONObject().apply {
            put("text", text ?: JSONObject.NULL)
        }
    }

    private fun writeClipboard(args: JSONObject): JSONObject {
        val text = args.optString("text", "")
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
            ?: throw McpError(-32603, "ClipboardManager unavailable on this device")
        cm.setPrimaryClip(android.content.ClipData.newPlainText("haven-mcp", text))
        return JSONObject().apply {
            put("bytesWritten", text.toByteArray(Charsets.UTF_8).size)
        }
    }

    private val preferenceWhitelist = setOf(
        "terminal_scrollback_rows",
        "terminal_tap_to_position_cursor",
        "terminal_font_size",
        "mouse_input_enabled",
        "mouse_drag_selects",
        "terminal_right_click",
        // SSH profile id the MCP server tunnels its loopback listener back
        // to (dedicated headless `-R`). Empty string clears it. See
        // McpTunnelManager.
        "mcp_tunnel_endpoint_profile_id",
        // Whether the MCP server also binds on the active WireGuard tunnel.
        "mcp_wireguard_enabled",
    )

    private suspend fun getPreference(args: JSONObject): JSONObject {
        val key = args.optString("key").ifEmpty {
            throw McpError(-32602, "Missing required argument: key")
        }
        if (key !in preferenceWhitelist) {
            throw McpError(-32602, "Preference $key is not in the whitelist")
        }
        val value: Any = when (key) {
            "terminal_scrollback_rows" -> preferencesRepository.terminalScrollbackRows.first()
            "terminal_tap_to_position_cursor" -> preferencesRepository.terminalTapToPositionCursor.first()
            "terminal_font_size" -> preferencesRepository.terminalFontSize.first()
            "mouse_input_enabled" -> preferencesRepository.mouseInputEnabled.first()
            "mouse_drag_selects" -> preferencesRepository.mouseDragSelects.first()
            "terminal_right_click" -> preferencesRepository.terminalRightClick.first()
            "mcp_tunnel_endpoint_profile_id" -> preferencesRepository.mcpTunnelEndpointProfileId.first() ?: ""
            "mcp_wireguard_enabled" -> preferencesRepository.mcpWireguardEnabled.first()
            else -> throw McpError(-32602, "Preference $key is not in the whitelist")
        }
        return JSONObject().apply {
            put("key", key)
            put("value", value)
        }
    }

    private suspend fun setPreference(args: JSONObject): JSONObject {
        val key = args.optString("key").ifEmpty {
            throw McpError(-32602, "Missing required argument: key")
        }
        if (key !in preferenceWhitelist) {
            throw McpError(-32602, "Preference $key is not in the whitelist")
        }
        if (!args.has("value")) throw McpError(-32602, "Missing required argument: value")
        val rawValue = args.opt("value")
        // Coerce permissively — different MCP clients serialise JSON
        // numerics inconsistently. JSONObject.opt() may hand back
        // Integer, Long, Double, or even a String that parses as a
        // number; the caller's intent is unambiguous either way.
        fun coerceInt(): Int = when (rawValue) {
            is Number -> rawValue.toInt()
            is String -> rawValue.toIntOrNull()
                ?: throw McpError(-32602, "value must be an integer for $key, got \"$rawValue\"")
            else -> throw McpError(-32602, "value must be an integer for $key, got ${rawValue?.javaClass?.simpleName}")
        }
        fun coerceBool(): Boolean = when (rawValue) {
            is Boolean -> rawValue
            is String -> rawValue.toBooleanStrictOrNull()
                ?: throw McpError(-32602, "value must be true/false for $key, got \"$rawValue\"")
            else -> throw McpError(-32602, "value must be a boolean for $key, got ${rawValue?.javaClass?.simpleName}")
        }
        when (key) {
            "terminal_scrollback_rows" -> preferencesRepository.setTerminalScrollbackRows(coerceInt())
            "terminal_font_size" -> preferencesRepository.setTerminalFontSize(coerceInt())
            "terminal_tap_to_position_cursor" -> preferencesRepository.setTerminalTapToPositionCursor(coerceBool())
            "mouse_input_enabled" -> preferencesRepository.setMouseInputEnabled(coerceBool())
            "mouse_drag_selects" -> preferencesRepository.setMouseDragSelects(coerceBool())
            "terminal_right_click" -> preferencesRepository.setTerminalRightClick(coerceBool())
            "mcp_tunnel_endpoint_profile_id" ->
                preferencesRepository.setMcpTunnelEndpointProfileId((rawValue as? String)?.ifBlank { null })
            "mcp_wireguard_enabled" -> preferencesRepository.setMcpWireguardEnabled(coerceBool())
        }
        return JSONObject().apply {
            put("key", key)
            put("value", rawValue)
        }
    }

    private fun parseSelectionMode(s: String?): org.connectbot.terminal.SelectionMode = when (s?.uppercase()) {
        null, "", "CHARACTER" -> org.connectbot.terminal.SelectionMode.CHARACTER
        "WORD" -> org.connectbot.terminal.SelectionMode.WORD
        "LINE" -> org.connectbot.terminal.SelectionMode.LINE
        "NONE" -> org.connectbot.terminal.SelectionMode.NONE
        else -> throw McpError(-32602, "Unknown selection mode: $s")
    }

    private fun startSelection(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId")
        val entry = requireRegistryEntry(sessionId)
        val controller = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController")
        val row = args.optInt("row", -1).also { if (it < 0) throw McpError(-32602, "row must be >= 0") }
        val col = args.optInt("col", -1).also { if (it < 0) throw McpError(-32602, "col must be >= 0") }
        val mode = parseSelectionMode(args.optString("mode", "CHARACTER"))
        // The Compose-side SelectionController.startSelection short-circuits
        // when `selectionManager.mode != NONE` (so the gesture handler can't
        // re-anchor while a selection is already up). Mirror "replaces any
        // existing selection on this session" by clearing first; then
        // startSelection(mode) sets both mode and an initial range, and
        // updateSelectionStart/End move it to the caller's (row, col).
        controller.clearSelection()
        controller.startSelection(mode)
        controller.updateSelectionStart(row, col)
        controller.updateSelectionEnd(row, col)
        val range = controller.getSelectionRange()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("active", controller.isSelectionActive)
            put("range", range?.let {
                JSONObject().apply {
                    put("startRow", it.startRow); put("startCol", it.startCol)
                    put("endRow", it.endRow); put("endCol", it.endCol)
                }
            } ?: JSONObject.NULL)
        }
    }

    private fun extendSelection(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId")
        val entry = requireRegistryEntry(sessionId)
        val controller = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController")
        val row = args.optInt("row", -1).also { if (it < 0) throw McpError(-32602, "row must be >= 0") }
        val col = args.optInt("col", -1).also { if (it < 0) throw McpError(-32602, "col must be >= 0") }
        controller.updateSelectionEnd(row, col)
        val range = controller.getSelectionRange()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("range", range?.let {
                JSONObject().apply {
                    put("startRow", it.startRow); put("startCol", it.startCol)
                    put("endRow", it.endRow); put("endCol", it.endCol)
                }
            } ?: JSONObject.NULL)
        }
    }

    private fun dragSelectionTo(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId")
        val entry = requireRegistryEntry(sessionId)
        val selection = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController")
        val scroll = entry.scrollController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active ScrollController")
        if (!args.has("toRow")) throw McpError(-32602, "Missing required argument: toRow")
        if (!args.has("toCol")) throw McpError(-32602, "Missing required argument: toCol")
        val toRow = args.optInt("toRow")
        val toCol = args.optInt("toCol")
        if (toCol < 0) throw McpError(-32602, "toCol must be >= 0")
        val rows = entry.emulator.dimensions.rows
        // Translate the requested logical row into a viewport-anchored
        // endpoint plus a signed step count. The Compose gesture handler
        // does this implicitly because the pointer reports a Y position;
        // MCP works in logical rows so the agent can request "drag 30
        // rows into scrollback" without knowing the viewport height.
        //
        // Sign convention here matches scroll_terminal: positive steps =
        // scroll back into older content (so a finger at the top edge
        // dragging UP into scrollback is positive); negative = scroll
        // forward toward the live screen. Each step is one row of scroll
        // paired with one shiftSelectionStartByRows of the same sign.
        val (clampedRow, requestedSteps) = when {
            toRow < 0 -> 0 to (-toRow)                          // into scrollback
            toRow >= rows -> (rows - 1) to (rows - 1 - toRow)   // toward live
            else -> toRow to 0
        }
        var actualSteps = 0
        var clamped = false
        if (requestedSteps != 0) {
            val stepDirection = if (requestedSteps > 0) +1 else -1
            val totalSteps = kotlin.math.abs(requestedSteps)
            // Read the clamp threshold from the emulator's live agent
            // snapshot rather than the ScrollController's `maxScrollback`
            // property. The controller reads through the Compose-side
            // `screenState`, which only refreshes on recomposition — for
            // sessions whose tab isn't currently the foreground (the
            // typical agent-shell case), the cached scrollback size lags
            // the underlying emulator by an arbitrary amount.
            // `buildAgentSnapshot` reads the same `_snapshot.value`
            // that's updated on every processPendingUpdates(), so it's
            // authoritative even when the Compose subtree is paused.
            val maxScrollback = entry.emulator.buildAgentSnapshot(maxLines = 0).scrollbackSize
            repeat(totalSteps) {
                val canStep = if (stepDirection > 0) {
                    scroll.scrollbackPosition < maxScrollback
                } else {
                    scroll.scrollbackPosition > 0
                }
                if (!canStep) {
                    clamped = true
                    return@repeat
                }
                scroll.scrollBy(stepDirection)
                selection.shiftSelectionStartByRows(stepDirection)
                actualSteps += stepDirection
            }
        }
        selection.updateSelectionEnd(clampedRow, toCol)
        val range = selection.getSelectionRange()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("range", range?.let {
                JSONObject().apply {
                    put("startRow", it.startRow); put("startCol", it.startCol)
                    put("endRow", it.endRow); put("endCol", it.endCol)
                }
            } ?: JSONObject.NULL)
            put("scrollbackPosition", scroll.scrollbackPosition)
            put("scrollSteps", actualSteps)
            put("clamped", clamped)
            put("text", selection.getSelectedText())
        }
    }

    private fun copySelection(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId")
        val entry = requireRegistryEntry(sessionId)
        val controller = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController")
        val text = controller.copySelection()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("text", text)
        }
    }

    private fun tapTerminal(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId")
        val entry = requireRegistryEntry(sessionId)
        val row = args.optInt("row", -1).also { if (it < 0) throw McpError(-32602, "row must be >= 0") }
        val col = args.optInt("col", -1).also { if (it < 0) throw McpError(-32602, "col must be >= 0") }
        val beforeCol = entry.emulator.buildAgentSnapshot().cursorCol
        val handled = entry.emulator.tapToPositionCursorOnPrompt(row, col)
        val afterCol = entry.emulator.buildAgentSnapshot().cursorCol
        val deltaCols = afterCol - beforeCol
        val dispatched = when {
            !handled -> "NONE"
            deltaCols < 0 -> "LEFT"
            deltaCols > 0 -> "RIGHT"
            else -> "NONE"
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("handled", handled)
            put("deltaCols", deltaCols)
            put("dispatched", dispatched)
        }
    }

    private fun scrollTerminal(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId")
        val entry = requireRegistryEntry(sessionId)
        val scrollController = entry.scrollController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active ScrollController")
        val lines = if (args.has("lines")) args.optInt("lines") else throw McpError(-32602, "Missing required argument: lines")
        scrollController.scrollBy(lines)
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("scrollbackPosition", scrollController.scrollbackPosition)
        }
    }

    private fun sendTerminalInput(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId").ifEmpty {
            throw McpError(-32602, "Missing required argument: sessionId")
        }
        val text = args.optString("text", "")
        if (text.isEmpty()) {
            throw McpError(-32602, "Missing required argument: text")
        }
        // Hard cap to keep consent prompts reviewable and avoid an agent
        // pasting megabytes through the terminal. 4 KiB is roughly two
        // screens of dense text — plenty for any reasonable command.
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > 4096) {
            throw McpError(-32602, "text too long: ${bytes.size} bytes (max 4096)")
        }
        // Try SSH first; if the SSH manager doesn't know the session,
        // fall through to the local-shell manager. Whichever throws
        // last with a "no session" / "no terminal" message is the one
        // we surface — that error is the most informative.
        val sshErr = try {
            sshSessionManager.sendInput(sessionId, text)
            null
        } catch (e: IllegalStateException) {
            e.message
        }
        if (sshErr != null) {
            try {
                localSessionManager.sendInput(sessionId, text)
            } catch (e: IllegalStateException) {
                throw McpError(-32603, e.message ?: sshErr)
            }
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("bytesSent", bytes.size)
        }
    }

    private fun feedTerminalOutput(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId").ifEmpty {
            throw McpError(-32602, "Missing required argument: sessionId")
        }
        val entry = requireRegistryEntry(sessionId)
        val feed = entry.feedOutput
            ?: throw McpError(
                -32603,
                "Session $sessionId has no output pipeline — feed_terminal_output is not supported for headless agent shells",
            )
        val hasText = args.has("text")
        val hasB64 = args.has("bytesBase64")
        if (hasText == hasB64) {
            throw McpError(-32602, "Provide exactly one of: text, bytesBase64")
        }
        val bytes: ByteArray = if (hasText) {
            args.optString("text").toByteArray(Charsets.UTF_8)
        } else {
            try {
                android.util.Base64.decode(args.optString("bytesBase64"), android.util.Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw McpError(-32602, "bytesBase64 is not valid base64: ${e.message}")
            }
        }
        if (bytes.size > 65536) {
            throw McpError(-32602, "payload too long: ${bytes.size} bytes (max 65536)")
        }
        // feedOutput is internally synchronized against the live data
        // callback, so this is safe to call straight from the MCP thread.
        feed(bytes, 0, bytes.size)
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("bytesFed", bytes.size)
        }
    }

    private fun dragTerminal(args: JSONObject): JSONObject {
        val sessionId = args.optString("sessionId").ifEmpty {
            throw McpError(-32602, "Missing required argument: sessionId")
        }
        val entry = requireRegistryEntry(sessionId)
        val injector = entry.gestureInjector
            ?: throw McpError(
                -32603,
                "Session $sessionId has no gesture injector — its terminal tab must be the foreground tab",
            )
        val pathArr = args.optJSONArray("path")
            ?: throw McpError(-32602, "Missing required argument: path")
        if (pathArr.length() == 0) {
            throw McpError(-32602, "path must have at least one cell")
        }
        if (pathArr.length() > 256) {
            throw McpError(-32602, "path too long: ${pathArr.length()} cells (max 256)")
        }
        val path = ArrayList<Pair<Int, Int>>(pathArr.length())
        for (i in 0 until pathArr.length()) {
            val cell = pathArr.optJSONObject(i)
                ?: throw McpError(-32602, "path[$i] must be an object { row, col }")
            if (!cell.has("row") || !cell.has("col")) {
                throw McpError(-32602, "path[$i] must have both row and col")
            }
            path.add(cell.getInt("row") to cell.getInt("col"))
        }
        val pressMs = if (args.has("pressMs")) args.optLong("pressMs", 900L) else 900L
        val stepMs = if (args.has("stepMs")) args.optLong("stepMs", 30L) else 30L
        val holdMs = if (args.has("holdMs")) args.optLong("holdMs", 1000L) else 1000L
        if (pressMs < 0 || stepMs < 0 || holdMs < 0) {
            throw McpError(-32602, "pressMs / stepMs / holdMs must be non-negative")
        }
        try {
            injector.injectDrag(path, pressMs, stepMs, holdMs)
        } catch (e: IllegalArgumentException) {
            throw McpError(-32602, e.message ?: "invalid drag_terminal arguments")
        } catch (e: IllegalStateException) {
            throw McpError(-32603, e.message ?: "gesture injection failed")
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("cells", path.size)
            put("approxDurationMs", pressMs + stepMs * (path.size - 1).coerceAtLeast(0) + holdMs)
        }
    }

    private fun openDeveloperSettings(): JSONObject {
        // ACTION_APPLICATION_DEVELOPMENT_SETTINGS lands directly on the
        // Developer Options screen on every Android version we support;
        // nothing in there changes without an explicit user tap, so
        // this is a tap-equivalent action — same consent shape as
        // play_file.
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            JSONObject().apply { put("dispatched", true) }
        } catch (e: android.content.ActivityNotFoundException) {
            throw McpError(
                -32603,
                "Could not open Developer Options — most likely Developer Mode is not yet enabled. Toggle Settings → About phone → Build number 7 times to unlock it.",
            )
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to open Developer Settings: ${e.message}")
        }
    }

    /** Shared upper bound on staged APK size — 200 MiB; release APKs are ~80–100 MiB. */
    private val agentInstallMaxBytes: Long = 200L * 1024 * 1024

    /** Stage a fresh File handle under cacheDir/agent-install/. */
    private fun stageApkFile(): File {
        val dir = File(context.cacheDir, "agent-install").apply { mkdirs() }
        return File(dir, "haven-agent-install-${System.currentTimeMillis()}.apk")
    }

    /**
     * Verify that the staged file's first two bytes are the zip
     * magic ("PK") — cheap defence against an HTML 200, wrong file
     * type, or a truncated download.
     */
    private fun assertApkMagic(target: File, written: Long) {
        val firstFour = target.inputStream().use { stream ->
            ByteArray(4).also { stream.read(it) }
        }
        if (firstFour[0] != 0x50.toByte() || firstFour[1] != 0x4B.toByte()) {
            target.delete()
            throw McpError(
                -32603,
                "Got $written bytes but they don't start with the zip/APK magic — wrong path/URL?",
            )
        }
    }

    private suspend fun installApkFromUrl(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val urlString = args.optString("url").ifEmpty {
            throw McpError(-32602, "Missing required argument: url")
        }
        val url = try {
            java.net.URL(urlString)
        } catch (e: Exception) {
            throw McpError(-32602, "Invalid URL: ${e.message}")
        }
        if (url.protocol !in setOf("http", "https")) {
            throw McpError(-32602, "Only http(s) URLs are supported (got ${url.protocol})")
        }
        // Stage the APK in app cache, then pipe it into `pm install -S`
        // via Shizuku (or hand off to the system installer dialog).
        val target = stageApkFile()
        val written = try {
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Haven/1.0 (install_apk_from_url)")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw McpError(-32603, "HTTP ${conn.responseCode} from ${url.host}")
            }
            try {
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > agentInstallMaxBytes) {
                                target.delete()
                                throw McpError(-32603, "APK exceeds ${agentInstallMaxBytes / (1024 * 1024)} MiB cap")
                            }
                            output.write(buf, 0, n)
                        }
                        total
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            target.delete()
            throw McpError(-32603, "Download failed: ${e.message}")
        }
        // Sanity check — APK is a zip, magic bytes 0x50 0x4B ("PK").
        // Reject HTML / plain-text bodies that 200'd despite being the
        // wrong content type. Cheap and catches the most common
        // wrong-URL failure mode.
        assertApkMagic(target, written)
        return@withContext installStagedApk(target, written)
    }

    /**
     * Install an APK that's already been staged at [target]. Tries
     * the Shizuku-driven `pm install -S` path first; falls back to
     * firing the system installer dialog via `Intent.ACTION_VIEW`
     * when Shizuku is unavailable.
     *
     * Caller owns [target]'s creation; this function takes ownership
     * of cleanup. On the system-installer fallback path the file
     * survives long enough for the installer to read it and is
     * cleaned up by a delayed Handler.
     */
    private fun installStagedApk(target: File, written: Long): JSONObject {
        // Hand off to Shizuku — `pm install -S <size>` reads APK bytes
        // from stdin. Working directory of the shell process doesn't
        // need to see our cache dir; we pipe FileInputStream directly.
        //
        // NOTE: do NOT wrap the Shizuku call in a `finally { delete }`
        // here — the Shizuku-unavailable branch needs the staged APK
        // to survive long enough for the system installer to read it
        // via FileProvider. Each terminal path handles its own cleanup.
        val result = try {
            sh.haven.core.local.WaylandSocketHelper.execAsShizukuWithStdin(
                cmd = "pm install -S $written",
                stdin = target.inputStream(),
            )
        } catch (e: IllegalStateException) {
            // Shizuku unavailable — fall back to the system installer
            // dialog. The user gets one confirmation prompt on-device;
            // the MCP call returns immediately with pending=true. The
            // staged APK is intentionally not deleted here — the
            // system installer reads it via FileProvider after this
            // function returns. A delayed Handler cleans up after 5
            // min in case the user dismisses the dialog.
            return launchSystemInstaller(target, written, e.message)
        } catch (e: Exception) {
            target.delete()
            throw McpError(-32603, "Shizuku exec failed: ${e.message}")
        }
        // Shizuku path took ownership of the bytes; delete the staged
        // APK regardless of the install outcome.
        target.delete()
        if (result.exitCode != 0 || !result.output.contains("Success")) {
            throw McpError(
                -32603,
                "pm install exited ${result.exitCode}: ${result.output.take(500)}",
            )
        }
        return JSONObject().apply {
            put("installed", true)
            put("bytesDownloaded", written)
            put("output", result.output)
        }
    }

    private suspend fun installApkFromBackend(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        // Same gate as serve_file — installing an APK reads bytes
        // from a connected backend, so it falls under "allow agents
        // to read file contents". The system installer still asks
        // for confirmation on the Shizuku-less path; on the Shizuku
        // path the EVERY_CALL consent on this tool covers it.
        if (!preferencesRepository.agentAllowFileRead.first()) {
            throw McpError(
                -32011,
                "agent file read is disabled — enable in Settings → Agent endpoint",
            )
        }
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        val backend = resolution.backend

        val entry = try {
            backend.stat(path)
        } catch (t: Throwable) {
            throw McpError(-32603, "Failed to stat $path: ${t.message}")
        }
        if (entry.isDirectory) {
            throw McpError(-32602, "$path is a directory; need an APK file")
        }
        if (entry.size > agentInstallMaxBytes) {
            throw McpError(
                -32603,
                "$path is ${entry.size} bytes — exceeds ${agentInstallMaxBytes / (1024 * 1024)} MiB cap",
            )
        }

        val target = stageApkFile()
        val written = try {
            backend.openInputStream(path, 0).use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > agentInstallMaxBytes) {
                            target.delete()
                            throw McpError(-32603, "APK exceeds ${agentInstallMaxBytes / (1024 * 1024)} MiB cap")
                        }
                        output.write(buf, 0, n)
                    }
                    total
                }
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            target.delete()
            throw McpError(-32603, "Read from ${backend.label} failed: ${e.message}")
        }
        assertApkMagic(target, written)
        installStagedApk(target, written)
    }

    /**
     * Shizuku-less fallback for [installApkFromUrl] — fires the
     * system installer dialog via `Intent.ACTION_VIEW` with a
     * FileProvider URI for the staged APK. Returns a structured
     * response indicating the install is pending user confirmation
     * on-device.
     *
     * The APK stays in [target] until the system installer reads
     * it. A delayed cleanup job removes the file after a generous
     * window so it doesn't linger when the user dismisses the
     * dialog.
     */
    private fun launchSystemInstaller(target: java.io.File, written: Long, shizukuReason: String?): JSONObject {
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            )
        } catch (e: Exception) {
            target.delete()
            throw McpError(
                -32603,
                "FileProvider URI build failed: ${e.message}. Cannot show system installer.",
            )
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    or android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            target.delete()
            throw McpError(
                -32603,
                "Failed to launch system installer: ${e.message}. " +
                    "Shizuku also unavailable (${shizukuReason ?: "no detail"}).",
            )
        }
        // Schedule a delayed cleanup of the staged APK so it doesn't
        // linger if the user dismisses the install dialog. 5-min
        // window is generous — the system installer reads the file
        // when the user taps Install, typically within seconds.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { target.delete() },
            5 * 60 * 1000L,
        )
        return JSONObject().apply {
            put("installed", false)
            put("pending", true)
            put("bytesDownloaded", written)
            put(
                "message",
                "Shizuku unavailable (${shizukuReason ?: "not running"}); " +
                    "system installer dialog launched — confirm on-device to complete the install.",
            )
        }
    }

    private suspend fun enableWirelessAdb(): JSONObject = withContext(Dispatchers.IO) {
        // `cmd settings put global adb_wifi_enabled 1` is the
        // Android-11+ canonical flag. On older Android (<=10) the
        // setting key didn't exist and adb-over-tcp was driven by the
        // `service.adb.tcp.port` system property; we only target API
        // 26+ so the modern path is enough — older devices fall back
        // to the user toggling Developer Options by hand.
        val result = try {
            WaylandSocketHelper.execAsShizuku("cmd settings put global adb_wifi_enabled 1")
        } catch (e: IllegalStateException) {
            throw McpError(
                -32603,
                "${e.message}. Install Shizuku from https://shizuku.rikka.app and grant Haven permission, then retry.",
            )
        } catch (e: Exception) {
            throw McpError(-32603, "Shizuku exec failed: ${e.message}")
        }
        if (result.exitCode != 0) {
            throw McpError(
                -32603,
                "settings put exited ${result.exitCode}: ${result.output}",
            )
        }
        // Read back the bound port so the agent can `adb connect <ip>:<port>` directly.
        val portReadback = try {
            WaylandSocketHelper.execAsShizuku("cmd settings get global adb_wifi_port")
        } catch (_: Exception) {
            null
        }
        val port = portReadback?.output?.trim()?.toIntOrNull()
        // Best-effort LAN IP discovery (excludes loopback/down/IPv6).
        val ip = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
        JSONObject().apply {
            put("enabled", true)
            put("ip", ip ?: JSONObject.NULL)
            put("port", port ?: JSONObject.NULL)
            put(
                "note",
                if (port != null) "If the host has paired before, run: adb connect ${ip ?: "<phone-ip>"}:$port"
                else "Open Settings → Developer Options → Wireless debugging on the device to read the pairing port if you need to pair a new host.",
            )
        }
    }

    private suspend fun openLocalShell(args: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val plain = args.optBoolean("plain", false)
        // Find or seed the canonical "Local Shell" profile, mirroring
        // ConnectionsViewModel.connectLocalTerminal so the agent and
        // the user reach the same place. We deliberately do NOT trigger
        // a rootfs install from here — that's a long-running install
        // step that belongs behind explicit user consent in the UI; if
        // the rootfs is missing the session falls back to /system/bin/sh,
        // same as the existing UI fallback.
        val all = connectionRepository.getAll()
        val existing = all.firstOrNull {
            it.connectionType == "LOCAL" && !it.useAndroidShell
        }
        val profile = existing ?: run {
            val seeded = ConnectionProfile(
                label = "Local Shell",
                host = "localhost",
                username = "",
                port = 0,
                connectionType = "LOCAL",
                useAndroidShell = false,
            )
            connectionRepository.save(seeded)
            connectionRepository.getAll()
                .firstOrNull { it.connectionType == "LOCAL" && it.label == "Local Shell" && !it.useAndroidShell }
                ?: seeded
        }
        // Reuse a connected session for this profile if one is already
        // alive — there's no point spinning up a second PRoot when the
        // existing one is exactly what the agent wants. Multiple shells
        // on the same profile is a valid pattern, but the simple "give
        // me a sessionId I can type into" use case is best served by
        // the shortest path.
        val alive = localSessionManager.getSessionsForProfile(profile.id)
            .firstOrNull { it.status == LocalSessionManager.SessionState.Status.CONNECTED }
        val sessionId = if (alive != null) {
            alive.sessionId
        } else {
            val sid = localSessionManager.registerSession(profile.id, profile.label, profile.useAndroidShell)
            try {
                localSessionManager.connectSession(sid)
            } catch (e: Exception) {
                localSessionManager.updateStatus(sid, LocalSessionManager.SessionState.Status.ERROR)
                localSessionManager.removeSession(sid)
                throw McpError(-32603, "Failed to open local shell: ${e.message}")
            }
            sid
        }
        // Spin up the PTY without a UI tab on top so the agent can
        // immediately type into the session. Idempotent — a no-op if
        // the user already has a terminal tab open on this profile.
        // Also tees the data stream into a TerminalEmulator and
        // registers it with terminalSessionRegistry so the structured
        // MCP tools (read_terminal_snapshot, tap_terminal,
        // send_terminal_input) work against headless agent shells —
        // not just sessions the user has visited in the Terminal tab
        // (gap surfaced during v5.32.0-rc2 MCP testing). When a UI
        // tab is later built for the same session, syncSessions skips
        // it because LocalSession is already attached, so the
        // registry's headless emulator stays the source of truth for
        // agent reads.
        if (terminalSessionRegistry.get(sessionId) == null) {
            val scrollbackRows = preferencesRepository.terminalScrollbackRows.first()
            val agentEmulator = org.connectbot.terminal.TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                onKeyboardInput = { data ->
                    try {
                        localSessionManager.sendInput(sessionId, String(data, Charsets.UTF_8))
                    } catch (e: Exception) {
                        // Session went away mid-write; agents recover via list_sessions.
                    }
                },
                onResize = { dims ->
                    // When a UI tab adopts this headless session, HavenTerminal
                    // sizes the emulator from the Compose canvas — propagate
                    // that to the underlying PTY so `clear`/nano/etc. see the
                    // real viewport. Looking the LocalSession up each call
                    // tolerates the chicken-and-egg between emulator creation
                    // and startHeadlessShell.
                    localSessionManager.getActiveSession(sessionId)?.resize(dims.columns, dims.rows)
                },
                maxScrollbackLines = scrollbackRows,
            )
            // writeInput must happen on the main looper — libvterm's
            // OSC dispatch is wired against the same thread that
            // initialised the native state, and cross-thread writes
            // succeed at the cell level but silently drop OSC callbacks
            // (notably 133 prompt markers and OSC 2 title). Mirroring
            // EmulatorWriteBuffer's main-thread post pattern.
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            localSessionManager.startHeadlessShell(
                sessionId,
                extraOnData = { data, off, len ->
                    val copy = data.copyOfRange(off, off + len)
                    mainHandler.post { agentEmulator.writeInput(copy, 0, copy.size) }
                },
                plain = plain,
            )
            terminalSessionRegistry.register(sessionId, agentEmulator)
        } else {
            // No-op when an existing LocalSession is attached, regardless of
            // the original plain choice — see LocalSessionManager.startHeadlessShell.
            localSessionManager.startHeadlessShell(sessionId, plain = plain)
        }
        // The PTY reader thread starts asynchronously, so wait briefly for the
        // shell's first output before returning — otherwise an immediate
        // send_terminal_input / read_terminal_scrollback races an unattached
        // PTY (input ignored, scrollback empty). `ready` tells the agent
        // whether output is already flowing.
        val ready = localSessionManager.awaitFirstOutput(sessionId)
        JSONObject().apply {
            put("sessionId", sessionId)
            put("profileId", profile.id)
            put("label", profile.label)
            put("reused", alive != null)
            put("plain", plain)
            put("ready", ready)
        }
    }

    private suspend fun setTerminalFontFromUrl(args: JSONObject): JSONObject {
        val urlString = args.optString("url").ifEmpty {
            throw McpError(-32602, "Missing required argument: url")
        }
        // Delegate to the shared installer so the agent and the user
        // surfaces touch identical bytes — same download path, same
        // validation, same path layout, same preference write.
        return when (val r = terminalFontInstaller.installFromUrl(urlString)) {
            is TerminalFontInstaller.Result.Success -> JSONObject().apply {
                put("path", r.path)
                put("bytesDownloaded", r.bytesInstalled)
            }
            is TerminalFontInstaller.Result.Failure -> throw McpError(-32603, r.message)
        }
    }

    private suspend fun convertFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val sourceUrl = args.optString("sourceUrl").ifEmpty {
            throw McpError(-32602, "Missing required argument: sourceUrl")
        }
        val container = args.optString("container").ifEmpty {
            throw McpError(-32602, "Missing required argument: container")
        }
        val videoEncoder = args.optString("videoEncoder", "copy").ifEmpty { "copy" }
        val audioEncoder = args.optString("audioEncoder", "copy").ifEmpty { "copy" }
        if (!ffmpegExecutor.isAvailable()) {
            throw McpError(-32603, "ffmpeg is not available in this build")
        }
        val outputDir = File(context.cacheDir, "agent-convert").apply { mkdirs() }
        val outputName = args.optString("outputName", "").ifEmpty {
            "agent-convert-${System.currentTimeMillis()}"
        }
        val outputFile = File(outputDir, "$outputName.$container")
        val cmd = TranscodeCommand(input = sourceUrl, output = outputFile.absolutePath)
            .videoCodec(videoEncoder)
            .audioCodec(audioEncoder)
            .build()
        val started = System.currentTimeMillis()
        val result = ffmpegExecutor.execute(cmd)
        val elapsedMs = System.currentTimeMillis() - started
        if (result.exitCode != 0) {
            // Surface the last few stderr lines so the agent can react
            // to common failures (codec missing, source unreachable).
            val tail = result.stderr.lineSequence().toList().takeLast(8).joinToString("\n")
            throw McpError(-32603, "ffmpeg exit ${result.exitCode}: $tail")
        }
        JSONObject().apply {
            put("outputPath", outputFile.absolutePath)
            put("sizeBytes", outputFile.length())
            put("durationMs", elapsedMs)
            put("container", container)
            put("videoEncoder", videoEncoder)
            put("audioEncoder", audioEncoder)
        }
    }

    private fun listRcloneDirectory(args: JSONObject): JSONObject {
        val remote = args.optString("remote").ifEmpty {
            throw McpError(-32602, "Missing required argument: remote")
        }
        val path = args.optString("path", "")
        return try {
            ensureRcloneReady()
            val entries = rcloneClient.listDirectory(remote, path)
            JSONObject().apply {
                put("remote", remote)
                put("path", path)
                put("count", entries.size)
                put("entries", JSONArray().apply {
                    for (e in entries) {
                        put(JSONObject().apply {
                            put("name", e.name)
                            put("path", e.path)
                            put("isDir", e.isDir)
                            put("size", e.size)
                            put("mimeType", e.mimeType)
                            put("modTime", e.modTime)
                        })
                    }
                })
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list $remote:$path : ${e.message}")
        }
    }

    // --- Workspace tools -------------------------------------------------

    private suspend fun listWorkspaces(): JSONObject {
        val profiles = workspaceRepository.observeAll().first()
        val arr = JSONArray()
        for (profile in profiles) {
            val items = workspaceRepository.observeItems(profile.id).first()
            val kindCounts = items.groupingBy { it.kind.name }.eachCount()
            arr.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("itemCount", items.size)
                put("kinds", JSONObject().apply {
                    for ((kind, n) in kindCounts) put(kind, n)
                })
                put("createdAt", profile.createdAt)
                put("updatedAt", profile.updatedAt)
            })
        }
        return JSONObject().apply {
            put("count", profiles.size)
            put("workspaces", arr)
        }
    }

    /**
     * Dispatch [workspaceLauncher] for the supplied workspace id.
     * The launcher walks items via [agentUiCommandBus] tryEmit calls,
     * which are non-blocking — the actual session/tab opening happens
     * in the corresponding feature ViewModels' collectors. So this
     * returns once every item has been dispatched (fast), with the
     * launcher state machine settled at Completed/Cancelled/Failed.
     * Live progress also shows up in the Connections-screen banner
     * via the same StateFlow the user's tap drives.
     */
    private suspend fun composeWorkspace(args: JSONObject): JSONObject {
        val workspaceId = args.optString("workspaceId").ifEmpty {
            throw McpError(-32602, "Missing required argument: workspaceId")
        }
        val workspace = workspaceRepository.getWorkspace(workspaceId)
            ?: throw McpError(-32602, "No workspace with id $workspaceId")
        workspaceLauncher.launch(workspaceId)
        val finalState = workspaceLauncher.state.value
        return JSONObject().apply {
            put("workspaceId", workspaceId)
            put("workspaceName", workspace.profile.name)
            put("itemCount", workspace.items.size)
            put("outcome", finalState::class.simpleName ?: "Unknown")
        }
    }

    // ---- #149 tunnel + routing tools --------------------------------------

    private suspend fun listTunnels(): JSONObject {
        val tunnels = tunnelConfigRepository.getAll()
        val arr = JSONArray()
        for (t in tunnels) {
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("label", t.label)
                put("type", t.type)
                put("createdAt", t.createdAt)
            })
        }
        return JSONObject().apply {
            put("count", tunnels.size)
            put("tunnels", arr)
        }
    }

    private suspend fun listSshKeys(): JSONObject {
        val keys = sshKeyRepository.getAll()
        val arr = JSONArray()
        for (k in keys) {
            arr.put(JSONObject().apply {
                put("id", k.id)
                put("label", k.label)
                put("keyType", k.keyType)
                put("publicKeyOpenSsh", k.publicKeyOpenSsh)
                put("fingerprintSha256", k.fingerprintSha256)
                put("isEncrypted", k.isEncrypted)
                put("biometricProtected", k.biometricProtected)
                put("hasCertificate", k.certificateBytes != null)
                put("caConfigId", k.caConfigId ?: JSONObject.NULL)
                put("certIssuedAt", k.certIssuedAt ?: JSONObject.NULL)
                put("createdAt", k.createdAt)
            })
        }
        return JSONObject().apply {
            put("count", keys.size)
            put("keys", arr)
        }
    }

    private suspend fun importSshKey(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val privateKey = args.optString("privateKey").ifBlank {
            throw IllegalArgumentException("privateKey required")
        }
        val label = args.optString("label").ifBlank {
            throw IllegalArgumentException("label required")
        }
        val passphrase = args.optString("passphrase").takeIf { it.isNotEmpty() }

        val fileBytes = privateKey.toByteArray(Charsets.UTF_8)
        val imported = try {
            sh.haven.core.ssh.SshKeyImporter.import(fileBytes, passphrase)
        } catch (e: sh.haven.core.ssh.SshKeyImporter.EncryptedKeyException) {
            throw IllegalArgumentException(
                "Key is passphrase-encrypted — pass `passphrase` to import.",
            )
        } catch (e: sh.haven.core.ssh.SshKeyImporter.SkKeyDetectedException) {
            throw IllegalArgumentException(
                "FIDO2 SK keys must be imported via the Keys → Discover from security key " +
                    "flow on-device — they aren't pasteable text.",
            )
        }

        val entity = sh.haven.core.data.db.entities.SshKey(
            label = label,
            keyType = imported.keyType,
            privateKeyBytes = imported.privateKeyBytes,
            publicKeyOpenSsh = imported.publicKeyOpenSsh,
            fingerprintSha256 = imported.fingerprintSha256,
            isEncrypted = imported.isEncrypted,
        )
        sshKeyRepository.save(entity)

        JSONObject().apply {
            put("id", entity.id)
            put("label", entity.label)
            put("keyType", entity.keyType)
            put("publicKeyOpenSsh", entity.publicKeyOpenSsh)
            put("fingerprintSha256", entity.fingerprintSha256)
            put("isEncrypted", entity.isEncrypted)
        }
    }

    private suspend fun deleteSshKey(args: JSONObject): JSONObject {
        val id = args.optString("sshKeyId").ifBlank {
            throw IllegalArgumentException("sshKeyId required")
        }
        val existing = sshKeyRepository.getById(id)
            ?: throw IllegalArgumentException("No SSH key with id $id")
        sshKeyRepository.delete(id)
        return JSONObject().apply {
            put("deleted", true)
            put("id", id)
            put("label", existing.label)
        }
    }

    private suspend fun listTotpSecrets(): JSONObject {
        val secrets = totpSecretRepository.getAll()
        val arr = JSONArray()
        for (s in secrets) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("label", s.label)
                put("issuer", s.issuer ?: JSONObject.NULL)
                put("accountName", s.accountName ?: JSONObject.NULL)
                put("algorithm", s.algorithm)
                put("digits", s.digits)
                put("periodSeconds", s.periodSeconds)
                put("createdAt", s.createdAt)
            })
        }
        return JSONObject().apply {
            put("count", secrets.size)
            put("secrets", arr)
        }
    }

    private suspend fun createTotpSecret(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val otpauth = args.optString("otpauth").takeIf { it.isNotBlank() }
        val rawSecret = args.optString("secret").takeIf { it.isNotBlank() }
        val input = otpauth ?: rawSecret
            ?: throw IllegalArgumentException("Pass either `otpauth` or `secret`")
        val parsed = sh.haven.core.security.OtpAuthUri.parse(input)
            ?: throw IllegalArgumentException("Not a valid otpauth:// URI or base32 secret")
        val labelOverride = args.optString("label").takeIf { it.isNotBlank() }
        val entity = sh.haven.core.data.db.entities.TotpSecret(
            label = labelOverride ?: parsed.label,
            secret = parsed.secret,
            issuer = parsed.issuer,
            accountName = parsed.accountName,
            algorithm = parsed.algorithm.name,
            digits = parsed.digits,
            periodSeconds = parsed.periodSeconds,
        )
        totpSecretRepository.save(entity)
        JSONObject().apply {
            put("id", entity.id)
            put("label", entity.label)
            put("issuer", entity.issuer ?: JSONObject.NULL)
            put("accountName", entity.accountName ?: JSONObject.NULL)
            put("algorithm", entity.algorithm)
            put("digits", entity.digits)
            put("periodSeconds", entity.periodSeconds)
            put("authMethodToken", "TOTP:${entity.id}")
        }
    }

    private suspend fun deleteTotpSecret(args: JSONObject): JSONObject {
        val id = args.optString("totpSecretId").ifBlank {
            throw IllegalArgumentException("totpSecretId required")
        }
        val existing = totpSecretRepository.getById(id)
            ?: throw IllegalArgumentException("No TOTP secret with id $id")
        totpSecretRepository.delete(id)
        return JSONObject().apply {
            put("deleted", true)
            put("id", id)
            put("label", existing.label)
        }
    }

    private suspend fun listLiveTunnels(): JSONObject {
        val live = tunnelManager.liveSnapshot()
        val arr = JSONArray()
        for (entry in live) {
            arr.put(JSONObject().apply {
                put("configId", entry.configId)
                put("dependentCount", entry.dependentProfileIds.size)
                put("dependentProfileIds", JSONArray().apply {
                    entry.dependentProfileIds.forEach { put(it) }
                })
            })
        }
        return JSONObject().apply {
            put("count", live.size)
            put("liveTunnels", arr)
        }
    }

    private suspend fun createTunnel(args: JSONObject): JSONObject {
        val label = args.optString("label").ifBlank { throw IllegalArgumentException("label required") }
        val typeRaw = args.optString("type").uppercase()
        val type = when (typeRaw) {
            "WIREGUARD" -> TunnelConfigType.WIREGUARD
            "TAILSCALE" -> TunnelConfigType.TAILSCALE
            "CLOUDFLARE_ACCESS" -> TunnelConfigType.CLOUDFLARE_ACCESS
            else -> throw IllegalArgumentException(
                "type must be WIREGUARD, TAILSCALE, or CLOUDFLARE_ACCESS"
            )
        }
        val configBytes: ByteArray = when (type) {
            TunnelConfigType.WIREGUARD -> {
                val wgQuick = args.optString("configText")
                if (wgQuick.isBlank()) {
                    throw IllegalArgumentException("configText required for WIREGUARD type")
                }
                wgQuick.toByteArray()
            }
            TunnelConfigType.CLOUDFLARE_ACCESS -> {
                // MCP path supports unprotected Cloudflare Tunnel routes
                // (hostname only) as well as Access-protected ones
                // (additionally requiring a JWT). The in-app WebView
                // sign-in flow is interactive and can't be driven by an
                // agent — agents wanting Access auth must already hold
                // a JWT (e.g. from `cloudflared access token --app <host>`).
                val hostname = args.optString("accessHostname")
                if (hostname.isBlank()) {
                    throw IllegalArgumentException(
                        "accessHostname required for CLOUDFLARE_ACCESS type"
                    )
                }
                val jwt = args.optString("accessJwt")
                val teamDomain = args.optString("accessTeamDomain")
                val jumpDestination = args.optString("accessJumpDestination")
                val explicitExpiry = args.optLong("accessExpiresAt", 0L)
                val derivedExpiry = if (explicitExpiry > 0) {
                    explicitExpiry
                } else if (jwt.isNotBlank()) {
                    sh.haven.core.security.JwtPayload.parse(jwt)
                        ?.expiresAtSeconds ?: 0L
                } else 0L
                sh.haven.core.tunnel.CloudflareAccessConfigBlob(
                    hostname = hostname,
                    teamDomain = teamDomain,
                    jwt = jwt,
                    jwtExpiresAt = derivedExpiry,
                    jumpDestination = jumpDestination,
                ).encode()
            }
            TunnelConfigType.TAILSCALE -> {
                val authKey = args.optString("tailscaleAuthKey")
                if (authKey.isBlank()) {
                    throw IllegalArgumentException("tailscaleAuthKey required for TAILSCALE type")
                }
                val controlUrl = args.optString("tailscaleControlUrl")
                sh.haven.core.tunnel.TailscaleConfigBlob(authKey, controlUrl).encode()
            }
        }
        val config = TunnelConfig(
            label = label,
            type = type.name,
            configText = configBytes,
        )
        tunnelConfigRepository.save(config)
        return JSONObject().apply {
            put("id", config.id)
            put("label", config.label)
            put("type", config.type)
            put("createdAt", config.createdAt)
        }
    }

    private suspend fun deleteTunnel(args: JSONObject): JSONObject {
        val id = args.optString("tunnelConfigId").ifBlank {
            throw IllegalArgumentException("tunnelConfigId required")
        }
        val existing = tunnelConfigRepository.getById(id)
            ?: return JSONObject().apply { put("deleted", false); put("reason", "not found") }
        tunnelConfigRepository.delete(id)
        // If a live tunnel depends on this config, ask TunnelManager to
        // release every dependent — they fall through to direct on next
        // connect. (The current TunnelManager API is profileId-keyed so
        // the simplest correct thing is to release each dependent.)
        tunnelManager.liveSnapshot()
            .firstOrNull { it.configId == id }
            ?.dependentProfileIds
            ?.forEach { tunnelManager.release(it) }
        return JSONObject().apply {
            put("deleted", true)
            put("id", id)
            put("label", existing.label)
        }
    }

    private suspend fun setProfileRouting(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val profile = connectionRepository.getById(profileId)
            ?: throw IllegalArgumentException("profile $profileId not found")
        val clear = args.optBoolean("clear", false)
        val tunnelConfigId = if (args.has("tunnelConfigId")) args.optString("tunnelConfigId") else null
        val proxyType = if (args.has("proxyType")) args.optString("proxyType") else null
        val proxyHost = if (args.has("proxyHost")) args.optString("proxyHost") else null
        val proxyPort = if (args.has("proxyPort")) args.optInt("proxyPort", 1080) else null

        val updated = when {
            clear -> profile.copy(
                tunnelConfigId = null,
                proxyType = null,
                proxyHost = null,
            )
            !tunnelConfigId.isNullOrBlank() -> {
                tunnelConfigRepository.getById(tunnelConfigId)
                    ?: throw IllegalArgumentException("tunnel config $tunnelConfigId not found")
                profile.copy(
                    tunnelConfigId = tunnelConfigId,
                    proxyType = null,
                    proxyHost = null,
                )
            }
            !proxyType.isNullOrBlank() -> {
                if (proxyType !in setOf("SOCKS5", "SOCKS4", "HTTP")) {
                    throw IllegalArgumentException("proxyType must be SOCKS5, SOCKS4, or HTTP")
                }
                if (proxyHost.isNullOrBlank()) {
                    throw IllegalArgumentException("proxyHost required when proxyType is set")
                }
                profile.copy(
                    tunnelConfigId = null,
                    proxyType = proxyType,
                    proxyHost = proxyHost,
                    proxyPort = proxyPort ?: profile.proxyPort,
                )
            }
            else -> throw IllegalArgumentException(
                "set_profile_routing needs one of: tunnelConfigId, proxyType+proxyHost, or clear=true"
            )
        }
        connectionRepository.save(updated)
        return JSONObject().apply {
            put("profileId", updated.id)
            put("tunnelConfigId", updated.tunnelConfigId ?: JSONObject.NULL)
            put("proxyType", updated.proxyType ?: JSONObject.NULL)
            put("proxyHost", updated.proxyHost ?: JSONObject.NULL)
            put("proxyPort", updated.proxyPort)
        }
    }

    private suspend fun createConnection(args: JSONObject): JSONObject {
        val label = args.optString("label").ifBlank { throw IllegalArgumentException("label required") }
        val type = args.optString("connectionType").uppercase().ifBlank {
            throw IllegalArgumentException("connectionType required")
        }
        if (type !in setOf("SSH", "SMB", "VNC", "RDP")) {
            throw IllegalArgumentException("connectionType must be SSH, SMB, VNC, or RDP (use the UI for LOCAL / RCLONE / RETICULUM)")
        }
        val host = args.optString("host").ifBlank { throw IllegalArgumentException("host required") }
        val username = args.optString("username")
        val password = args.optString("password")
        val tunnelConfigId = if (args.has("tunnelConfigId")) args.optString("tunnelConfigId") else null
        if (!tunnelConfigId.isNullOrBlank()) {
            tunnelConfigRepository.getById(tunnelConfigId)
                ?: throw IllegalArgumentException("tunnel config $tunnelConfigId not found")
        }

        val defaultPort = when (type) {
            "SSH" -> 22
            "SMB" -> 445
            "VNC" -> 5900
            "RDP" -> 3389
            else -> 22
        }
        val port = if (args.has("port")) args.optInt("port", defaultPort) else defaultPort

        val tunnelOnly = args.optBoolean("tunnelOnly", false)
        if (tunnelOnly && type != "SSH") {
            throw IllegalArgumentException("tunnelOnly is only meaningful for SSH connections")
        }

        // #166: optional ordered auth-method list. Each element is a token:
        // "PASSWORD", "KEY" (any saved key), "KEY:<keyId>", or
        // "KEYBOARD_INTERACTIVE". Serialised newline-per-token to match
        // ConnectionProfile.authMethods. Empty/absent = derive from keyId.
        val authMethodsArr = args.optJSONArray("authMethods")
        val authMethodsText = if (authMethodsArr != null) {
            (0 until authMethodsArr.length())
                .joinToString("\n") { authMethodsArr.getString(it).trim() }
        } else ""

        // Validate the knock sequence eagerly so an invalid string is
        // rejected at create time rather than silently saved and only
        // surfaced when the user (or agent) tries to connect. Empty/blank
        // means "no knock configured" — equivalent to omitting the field.
        val rawKnock = args.optString("portKnockSequence")
        val knockDelay = if (args.has("portKnockDelayMs")) args.optInt("portKnockDelayMs", 100) else 100
        val parsedKnock = sh.haven.core.knock.KnockSequence.parse(rawKnock, knockDelay)
            .getOrElse { throw IllegalArgumentException("portKnockSequence: ${it.message}") }
        val knockSequence = if (parsedKnock != null) rawKnock.trim() else null

        val profile = when (type) {
            "SSH" -> ConnectionProfile(
                label = label,
                host = host,
                port = port,
                username = username,
                sshPassword = password.ifBlank { null },
                connectionType = "SSH",
                useMosh = args.optBoolean("useMosh", false),
                keyId = args.optString("keyId").ifBlank { null },
                authMethods = authMethodsText,
                tunnelConfigId = tunnelConfigId,
                tunnelOnly = tunnelOnly,
                portKnockSequence = knockSequence,
                portKnockDelayMs = knockDelay,
            )
            "SMB" -> {
                val share = args.optString("smbShare").ifBlank {
                    throw IllegalArgumentException("smbShare required for SMB")
                }
                ConnectionProfile(
                    label = label,
                    host = host,
                    port = port,
                    username = username,
                    connectionType = "SMB",
                    smbPort = port,
                    smbShare = share,
                    smbDomain = args.optString("smbDomain").ifBlank { null },
                    smbPassword = password.ifBlank { null },
                    tunnelConfigId = tunnelConfigId,
                    portKnockSequence = knockSequence,
                    portKnockDelayMs = knockDelay,
                )
            }
            "VNC" -> ConnectionProfile(
                label = label,
                host = host,
                port = port,
                username = "",
                connectionType = "VNC",
                vncPort = port,
                vncUsername = args.optString("vncUsername").ifBlank { null },
                vncPassword = args.optString("vncPassword").ifBlank { null },
                vncSshForward = false,
                tunnelConfigId = tunnelConfigId,
                portKnockSequence = knockSequence,
                portKnockDelayMs = knockDelay,
            )
            "RDP" -> {
                val rdpUser = args.optString("rdpUsername").ifBlank {
                    throw IllegalArgumentException("rdpUsername required for RDP")
                }
                ConnectionProfile(
                    label = label,
                    host = host,
                    port = port,
                    username = rdpUser,
                    connectionType = "RDP",
                    rdpPort = port,
                    rdpUsername = rdpUser,
                    rdpPassword = args.optString("rdpPassword").ifBlank { null },
                    rdpDomain = args.optString("rdpDomain").ifBlank { null },
                    rdpSshForward = false,
                    tunnelConfigId = tunnelConfigId,
                    portKnockSequence = knockSequence,
                    portKnockDelayMs = knockDelay,
                )
            }
            else -> error("unreachable")
        }
        connectionRepository.save(profile)
        return JSONObject().apply {
            put("id", profile.id)
            put("label", profile.label)
            put("connectionType", profile.connectionType)
            put("host", profile.host)
            put("port", profile.port)
            put("tunnelConfigId", profile.tunnelConfigId ?: JSONObject.NULL)
            if (profile.tunnelOnly) put("tunnelOnly", true)
            if (knockSequence != null) {
                put("portKnockSequence", knockSequence)
                put("portKnockDelayMs", knockDelay)
            }
        }
    }

    private suspend fun setPortKnock(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val existing = connectionRepository.getById(profileId)
            ?: throw IllegalArgumentException("profile $profileId not found")
        val rawSeq = args.optString("portKnockSequence")
        val delay = if (args.has("portKnockDelayMs")) {
            args.optInt("portKnockDelayMs", existing.portKnockDelayMs)
        } else {
            existing.portKnockDelayMs
        }
        val parsed = sh.haven.core.knock.KnockSequence.parse(rawSeq, delay)
            .getOrElse { throw IllegalArgumentException("portKnockSequence: ${it.message}") }
        val newSeq = if (parsed != null) rawSeq.trim() else null
        val updated = existing.copy(
            portKnockSequence = newSeq,
            portKnockDelayMs = delay,
        )
        connectionRepository.save(updated)
        return JSONObject().apply {
            put("id", updated.id)
            put("label", updated.label)
            put("portKnockSequence", newSeq ?: JSONObject.NULL)
            put("portKnockDelayMs", delay)
            put("steps", parsed?.steps?.size ?: 0)
        }
    }

    private suspend fun testPortKnock(args: JSONObject): JSONObject {
        val host = args.optString("host").ifBlank {
            throw IllegalArgumentException("host required")
        }
        val rawSeq = args.optString("portKnockSequence").ifBlank {
            throw IllegalArgumentException("portKnockSequence required")
        }
        val delay = if (args.has("portKnockDelayMs")) args.optInt("portKnockDelayMs", 100) else 100
        val seq = sh.haven.core.knock.KnockSequence.parse(rawSeq, delay)
            .getOrElse { throw IllegalArgumentException("portKnockSequence: ${it.message}") }
            ?: throw IllegalArgumentException("portKnockSequence parsed to empty")
        val result = portKnocker.knock(host, seq)
        return JSONObject().apply {
            put("host", host)
            put("sequence", seq.format())
            put("ok", result.ok)
            put("sentSteps", result.sentSteps)
            put("totalSteps", seq.steps.size)
            put("durationMs", result.totalDurationMs)
            put("error", result.error?.message ?: JSONObject.NULL)
        }
    }

    private suspend fun getConnectionLog(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val limit = args.optInt("limit", 10).coerceIn(1, 100)
        // ConnectionLogRepository observes a Flow — take the latest snapshot
        // and slice it down to the requested limit. Newest first.
        val entries = connectionLogRepository.observeForProfile(profileId, limit)
            .first()
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("timestamp", e.timestamp)
                put("status", e.status.name)
                put("durationMs", e.durationMs)
                put("details", e.details ?: JSONObject.NULL)
                put("verboseLog", e.verboseLog ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("profileId", profileId)
            put("count", entries.size)
            put("entries", arr)
        }
    }

    private suspend fun deleteConnection(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val existing = connectionRepository.getById(profileId)
            ?: return JSONObject().apply { put("deleted", false); put("reason", "not found") }
        // Tear down any live session for this profile (incl. its VNC/RDP
        // Desktop tab via the lease), then drop tunnel refcount, then delete.
        sessionManagerRegistry.disconnectProfile(profileId)
        sshSessionManager.teardownTunnelDependent(profileId)
        tunnelManager.release(profileId)
        connectionRepository.delete(profileId)
        return JSONObject().apply {
            put("deleted", true)
            put("id", profileId)
            put("label", existing.label)
        }
    }

    private suspend fun connectProfile(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val profile = connectionRepository.getById(profileId)
            ?: throw IllegalArgumentException("profile $profileId not found")
        agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.ConnectProfile(profileId)
        )
        return JSONObject().apply {
            put("profileId", profileId)
            put("label", profile.label)
            put("dispatched", true)
            put(
                "note",
                "Connect dispatched asynchronously through the same path a UI tap uses. Poll list_sessions for status."
            )
        }
    }

    companion object {
        /**
         * Port the MCP HTTP server binds on the device and the matching
         * `-R` forward Haven installs on the SSH session. Kept in sync
         * with `feature/connections`'s MCP_REVERSE_TUNNEL_PORT (`#161`).
         */
        internal const val MCP_REVERSE_TUNNEL_PORT = 8730

        /**
         * Best-effort fallback regex matching the trailing
         * prompt glyph of common interactive shells and REPLs
         * at end-of-line. Covers `$` (bash/sh/zsh user), `#`
         * (root), `%` (csh), `>` (cmd.exe-style and traditional
         * minimal), and `❯` (Claude Code, fish, starship).
         * Multiline + ANSI-stripped before matching in
         * [TerminalInputQueue]; the watcher's tail-only scope
         * (last 512 chars of stripped scrollback) keeps the
         * character class from matching `$` redirects or `>`
         * in earlier output.
         *
         * Callers should supply their own `promptPattern` when
         * they know what's running on the other end — e.g.
         * `\[y/N\]\s*$` for an install script,
         * `Password:\s*$` for a password prompt. The default
         * exists for the convenience of the "drive an
         * interactive shell or REPL" case where the exact
         * prompt isn't pinned in advance.
         */
        internal const val DEFAULT_PROMPT_PATTERN = "[\\\$#%>❯]\\s*\$"

        /**
         * Default key bytes sent after the queued text. `\r` (CR) is
         * what TTYs in cooked mode translate to NL, and what programs
         * in raw mode (Claude Code, vim, less, fzf, readline-based
         * shells) read as the Enter key. Callers can override:
         * - `\n` (LF) for line-buffered programs reading stdin
         *   directly without a tty.
         * - `""` (empty) to leave the text in the input buffer
         *   without submitting — e.g. pre-filling a prompt the user
         *   will edit further before pressing Enter themselves.
         */
        internal const val DEFAULT_SUBMIT_KEY = "\r"
    }

    // --- proot / desktop environment tool implementations (issue #162 Phase 3b) ---

    private val prootManager get() = localSessionManager.prootManager

    /** Background scope for fire-and-forget installs. Survives the tool
     * call return so the agent can poll progress; SupervisorJob keeps
     * one failing install from cancelling siblings. */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Live background `run_in_proot` jobs, keyed by jobId. */
    private class ProotJob {
        val output = StringBuilder()
        @Volatile var running = true
        @Volatile var exitCode: Int? = null
    }
    private val prootJobs = java.util.concurrent.ConcurrentHashMap<String, ProotJob>()

    private fun distroToJson(
        d: sh.haven.core.local.proot.Distro,
        installed: Boolean,
        active: Boolean,
        bytesOnDisk: Long,
    ): JSONObject = JSONObject().apply {
        put("id", d.id)
        put("label", d.label)
        put("family", d.family.name)
        put("installed", installed)
        put("active", active)
        put("sizeEstimateMb", d.sizeEstimateMb)
        put("bytesOnDisk", bytesOnDisk)
        put("baselinePackages", JSONArray().apply {
            d.baselinePackages.forEach { put(it) }
        })
        put("postExtractHookIds", JSONArray().apply {
            d.postExtractHooks.forEach { put(it.id) }
        })
        put("rootfsArches", JSONArray().apply {
            d.rootfsSources.keys.forEach { put(it.abi) }
        })
        // Which desktops are installed on THIS distro's rootfs — read
        // per-distro so the cross-distro picture is visible without
        // switching the active distro. Empty for non-installed distros.
        put("installedDesktops", JSONArray().apply {
            prootManager.installedDesktopsFor(d.id).forEach { put(it.spec.id) }
        })
    }

    private fun rootfsBytesOnDisk(distroId: String): Long {
        // Honor ProotManager's legacy-path fallback (alpine-3.21 → alpine).
        val dir = prootManager.rootfsDirFor(distroId)
        if (!dir.exists()) return 0L
        // Cheap: just the top-level directory size — accurate to within
        // the order of magnitude. Recursive du would block several
        // seconds for a 100-MB rootfs.
        return runCatching {
            java.nio.file.Files.walk(dir.toPath()).use { stream ->
                stream.filter { java.nio.file.Files.isRegularFile(it) }
                    .mapToLong {
                        runCatching { java.nio.file.Files.size(it) }.getOrDefault(0L)
                    }
                    .sum()
            }
        }.getOrDefault(0L)
    }

    private fun desktopToJson(
        de: sh.haven.core.local.ProotManager.DesktopEnvironment,
        activeFamily: sh.haven.core.local.proot.PackageFamily,
        installed: Boolean,
        running: Boolean,
    ): JSONObject = JSONObject().apply {
        put("id", de.spec.id)
        put("label", de.label)
        put("sizeEstimateMb", de.spec.sizeEstimateMb)
        put("installed", installed)
        put("running", running)
        put("isNative", de.isNative)
        put("isWayland", de.isWayland)
        put("verifyBinary", de.spec.verifyBinary)
        put("compatibility", de.spec.compatibilityOn(activeFamily).name)
        de.spec.compatibilityNoteOn(activeFamily)?.let { put("compatibilityNote", it) }
        val pkgs = de.spec.packagesPerFamily[activeFamily]
        if (pkgs != null) {
            put("packages", JSONArray().apply { pkgs.forEach { put(it) } })
        } else {
            put("packages", JSONObject.NULL)
        }
        put("packageFamilies", JSONArray().apply {
            de.spec.packagesPerFamily.keys.forEach { put(it.name) }
        })
    }

    private fun osSetupStateToJson(
        state: sh.haven.core.local.ProotManager.SetupState,
    ): JSONObject = JSONObject().apply {
        when (state) {
            is sh.haven.core.local.ProotManager.SetupState.NotInstalled -> {
                put("phase", "NotInstalled")
            }
            is sh.haven.core.local.ProotManager.SetupState.Downloading -> {
                put("phase", "Downloading")
                put("percent", state.progress)
            }
            is sh.haven.core.local.ProotManager.SetupState.Extracting -> {
                put("phase", "Extracting")
            }
            is sh.haven.core.local.ProotManager.SetupState.Initializing -> {
                put("phase", "Initializing")
                put("step", state.step)
            }
            is sh.haven.core.local.ProotManager.SetupState.Ready -> {
                put("phase", "Ready")
            }
            is sh.haven.core.local.ProotManager.SetupState.Error -> {
                put("phase", "Error")
                put("errorPhase", state.phase.name)
                put("errorMessage", state.message)
                if (state.logTail.isNotEmpty()) put("errorTail", state.logTail)
            }
        }
    }

    private fun desktopSetupStateToJson(
        state: sh.haven.core.local.ProotManager.DesktopSetupState,
    ): JSONObject = JSONObject().apply {
        when (state) {
            is sh.haven.core.local.ProotManager.DesktopSetupState.Idle ->
                put("phase", "Idle")
            is sh.haven.core.local.ProotManager.DesktopSetupState.Installing -> {
                put("phase", "Installing")
                put("step", state.step)
            }
            is sh.haven.core.local.ProotManager.DesktopSetupState.Complete ->
                put("phase", "Complete")
            is sh.haven.core.local.ProotManager.DesktopSetupState.Error -> {
                put("phase", "Error")
                put("errorPhase", state.phase.name)
                put("errorMessage", state.message)
                if (state.logTail.isNotEmpty()) put("errorTail", state.logTail)
            }
        }
    }

    private fun installLogEventToJson(
        e: sh.haven.core.data.db.entities.ProotInstallLog,
    ): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("timestamp", e.timestamp)
        put("distroId", e.distroId)
        put("phase", e.phase)
        e.deId?.let { put("deId", it) }
        if (e.exit != null) put("exit", e.exit) else put("exit", JSONObject.NULL)
        put("ok", e.ok)
        e.message?.let { put("message", it) }
        e.logTail?.let { put("logTail", it) }
    }

    private suspend fun inspectProot(args: JSONObject): JSONObject {
        val eventLimit = args.optInt("eventLimit", 50).coerceIn(1, 500)
        val pm = prootManager
        val active = pm.activeDistro
        val installed = pm.installedDistros.map { it.id }.toSet()
        val installedDes = pm.installedDesktops
        val runningDes = localSessionManager.desktopManager.desktops.value.keys

        val distrosJson = JSONArray().apply {
            sh.haven.core.local.proot.DistroCatalog.all.forEach { d ->
                put(
                    distroToJson(
                        d = d,
                        installed = d.id in installed,
                        active = d.id == active.id,
                        bytesOnDisk = if (d.id in installed) rootfsBytesOnDisk(d.id) else 0L,
                    ),
                )
            }
        }
        val desktopsJson = JSONArray().apply {
            sh.haven.core.local.ProotManager.DesktopEnvironment.entries
                .filter { !it.hidden }
                .filter { active.family in it.spec.packagesPerFamily.keys }
                .forEach { de ->
                    put(
                        desktopToJson(
                            de = de,
                            activeFamily = active.family,
                            installed = de in installedDes,
                            running = de in runningDes,
                        ),
                    )
                }
        }
        val events = prootInstallLogRepository.querySince(
            distroId = null,
            sinceMs = 0L,
            limit = eventLimit,
        )
        val eventsJson = JSONArray().apply {
            // querySince returns ASC; reverse so the most recent event is at index 0
            events.asReversed().forEach { put(installLogEventToJson(it)) }
        }

        return JSONObject().apply {
            put("activeDistroId", active.id)
            put("distros", distrosJson)
            put("desktopEnvironments", desktopsJson)
            put("osSetupState", osSetupStateToJson(pm.state.value))
            put("desktopSetupState", desktopSetupStateToJson(pm.desktopState.value))
            put("recentInstallLog", eventsJson)
        }
    }

    private fun listDistros(): JSONObject {
        val pm = prootManager
        val active = pm.activeDistro
        val installed = pm.installedDistros.map { it.id }.toSet()
        val arr = JSONArray().apply {
            sh.haven.core.local.proot.DistroCatalog.all.forEach { d ->
                put(
                    distroToJson(
                        d = d,
                        installed = d.id in installed,
                        active = d.id == active.id,
                        bytesOnDisk = if (d.id in installed) rootfsBytesOnDisk(d.id) else 0L,
                    ),
                )
            }
        }
        return JSONObject().apply {
            put("activeDistroId", active.id)
            put("count", arr.length())
            put("distros", arr)
        }
    }

    private fun listDesktopEnvironments(): JSONObject {
        val pm = prootManager
        val active = pm.activeDistro
        val installedDes = pm.installedDesktops
        val runningDes = localSessionManager.desktopManager.desktops.value.keys
        val arr = JSONArray().apply {
            sh.haven.core.local.ProotManager.DesktopEnvironment.entries
                .filter { !it.hidden }
                .filter { active.family in it.spec.packagesPerFamily.keys }
                .forEach { de ->
                    put(
                        desktopToJson(
                            de = de,
                            activeFamily = active.family,
                            installed = de in installedDes,
                            running = de in runningDes,
                        ),
                    )
                }
        }
        return JSONObject().apply {
            put("activeDistroId", active.id)
            put("activeFamily", active.family.name)
            put("count", arr.length())
            put("desktopEnvironments", arr)
        }
    }

    private suspend fun installDistro(args: JSONObject): JSONObject {
        val distroId = args.optString("distroId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "distroId is required")
        val target = sh.haven.core.local.proot.DistroCatalog.lookup(distroId)
            ?: throw McpError(-32602, "Unknown distroId: $distroId")
        val pm = prootManager
        val wasInstalled = target.id in pm.installedDistros.map { it.id }
        if (pm.activeDistroId != target.id) {
            pm.setActiveDistroId(target.id)
        }
        // Launch the install on the IO dispatcher and return immediately.
        // Callers poll via inspect_proot.osSetupState — matches the UI
        // pattern of "tap Install, watch the progress block update".
        backgroundScope.launch {
            try {
                pm.installRootfs()
            } catch (_: Exception) {
                // The catch in installRootfs already pushes Error state.
            }
        }
        return JSONObject().apply {
            put("distroId", target.id)
            put("label", target.label)
            put("alreadyInstalled", wasInstalled)
            put("status", "started")
            put("poll", "inspect_proot.osSetupState")
        }
    }

    private suspend fun deleteDistroTool(args: JSONObject): JSONObject {
        val distroId = args.optString("distroId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "distroId is required")
        val target = sh.haven.core.local.proot.DistroCatalog.lookup(distroId)
            ?: throw McpError(-32602, "Unknown distroId: $distroId")
        val pm = prootManager
        // Stop any DEs on this distro before yanking the rootfs.
        if (pm.activeDistroId == target.id) {
            localSessionManager.desktopManager.stopAll()
        }
        pm.deleteDistro(target.id)
        // Drop install-log rows for this distro too — the rootfs is gone,
        // its events would just be confusing on a re-install.
        prootInstallLogRepository.deleteForDistro(target.id)
        return JSONObject().apply {
            put("distroId", target.id)
            put("label", target.label)
            put("status", "deleted")
        }
    }

    private suspend fun installDesktopTool(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
            .firstOrNull { it.spec.id == deId }
            ?: throw McpError(-32602, "Unknown deId: $deId")
        val vncPassword = args.optString("vncPassword", "")
        val pm = prootManager
        val active = pm.activeDistro
        if (active.family !in de.spec.packagesPerFamily.keys) {
            throw McpError(
                -32602,
                "${de.label} has no package list for ${active.family} — supported: " +
                    "${de.spec.packagesPerFamily.keys}",
            )
        }
        backgroundScope.launch {
            try {
                pm.setupDesktop(vncPassword, de)
            } catch (_: Exception) {
                // setupDesktop's catch already pushes Error state.
            }
        }
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("label", de.label)
            put("activeDistroId", active.id)
            put("compatibility", de.spec.compatibilityOn(active.family).name)
            de.spec.compatibilityNoteOn(active.family)?.let { put("compatibilityNote", it) }
            put("status", "started")
            put("poll", "inspect_proot.desktopSetupState")
        }
    }

    private suspend fun uninstallDesktopTool(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
            .firstOrNull { it.spec.id == deId }
            ?: throw McpError(-32602, "Unknown deId: $deId")
        val pm = prootManager
        localSessionManager.desktopManager.stopDesktop(de)
        pm.uninstallDesktop(de)
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("label", de.label)
            put("status", "uninstalled")
        }
    }

    private suspend fun startDesktopTool(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
            .firstOrNull { it.spec.id == deId }
            ?: throw McpError(-32602, "Unknown deId: $deId")
        if (de !in prootManager.installedDesktops) {
            throw McpError(
                -32602,
                "${de.label} is not installed — call install_desktop first.",
            )
        }
        val dm = localSessionManager.desktopManager
        dm.startDesktop(de)
        // DesktopManager.startDesktop stays at STARTING until a caller
        // confirms the VNC port is up and calls markRunning — the UI path
        // (DesktopViewModel.startDesktop) does this, but the MCP path
        // didn't, so MCP-started desktops sat at STARTING forever (task
        // #23). Do the same port-poll → markRunning here so the state
        // finalizes. Native (labwc) self-finalizes RUNNING via the JNI
        // bridge, so skip the poll for it. We don't open an in-app VNC
        // viewer (that's UI-only; McpTools has no DesktopViewModel) — the
        // returned vncPort lets an external VNC client connect.
        if (!de.isNative) {
            val port = dm.getVncPort(de) ?: 5901
            withContext(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline) {
                    if (dm.desktops.value[de]?.state ==
                        sh.haven.core.local.DesktopManager.DesktopState.ERROR
                    ) break
                    try {
                        java.net.Socket("127.0.0.1", port).close()
                        dm.markRunning(de)
                        break
                    } catch (_: Exception) {
                        kotlinx.coroutines.delay(500)
                    }
                }
            }
        }
        val instance = dm.desktops.value[de]
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("label", de.label)
            put("state", instance?.state?.name ?: "UNKNOWN")
            put("displayNumber", instance?.displayNumber ?: -1)
            put("vncPort", instance?.vncPort ?: -1)
            instance?.errorMessage?.let { put("errorMessage", it) }
            put("launchKind", de.spec.launch::class.simpleName ?: "unknown")
            put("poll", "list_desktop_environments[].running")
        }
    }

    private fun stopDesktopTool(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
            .firstOrNull { it.spec.id == deId }
            ?: throw McpError(-32602, "Unknown deId: $deId")
        localSessionManager.desktopManager.stopDesktop(de)
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("label", de.label)
            put("status", "stopped")
        }
    }

    private fun readDesktopLog(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
        val maxChars = args.optInt("maxChars", 4000).coerceIn(200, 50000)
        val dm = localSessionManager.desktopManager
        val instances = dm.desktops.value
        val targets = if (deId != null) {
            val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
                .firstOrNull { it.spec.id == deId }
                ?: throw McpError(-32602, "Unknown deId: $deId")
            listOf(de)
        } else {
            instances.keys.toList()
        }
        val arr = JSONArray()
        for (de in targets) {
            val inst = instances[de]
            val compLog = dm.compositorLogFor(de)
                ?.let { if (it.length > maxChars) it.takeLast(maxChars) else it }
            arr.put(JSONObject().apply {
                put("deId", de.spec.id)
                put("label", de.label)
                put("state", inst?.state?.name ?: "STOPPED")
                put("displayNumber", inst?.displayNumber ?: -1)
                put("vncPort", inst?.vncPort ?: -1)
                put("launchKind", de.spec.launch::class.simpleName ?: "unknown")
                inst?.errorMessage?.let { put("errorMessage", it) }
                put("capturedOutput", JSONArray(dm.capturedOutputFor(de)))
                put("compositorLog", compLog ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("count", arr.length())
            put("desktops", arr)
        }
    }

    private fun desktopByIdOrThrow(deId: String): sh.haven.core.local.ProotManager.DesktopEnvironment =
        sh.haven.core.local.ProotManager.DesktopEnvironment.entries
            .firstOrNull { it.spec.id == deId }
            ?: throw McpError(-32602, "Unknown deId: $deId")

    private suspend fun listDesktopWindows(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = desktopByIdOrThrow(deId)
        val windows = try {
            localSessionManager.desktopManager.listWindows(de)
        } catch (e: Exception) {
            throw McpError(-32603, e.message ?: "Window enumeration failed")
        }
        val arr = JSONArray()
        windows.forEach { w ->
            arr.put(JSONObject().apply {
                put("id", w.id)
                put("title", w.title)
                put("x", w.x)
                put("y", w.y)
                put("width", w.width)
                put("height", w.height)
            })
        }
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("count", arr.length())
            put("windows", arr)
        }
    }

    private suspend fun captureDesktop(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = desktopByIdOrThrow(deId)
        val dm = localSessionManager.desktopManager
        val windowId = args.optString("windowId").takeIf { it.isNotBlank() }
        val maxWidth = args.optInt("maxWidth", 1024).coerceIn(160, 4096)
        val format = if (args.optString("format", "jpeg").lowercase() == "png") "png" else "jpeg"

        // When a window is requested, resolve its geometry first so we can
        // crop the full-screen capture to it app-side (a single reliable
        // capture path beats per-window-id capture on bare Xvnc).
        var crop: IntArray? = null
        var windowTitle: String? = null
        if (windowId != null) {
            val win = try {
                dm.listWindows(de).firstOrNull { it.id == windowId }
            } catch (e: Exception) {
                throw McpError(-32603, e.message ?: "Window lookup failed")
            } ?: throw McpError(
                -32602,
                "Window '$windowId' not found on '${de.spec.id}' — call list_desktop_windows first",
            )
            crop = intArrayOf(win.x, win.y, win.width, win.height)
            windowTitle = win.title
        }

        val png = try {
            dm.capture(de)
        } catch (e: Exception) {
            throw McpError(-32603, e.message ?: "Capture failed")
        }
        val (b64, w, h) = withContext(Dispatchers.Default) {
            encodeCapture(png, crop, maxWidth, format)
        }
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("width", w)
            put("height", h)
            put("format", format)
            put("source", "guest")
            windowId?.let { put("windowId", it) }
            windowTitle?.let { put("windowTitle", it) }
            // Reserved keys: McpServer lifts these into an MCP image content
            // block and strips them from structuredContent / the text echo.
            put("__imageBase64", b64)
            put("__mimeType", if (format == "jpeg") "image/jpeg" else "image/png")
        }
    }

    /**
     * Decode captured PNG bytes, optionally crop to [crop] (x,y,w,h),
     * downscale to [maxWidth], and re-encode. Returns (base64, width,
     * height). Crop rect is clamped to the bitmap bounds so a window that
     * extends past the screen edge still yields a valid image.
     */
    private fun encodeCapture(
        pngBytes: ByteArray,
        crop: IntArray?,
        maxWidth: Int,
        format: String,
    ): Triple<String, Int, Int> {
        var bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            ?: throw McpError(-32603, "Could not decode captured image")
        if (crop != null) {
            val x = crop[0].coerceIn(0, bmp.width - 1)
            val y = crop[1].coerceIn(0, bmp.height - 1)
            val cw = crop[2].coerceIn(1, bmp.width - x)
            val ch = crop[3].coerceIn(1, bmp.height - y)
            bmp = Bitmap.createBitmap(bmp, x, y, cw, ch)
        }
        if (maxWidth in 1 until bmp.width) {
            val nh = (bmp.height.toFloat() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
            bmp = Bitmap.createScaledBitmap(bmp, maxWidth, nh, true)
        }
        val out = java.io.ByteArrayOutputStream()
        if (format == "jpeg") {
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        } else {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return Triple(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), bmp.width, bmp.height)
    }

    private suspend fun runInProot(args: JSONObject): JSONObject {
        val maxOut = args.optInt("maxOutputChars", 8000).coerceIn(200, 200_000)
        fun tail(s: String) = if (s.length > maxOut) s.takeLast(maxOut) else s

        // Poll an existing background job.
        val pollId = args.optString("jobId").takeIf { it.isNotBlank() }
        if (pollId != null) {
            val job = prootJobs[pollId]
                ?: throw McpError(-32602, "Unknown jobId: $pollId (it may have been lost on app restart)")
            val out = synchronized(job.output) { job.output.toString() }
            return JSONObject().apply {
                put("jobId", pollId)
                put("status", if (job.running) "running" else "done")
                if (!job.running) put("exitCode", job.exitCode ?: -1)
                put("output", tail(out))
            }
        }

        val command = args.optString("command").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "command is required (or pass jobId to poll a background job)")

        if (!prootManager.isRootfsInstalled) {
            throw McpError(-32603, "Active distro '${prootManager.activeDistroId}' has no installed rootfs")
        }

        // Background mode: stream the process output into a job buffer and
        // return immediately so long installs don't hold the request open.
        if (args.optBoolean("background", false)) {
            val jobId = java.util.UUID.randomUUID().toString()
            val job = ProotJob()
            prootJobs[jobId] = job
            backgroundScope.launch {
                try {
                    val proc = prootManager.startCommandInProot(command)
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(job.output) { job.output.append(line).append('\n') }
                    }
                    job.exitCode = proc.waitFor()
                } catch (e: Exception) {
                    synchronized(job.output) { job.output.append("\n[run_in_proot error] ${e.message}\n") }
                    job.exitCode = -1
                } finally {
                    job.running = false
                }
            }
            return JSONObject().apply {
                put("jobId", jobId)
                put("status", "running")
                put("activeDistroId", prootManager.activeDistroId)
                put("poll", "run_in_proot with jobId=$jobId")
            }
        }

        // Synchronous mode — blocks until the command exits.
        val (out, code) = prootManager.runCommandInProot(command)
        return JSONObject().apply {
            put("activeDistroId", prootManager.activeDistroId)
            put("exitCode", code)
            put("output", tail(out))
        }
    }

    private suspend fun getProotInstallLog(args: JSONObject): JSONObject {
        val distroId = args.optString("distroId").takeIf { it.isNotEmpty() }
        val sinceMs = args.optLong("sinceMs", 0L)
        val limit = args.optInt("limit", 100).coerceIn(1, 1000)
        val events = prootInstallLogRepository.querySince(
            distroId = distroId,
            sinceMs = sinceMs,
            limit = limit,
        )
        val arr = JSONArray().apply {
            events.forEach { put(installLogEventToJson(it)) }
        }
        return JSONObject().apply {
            put("count", events.size)
            distroId?.let { put("distroId", it) }
            put("sinceMs", sinceMs)
            put("events", arr)
        }
    }
}

/**
 * Consent gating metadata for a single tool, looked up by [McpServer]
 * before dispatching the call so destructive tools can prompt the user
 * with an action-specific summary.
 *
 * Read-only and tap-equivalent tools use [ConsentLevel.NEVER] and skip
 * the prompt entirely; the [summary] still has to be a function so the
 * registry shape stays uniform but is unused in that branch.
 */
internal data class ToolConsent(
    val level: ConsentLevel,
    val summary: (JSONObject) -> String,
)

private class ToolHandler(
    val description: String,
    val inputSchema: JSONObject,
    /** Consent level the dispatcher applies before invoking [invoke]. */
    val consentLevel: ConsentLevel = ConsentLevel.NEVER,
    /**
     * Builds the human-readable one-liner shown in the consent prompt
     * for non-NEVER tools. Default is just the tool name; per-tool
     * registrations should override with something specific so the user
     * understands what they're approving.
     */
    val summarise: (JSONObject) -> String = { "tool call" },
    private val invoke: suspend (JSONObject) -> JSONObject,
) {
    suspend fun handle(args: JSONObject): JSONObject = invoke(args)
}

/** JSON Schema for tools that take no arguments. */
private fun emptyObjectSchema(): JSONObject = JSONObject().apply {
    put("type", "object")
    put("properties", JSONObject())
}
