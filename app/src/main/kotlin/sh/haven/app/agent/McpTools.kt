package sh.haven.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
            description = "Read a Haven user preference by key. Whitelisted keys: terminal_scrollback_rows, terminal_tap_to_position_cursor, terminal_font_size, terminal_color_scheme, mouse_input_enabled, mouse_drag_selects, terminal_right_click. Returns { key, value } where value's type follows the preference's type (int / boolean / string).",
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
            consentLevel = ConsentLevel.EVERY_CALL,
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
            description = "Write a Haven user preference. Whitelisted keys (and their types): terminal_scrollback_rows (int 100..25000), terminal_tap_to_position_cursor (bool), terminal_font_size (int 8..32), mouse_input_enabled (bool), mouse_drag_selects (bool), terminal_right_click (bool). Returns { key, value }.",
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
            description = "Download an APK from a URL and install it on the device via Shizuku-driven `pm install`. Useful for agent-driven self-update or sideloading over VPN where wireless ADB isn't reachable. Requires Shizuku running and granted to Haven. Installation happens silently from the user's perspective once consent is given — no system installer dialog.",
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

        "set_terminal_font_from_url" to ToolHandler(
            description = "Download a TTF/OTF font from a URL, validate it, install it as Haven's terminal font (replacing any prior custom font), and return the saved path. Useful for agent-driven Nerd Font installs (#123). Requires the URL to be reachable from the device — use a tunneled URL (via add_port_forward LOCAL) to expose a workstation HTTP server back through the existing SSH session.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "http(s) URL pointing at a TTF or OTF file. Should resolve to font bytes (no HTML wrapper).")
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
            description = "Create a saved connection profile. Supports connectionType=SSH, SMB, VNC, RDP. SSH-family fields: username (required), password (optional, stored). SMB: smbShare (required), username + password, smbDomain. VNC: vncUsername, vncPassword, vncPort. RDP: rdpUsername (required), rdpPassword, rdpDomain, rdpPort. The new profile id is returned for follow-up calls (set_profile_routing, connect_profile). For Reticulum / rclone / local create the profile in the UI — those paths need OAuth / destination-hash flows the agent can't drive.",
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

    /** Call a tool by name. Throws [McpError] for bad input. */
    suspend fun call(name: String, arguments: JSONObject): JSONObject {
        val handler = tools[name] ?: throw McpError(-32602, "Unknown tool: $name")
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
        val bytes = sshSessionManager.readAgentScrollback(sessionId, capped)
            ?: localSessionManager.readAgentScrollback(sessionId, capped)
            ?: throw McpError(
                -32603,
                "No scrollback available for session $sessionId — open a terminal tab on this session first",
            )
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("byteCount", bytes.size)
            // UTF-8 with malformed-replacement so any partial codepoint
            // at the buffer head doesn't poison the whole response.
            put("text", String(bytes, Charsets.UTF_8))
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
        // Match ConnectionsViewModel.disconnect — release any auto-opened
        // SSH-tunnel dependent (#121) and the WG / Tailscale refcount
        // (#149). Bypassing these from the MCP path was the original gap
        // — without them, a profile disconnected through the agent path
        // left its tunnel handle live, and live_tunnels surfaced the
        // bogus "still depending" state.
        sshSessionManager.releaseTunnelDependent(profileId)
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
        // via Shizuku. Cap at 200 MB so a misdirected URL can't fill
        // the device storage; full Haven release APKs are ~80–100 MB,
        // so the cap leaves headroom.
        val maxBytes = 200L * 1024 * 1024
        val cacheDir = File(context.cacheDir, "agent-install").apply { mkdirs() }
        val target = File(cacheDir, "haven-agent-install-${System.currentTimeMillis()}.apk")
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
                            if (total > maxBytes) {
                                target.delete()
                                throw McpError(-32603, "APK exceeds ${maxBytes / (1024 * 1024)} MiB cap")
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
        val firstFour = target.inputStream().use { stream ->
            ByteArray(4).also { stream.read(it) }
        }
        if (firstFour[0] != 0x50.toByte() || firstFour[1] != 0x4B.toByte()) {
            target.delete()
            throw McpError(
                -32603,
                "Downloaded $written bytes but they don't start with the zip/APK magic — wrong URL?",
            )
        }
        // Hand off to Shizuku — `pm install -S <size>` reads APK bytes
        // from stdin. Working directory of the shell process doesn't
        // need to see our cache dir; we pipe FileInputStream directly.
        val result = try {
            sh.haven.core.local.WaylandSocketHelper.execAsShizukuWithStdin(
                cmd = "pm install -S $written",
                stdin = target.inputStream(),
            )
        } catch (e: IllegalStateException) {
            target.delete()
            throw McpError(
                -32603,
                "${e.message}. Install Shizuku from https://shizuku.rikka.app and grant Haven permission, then retry.",
            )
        } catch (e: Exception) {
            target.delete()
            throw McpError(-32603, "Shizuku exec failed: ${e.message}")
        } finally {
            // Best-effort cleanup of the staged APK regardless of outcome.
            target.delete()
        }
        if (result.exitCode != 0 || !result.output.contains("Success")) {
            throw McpError(
                -32603,
                "pm install exited ${result.exitCode}: ${result.output.take(500)}",
            )
        }
        JSONObject().apply {
            put("installed", true)
            put("bytesDownloaded", written)
            put("output", result.output)
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
        JSONObject().apply {
            put("sessionId", sessionId)
            put("profileId", profile.id)
            put("label", profile.label)
            put("reused", alive != null)
            put("plain", plain)
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
        // Tear down any live session for this profile, then drop tunnel
        // refcount, then delete.
        sessionManagerRegistry.disconnectProfile(profileId)
        sshSessionManager.releaseTunnelDependent(profileId)
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
