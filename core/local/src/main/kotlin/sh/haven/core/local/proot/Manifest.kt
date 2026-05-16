package sh.haven.core.local.proot

import android.os.Build

/**
 * Distro / DE manifest data model — Phase 1 of issue #162.
 *
 * The current `ProotManager.DesktopEnvironment` enum stays in place
 * as the public API. This file adds a parallel data-class catalog
 * that the internals (DesktopManager launch dispatch, PackageOps
 * routing) consult. Phase 2 adds Debian + APT and wires the UI to
 * pick a distro; Phase 5 moves these catalogs to JSON on disk.
 *
 * Naming note: the data class is [DesktopEnvironmentSpec] (not
 * `DesktopEnvironment`) so it doesn't clash with the existing enum.
 * Each enum entry exposes a `.spec` accessor that returns the
 * corresponding spec from [DesktopCatalog].
 */

/** Device ABI the rootfs tarballs are pinned against. */
enum class Arch(val abi: String) {
    AARCH64("arm64-v8a"),
    X86_64("x86_64");

    companion object {
        /** Detect the current device arch, or null if unsupported. */
        fun current(): Arch? = when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> AARCH64
            Build.SUPPORTED_ABIS.contains("x86_64") -> X86_64
            else -> null
        }
    }
}

/** Package management family. Each distro picks exactly one. */
enum class PackageFamily {
    APK,      // Alpine, postmarketOS
    APT,      // Debian, Ubuntu, Kali, Parrot
    PACMAN,   // Arch, Manjaro
    XBPS,     // Void
    NIX,      // Nix, NixOS
}

/** Tarball format for a rootfs source. */
enum class RootfsFormat { TAR_GZ, TAR_XZ, TAR_ZSTD }

/**
 * A rootfs download source — pinned URL + sha256 for one arch of
 * one distro. SHA-256 is required: rootfs integrity is a security
 * boundary, not a UX concern.
 *
 * [stripComponents] mirrors `tar --strip-components=N` — used when
 * a tarball wraps the rootfs in a top-level directory (proot-distro
 * does this; Alpine's minirootfs does not). Set to 1 for the
 * `<distro>-<arch>/` wrapper convention; leave at 0 for tarballs
 * whose files are already at the top level.
 */
data class RootfsSource(
    val url: String,
    val sha256: String,
    val format: RootfsFormat = RootfsFormat.TAR_GZ,
    val stripComponents: Int = 0,
)

/**
 * Post-extract hook — small shell snippets that run inside the
 * freshly-extracted rootfs before any package operations. Used for
 * distro-specific bootstrap (e.g. `pacman-key --init`, writing
 * `/etc/apt/sources.list`). Phase 1 ships with an empty list for
 * Alpine.
 */
data class RootfsHook(
    val id: String,
    val command: String,
)

/**
 * Launch dispatch — how a DE actually runs once its packages are
 * installed. Maps 1:1 to the branches in `DesktopManager`.
 *
 *  - [X11Vnc]: Xvnc :N + a startup script. Today's Openbox/Xfce4.
 *  - [NativeCompositor]: labwc via the JNI bridge. Today's
 *    WAYLAND_NATIVE path. GPU-accelerated via virgl. Singleton.
 *  - [NestedWayland]: a headless wlroots compositor running inside
 *    proot, exposed via `wayvnc` on the same VNC port the X11
 *    branch uses. Reserved for Phase 4 (Hyprland / niri / Sway).
 */
sealed interface LaunchSpec {
    data class X11Vnc(val startCommands: String) : LaunchSpec
    data object NativeCompositor : LaunchSpec
    data class NestedWayland(val compositorCmd: String) : LaunchSpec
}

/**
 * Data class form of a desktop environment — the same information
 * the [sh.haven.core.local.ProotManager.DesktopEnvironment] enum
 * carries today, plus a [LaunchSpec] and a per-package-family
 * package list for portability across distros.
 *
 * Phase 1: [packagesPerFamily] only has an APK entry. Phase 2 adds
 * APT entries when Debian lands.
 */
data class DesktopEnvironmentSpec(
    val id: String,
    val label: String,
    val packagesPerFamily: Map<PackageFamily, List<String>>,
    val verifyBinary: String,
    val launch: LaunchSpec,
    val sizeEstimateMb: Int,
    val minFreeMb: Int = sizeEstimateMb * 2,
    val hidden: Boolean = false,
)

/**
 * Data class form of a distro. Phase 1 ships with `alpine-3.21`
 * only; Phase 2 adds `debian-bookworm`.
 */
data class Distro(
    val id: String,
    val label: String,
    val family: PackageFamily,
    val rootfsSources: Map<Arch, RootfsSource>,
    val baselinePackages: List<String>,
    val postExtractHooks: List<RootfsHook> = emptyList(),
    val sizeEstimateMb: Int,
    /** null = any DE that has a [packagesPerFamily] entry for [family] is supported. */
    val supportedDeIds: Set<String>? = null,
)

/** Registry of known distros. Phase 1 = Alpine only. */
object DistroCatalog {
    val ALPINE_3_21 = Distro(
        id = "alpine-3.21",
        label = "Alpine Linux 3.21",
        family = PackageFamily.APK,
        rootfsSources = mapOf(
            Arch.AARCH64 to RootfsSource(
                url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz",
                sha256 = "ead8a4b37867bd19e7417dd078748e2312c0aea364403d96758d63ea8ff261ea",
            ),
            Arch.X86_64 to RootfsSource(
                url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/x86_64/alpine-minirootfs-3.21.3-x86_64.tar.gz",
                sha256 = "1a694899e406ce55d32334c47ac0b2efb6c06d7e878102d1840892ad44cd5239",
            ),
        ),
        baselinePackages = listOf("bash", "curl", "ca-certificates", "openssh-client", "tmux"),
        sizeEstimateMb = 6,
    )

    /**
     * Debian Bookworm (12) — first non-Alpine distro, landed in
     * Phase 2 of issue #162. Rootfs tarballs come from termux's
     * `proot-distro` project (the `pd-v4.17.3` revision is the
     * last one before they bumped Bookworm → Trixie). The
     * tarballs are wrapped in a top-level `debian-bookworm-<arch>/`
     * directory, hence `stripComponents = 1`.
     */
    val DEBIAN_BOOKWORM = Distro(
        id = "debian-bookworm",
        label = "Debian 12 (Bookworm)",
        family = PackageFamily.APT,
        rootfsSources = mapOf(
            Arch.AARCH64 to RootfsSource(
                url = "https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-aarch64-pd-v4.17.3.tar.xz",
                sha256 = "3a841a794ae5999b33e33b329582ed0379d4f54ca62c6ce5a8eb9cff5ef8900b",
                format = RootfsFormat.TAR_XZ,
                stripComponents = 1,
            ),
            Arch.X86_64 to RootfsSource(
                url = "https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-x86_64-pd-v4.17.3.tar.xz",
                sha256 = "675e534333adcbf369e97abda3088927651e5d91612ae5727c52ff2284f4b8c8",
                format = RootfsFormat.TAR_XZ,
                stripComponents = 1,
            ),
        ),
        baselinePackages = listOf("bash", "curl", "ca-certificates", "openssh-client", "tmux"),
        sizeEstimateMb = 130,
    )

    val all: List<Distro> = listOf(ALPINE_3_21, DEBIAN_BOOKWORM)

    fun lookup(id: String): Distro? = all.firstOrNull { it.id == id }

    /** The Phase 1 default — also what legacy installs migrate to. */
    const val DEFAULT_ID: String = "alpine-3.21"
}

/** Registry of known desktop environments. */
object DesktopCatalog {
    val OPENBOX = DesktopEnvironmentSpec(
        id = "openbox",
        label = "Openbox (VNC)",
        packagesPerFamily = mapOf(
            PackageFamily.APK to listOf("tigervnc", "openbox", "xterm", "xsetroot", "font-noto"),
            // Debian: tigervnc-standalone-server is the unprivileged Xvnc;
            // openbox + xterm + fonts-noto-core match the Alpine selection.
            PackageFamily.APT to listOf(
                "tigervnc-standalone-server", "openbox", "xterm", "x11-xserver-utils",
                "fonts-noto-core",
            ),
        ),
        verifyBinary = "usr/bin/openbox",
        launch = LaunchSpec.X11Vnc(
            startCommands = "xsetroot -solid '#2e3440'; openbox & xterm &",
        ),
        sizeEstimateMb = 10,
    )

    val XFCE4 = DesktopEnvironmentSpec(
        id = "xfce4",
        label = "Xfce4 (VNC)",
        packagesPerFamily = mapOf(
            PackageFamily.APK to listOf("tigervnc", "xfce4", "xfce4-terminal", "dbus-x11", "font-noto"),
            // Debian: `xfce4` meta-package pulls the full desktop; dbus-x11
            // is needed for proot-without-systemd; fonts-noto-core matches
            // the Alpine font selection.
            PackageFamily.APT to listOf(
                "tigervnc-standalone-server", "xfce4", "xfce4-terminal", "dbus-x11",
                "fonts-noto-core",
            ),
        ),
        verifyBinary = "usr/bin/startxfce4",
        launch = LaunchSpec.X11Vnc(
            startCommands = "xfwm4 & xfce4-panel & xfdesktop &",
        ),
        sizeEstimateMb = 100,
    )

    val LABWC_NATIVE = DesktopEnvironmentSpec(
        id = "labwc-native",
        label = "Native Wayland",
        packagesPerFamily = mapOf(
            PackageFamily.APK to listOf(
                "foot", "font-noto", "font-awesome", "adwaita-icon-theme",
                "xkeyboard-config", "xwayland", "mesa-dri-gallium", "mesa-gbm", "mesa-gl",
                "waybar", "fuzzel", "xfce4-terminal", "thunar", "mousepad", "htop", "dbus-x11",
            ),
        ),
        verifyBinary = "usr/bin/foot",
        launch = LaunchSpec.NativeCompositor,
        sizeEstimateMb = 80,
    )

    val all: List<DesktopEnvironmentSpec> = listOf(OPENBOX, XFCE4, LABWC_NATIVE)

    fun lookup(id: String): DesktopEnvironmentSpec? = all.firstOrNull { it.id == id }
}
