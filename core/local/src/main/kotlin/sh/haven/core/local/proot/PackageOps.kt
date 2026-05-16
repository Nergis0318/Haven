package sh.haven.core.local.proot

/**
 * Package-manager strategy interface for the [PackageFamily] enum.
 *
 * A template string isn't enough on its own — different families
 * have distinct invocation conventions (apt's `DEBIAN_FRONTEND`,
 * pacman's `--noconfirm`, etc.) and distinct stdout heuristics for
 * detecting "the package install succeeded but printed warnings".
 * This interface captures both.
 *
 * Phase 1 ships [Apk] only. [Apt], [Pacman], [Xbps], [Nix] land in
 * Phases 2-5.
 */
interface PackageOps {
    /** Command that refreshes the local package index. */
    fun updateCmd(): String

    /** Command that installs [pkgs] and prints progress. */
    fun installCmd(pkgs: List<String>): String

    /** Command that removes [pkgs]. */
    fun removeCmd(pkgs: List<String>): String

    /**
     * Detect "install succeeded" from the combined stdout+stderr of
     * an install run. Used as a fallback when a file-existence
     * check is unreliable (e.g. marker-file DEs).
     */
    fun installSucceeded(output: String): Boolean

    companion object {
        fun forFamily(family: PackageFamily): PackageOps = when (family) {
            PackageFamily.APK -> Apk
            PackageFamily.APT -> Apt
            PackageFamily.PACMAN -> error("Pacman support lands in Phase 3 — see issue #162")
            PackageFamily.XBPS -> error("XBPS support lands in Phase 3 — see issue #162")
            PackageFamily.NIX -> error("Nix support lands in Phase 5 — see issue #162")
        }
    }
}

object Apk : PackageOps {
    override fun updateCmd(): String = "apk update"

    override fun installCmd(pkgs: List<String>): String =
        "apk add ${pkgs.joinToString(" ")}"

    override fun removeCmd(pkgs: List<String>): String =
        "apk del ${pkgs.joinToString(" ")}"

    override fun installSucceeded(output: String): Boolean =
        output.contains("OK:")
}

/**
 * Debian / Ubuntu apt-get strategy.
 *
 * `DEBIAN_FRONTEND=noninteractive` suppresses dpkg's debconf
 * prompts; `-y` auto-confirms. `--no-install-recommends` is
 * deliberately omitted from install — Xfce4 et al. become
 * unusable without their Recommends. Callers that want minimal
 * footprint can append the flag via the package list (e.g. by
 * wrapping the package name in `--no-install-recommends pkgname`
 * — apt parses options anywhere).
 *
 * Success heuristic: apt-get prints `Setting up <pkg> (<ver>) ...`
 * for each installed package. This is more reliable than the
 * exit code because apt returns non-zero on warnings (e.g.
 * post-install trigger failures from systemd assumptions) that
 * don't actually break the install.
 */
object Apt : PackageOps {
    private const val ENV = "DEBIAN_FRONTEND=noninteractive"

    override fun updateCmd(): String =
        "$ENV apt-get update -y"

    /**
     * `--fix-missing` lets a single mirror flake (transient 400 / 503
     * on one .deb out of hundreds) skip that package instead of
     * tanking the whole install. The user can retry to pick up
     * stragglers. Without it, mid-install network blips force the
     * user to restart the entire 1-2 GB desktop fetch.
     */
    override fun installCmd(pkgs: List<String>): String =
        "$ENV apt-get install -y --fix-missing ${pkgs.joinToString(" ")}"

    override fun removeCmd(pkgs: List<String>): String =
        "$ENV apt-get remove -y ${pkgs.joinToString(" ")}"

    override fun installSucceeded(output: String): Boolean =
        output.contains("Setting up ")
}
