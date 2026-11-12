/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core.platform

import java.io.File
import java.util.concurrent.TimeUnit

/** How the Docker daemon answered when it was asked for its version. */
enum class DockerDaemon {
    /** The daemon answered. */
    RUNNING,

    /** The CLI is installed, but no daemon is listening. */
    STOPPED,

    /** A daemon is listening, but this user is not allowed to talk to it. */
    DENIED,

    /** No clear answer: a timeout, or an error NOIR does not recognise. */
    UNKNOWN
}

/**
 * Result of [Docker.probe]. `cli == null` means Docker is not installed; the
 * other fields only carry meaning when it is.
 */
data class DockerStatus(
    val cli: String?,
    val clientVersion: String? = null,
    val daemon: DockerDaemon = DockerDaemon.UNKNOWN,
    val serverVersion: String? = null,
    /** First useful line of the CLI's complaint, when there was one. */
    val detail: String? = null
) {
    val installed: Boolean get() = cli != null
    val running: Boolean get() = daemon == DockerDaemon.RUNNING
}

/** The container runtime NOIR itself is running under. */
enum class ContainerRuntime(val label: String) {
    DOCKER("docker"),
    PODMAN("podman"),
    KUBERNETES("kubernetes"),
    LXC("lxc"),
    OTHER("container")
}

/** What [Docker.parseDaemon] read out of `docker version`. */
internal data class DaemonAnswer(
    val state: DockerDaemon,
    val version: String? = null,
    val detail: String? = null
)

/**
 * Docker is optional for NOIR — it is only needed to run NOIR from a container
 * image. This answers three questions for `noir doctor`: is Docker installed,
 * is its daemon running, and is NOIR itself inside a container (where USB
 * devices stay invisible until they are passed through).
 *
 * Only the local `docker` CLI is asked, against whatever endpoint the user
 * already configured for it. Nothing is pulled and no container is started.
 */
object Docker {

    private val CLIENT_VERSION = Regex("\\bversion\\s+v?([0-9][^\\s,]*)", RegexOption.IGNORE_CASE)
    private val VERSION_LINE = Regex("^v?[0-9]+\\.[0-9]\\S*$")

    // Sentences the Docker CLI writes itself, and only when it could not reach
    // the daemon's socket or pipe at all. "error during connect" is not one of
    // them: the CLI puts that in front of every transport failure, a rejected
    // TLS certificate included, and those leave the daemon's state open.
    private val STOPPED_PHRASES = listOf(
        "cannot connect to the docker daemon",
        "failed to connect to the docker api",
        "docker daemon is not running"
    )

    private val DENIED_PHRASES = listOf(
        "permission denied while trying to connect"
    )

    // The operating system's own wording also turns up for unrelated files (a
    // missing ca.pem, an unreadable key), so it only counts on a line that
    // names the daemon's endpoint. Windows writes it in the system language.
    private val ENDPOINT = listOf("dial unix", "open //./pipe/")

    private val OS_MISSING = listOf(
        "no such file or directory",
        "connection refused",
        "cannot find the file specified",
        "系统找不到指定的文件"
    )

    private val OS_DENIED = listOf(
        "permission denied",
        "access is denied",
        "拒绝访问"
    )

    /** Locates the Docker CLI: `PATH` first, then the usual install locations. */
    fun find(): String? {
        Platform.which("docker")?.let { return it }

        val home = System.getProperty("user.home")
        val candidates = buildList {
            when {
                Platform.isWindows -> {
                    val programs = System.getenv("ProgramFiles") ?: "C:\\Program Files"
                    add("$programs\\Docker\\Docker\\resources\\bin\\docker.exe")
                }
                Platform.isMac -> {
                    add("/usr/local/bin/docker")
                    add("/opt/homebrew/bin/docker")
                    add("$home/.docker/bin/docker")
                    add("/Applications/Docker.app/Contents/Resources/bin/docker")
                    add("$home/.orbstack/bin/docker")
                    add("$home/.rd/bin/docker")
                }
                else -> {
                    add("/usr/bin/docker")
                    add("/usr/local/bin/docker")
                    add("/snap/bin/docker")
                    add("$home/bin/docker")
                    add("$home/.rd/bin/docker")
                }
            }
        }
        return candidates.firstOrNull(Platform::usable)
    }

    /**
     * Asks the CLI for its own version, then for the daemon's.
     *
     * Never throws and never waits longer than [timeoutSec] per question: a
     * CLI that cannot start, or a daemon endpoint that does not answer, is
     * reported as [DockerDaemon.UNKNOWN] with the reason in `detail`.
     */
    fun probe(cli: String? = find(), timeoutSec: Long = 10): DockerStatus {
        if (cli == null) return DockerStatus(cli = null)
        val silent = "no answer within ${timeoutSec}s"

        // `--version` never touches the daemon, so it works while Docker is stopped.
        val client = try {
            run(cli, listOf("--version"), timeoutSec)
        } catch (e: Exception) {
            return DockerStatus(cli, detail = e.message ?: e::class.simpleName)
        }
        if (client.timedOut) return DockerStatus(cli, detail = silent)
        val clientVersion = parseClientVersion(client.text)

        val server = try {
            run(cli, listOf("version", "--format", "{{.Server.Version}}"), timeoutSec)
        } catch (e: Exception) {
            return DockerStatus(cli, clientVersion, detail = e.message ?: e::class.simpleName)
        }
        if (server.timedOut) return DockerStatus(cli, clientVersion, detail = silent)

        val answer = parseDaemon(server.code, server.text)
        return DockerStatus(cli, clientVersion, answer.state, answer.version, answer.detail)
    }

    /** `Docker version 29.9.2, build a82e528` → `29.9.2`. */
    internal fun parseClientVersion(output: String): String? =
        CLIENT_VERSION.find(output)?.groupValues?.get(1)

    /**
     * Reads the outcome of `docker version --format {{.Server.Version}}`, with
     * stderr merged in. A running daemon prints just its version; otherwise
     * the CLI explains why it could not reach one, in wording that differs by
     * platform and Docker release.
     */
    internal fun parseDaemon(code: Int, output: String): DaemonAnswer {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (code == 0) {
            // Warnings may precede the answer; the version is the last thing printed.
            lines.lastOrNull { VERSION_LINE.matches(it) }?.let {
                return DaemonAnswer(DockerDaemon.RUNNING, version = it.removePrefix("v"))
            }
        }

        // A warning (an unreadable config file, …) is not the reason the daemon is silent.
        val complaints = lines.filterNot { it.startsWith("warning", ignoreCase = true) }

        // Refused is checked first: that message also says the daemon could not be reached.
        complaints.firstOrNull(::refused)?.let {
            return DaemonAnswer(DockerDaemon.DENIED, detail = it.take(300))
        }
        complaints.firstOrNull(::unreachable)?.let {
            return DaemonAnswer(DockerDaemon.STOPPED, detail = it.take(300))
        }
        return DaemonAnswer(
            DockerDaemon.UNKNOWN,
            detail = (complaints.firstOrNull() ?: lines.firstOrNull())?.take(300)
        )
    }

    private fun refused(line: String): Boolean {
        val text = line.lowercase()
        return DENIED_PHRASES.any { it in text } ||
            (ENDPOINT.any { it in text } && OS_DENIED.any { it in text })
    }

    private fun unreachable(line: String): Boolean {
        val text = line.lowercase()
        return STOPPED_PHRASES.any { it in text } ||
            (ENDPOINT.any { it in text } && OS_MISSING.any { it in text })
    }

    /** The container runtime NOIR is running in, or null on an ordinary host. */
    fun container(): ContainerRuntime? {
        if (!Platform.isLinux) return null
        return detectContainer(
            env = System.getenv(),
            exists = { File(it).exists() },
            cgroup = try {
                File("/proc/1/cgroup").readText()
            } catch (e: Exception) {
                null
            }
        )
    }

    /**
     * Container detection from the marker files and variables the runtimes
     * leave behind. The cgroup of PID 1 is the fallback: on cgroup v1 it names
     * the runtime, on pure v2 it is just `0::/` and says nothing.
     */
    internal fun detectContainer(
        env: Map<String, String>,
        exists: (String) -> Boolean,
        cgroup: String?
    ): ContainerRuntime? {
        if (!env["KUBERNETES_SERVICE_HOST"].isNullOrBlank()) return ContainerRuntime.KUBERNETES
        if (exists("/.dockerenv")) return ContainerRuntime.DOCKER
        if (exists("/run/.containerenv")) return ContainerRuntime.PODMAN

        // systemd's convention: container=docker|podman|lxc|oci|…
        env["container"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { value ->
            return when {
                "docker" in value -> ContainerRuntime.DOCKER
                "podman" in value -> ContainerRuntime.PODMAN
                "lxc" in value -> ContainerRuntime.LXC
                else -> ContainerRuntime.OTHER
            }
        }

        val groups = cgroup?.lowercase() ?: return null
        return when {
            "kubepods" in groups -> ContainerRuntime.KUBERNETES
            "docker" in groups -> ContainerRuntime.DOCKER
            "libpod" in groups -> ContainerRuntime.PODMAN
            "/lxc" in groups -> ContainerRuntime.LXC
            else -> null
        }
    }

    private class Output(val code: Int, val text: String, val timedOut: Boolean)

    /**
     * Runs the CLI with a real deadline. stdout is drained on a daemon thread
     * so a process that hangs without closing its pipe cannot hang NOIR.
     */
    private fun run(cli: String, args: List<String>, timeoutSec: Long): Output {
        val process = ProcessBuilder(Exec.argv(cli, args))
            .redirectErrorStream(true)
            .start()
        val text = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { stream ->
                    while (true) {
                        val line = stream.readLine() ?: break
                        synchronized(text) { text.append(line).append('\n') }
                    }
                }
            } catch (_: Exception) {
                // Process killed on timeout, or the pipe was closed.
            }
        }
        reader.isDaemon = true
        reader.start()

        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(2_000)
            return Output(-1, synchronized(text) { text.toString() }, timedOut = true)
        }
        reader.join(2_000)
        return Output(process.exitValue(), synchronized(text) { text.toString() }, timedOut = false)
    }
}
