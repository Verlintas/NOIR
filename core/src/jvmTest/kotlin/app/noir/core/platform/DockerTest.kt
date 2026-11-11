/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DockerTest {

    /** A stand-in `docker` that answers `--version` and `version` the way [body] says. */
    private fun fakeDocker(body: String): String {
        val file = File(Files.createTempDirectory("noir-docker").toFile(), "docker")
        file.writeText("#!/bin/sh\n$body\n")
        file.setExecutable(true)
        return file.absolutePath
    }

    @Test
    fun `client version is read from docker --version`() {
        assertEquals("29.9.2", Docker.parseClientVersion("Docker version 29.9.2, build a82e528\n"))
        assertEquals("20.10.24+dfsg1", Docker.parseClientVersion("Docker version 20.10.24+dfsg1, build 297e128"))
        assertEquals("4.9.3", Docker.parseClientVersion("podman version 4.9.3"))
        assertNull(Docker.parseClientVersion("docker: command not found"))
    }

    @Test
    fun `a daemon that answers is running`() {
        val answer = Docker.parseDaemon(0, "29.9.2\n")
        assertEquals(DockerDaemon.RUNNING, answer.state)
        assertEquals("29.9.2", answer.version)
    }

    @Test
    fun `warnings before the version do not hide a running daemon`() {
        val answer = Docker.parseDaemon(
            0,
            "WARNING: Error loading config file: /home/u/.docker/config.json: " +
                "open /home/u/.docker/config.json: permission denied\n" +
                "27.3.1\n"
        )
        assertEquals(DockerDaemon.RUNNING, answer.state)
        assertEquals("27.3.1", answer.version)
    }

    @Test
    fun `a missing or dead socket means the daemon is stopped`() {
        // Docker 29 on Linux, socket file absent.
        val modern = Docker.parseDaemon(
            1,
            "\nfailed to connect to the docker API at unix:///var/run/docker.sock; check if the path " +
                "is correct and if the daemon is running: dial unix /var/run/docker.sock: connect: " +
                "no such file or directory\n"
        )
        assertEquals(DockerDaemon.STOPPED, modern.state)
        assertNull(modern.version)
        assertTrue(modern.detail!!.startsWith("failed to connect to the docker API"))

        // Socket file present, nobody listening; also what older releases always printed.
        val classic = Docker.parseDaemon(
            1,
            "\nCannot connect to the Docker daemon at unix:///var/run/docker.sock. " +
                "Is the docker daemon running?\n"
        )
        assertEquals(DockerDaemon.STOPPED, classic.state)

        // Docker Desktop on Windows, not started.
        val windows = Docker.parseDaemon(
            1,
            "error during connect: Get \"http://%2F%2F.%2Fpipe%2FdockerDesktopLinuxEngine/v1.47/version\": " +
                "open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified.\n"
        )
        assertEquals(DockerDaemon.STOPPED, windows.state)
    }

    @Test
    fun `a refused socket is denied, not stopped`() {
        val modern = Docker.parseDaemon(
            1,
            "\npermission denied while trying to connect to the docker API at unix:///var/run/docker.sock\n"
        )
        assertEquals(DockerDaemon.DENIED, modern.state)

        val classic = Docker.parseDaemon(
            1,
            "permission denied while trying to connect to the Docker daemon socket at " +
                "unix:///var/run/docker.sock: Get \"http://%2Fvar%2Frun%2Fdocker.sock/v1.24/version\": " +
                "dial unix /var/run/docker.sock: connect: permission denied\n"
        )
        assertEquals(DockerDaemon.DENIED, classic.state)

        val windows = Docker.parseDaemon(
            1,
            "error during connect: Get \"http://%2F%2F.%2Fpipe%2Fdocker_engine/v1.24/version\": " +
                "open //./pipe/docker_engine: Access is denied.\n"
        )
        assertEquals(DockerDaemon.DENIED, windows.state)

        // Same failure on a Chinese Windows: the OS half of the message is localised.
        val windowsZh = Docker.parseDaemon(
            1,
            "error during connect: Get \"http://%2F%2F.%2Fpipe%2Fdocker_engine/v1.24/version\": " +
                "open //./pipe/docker_engine: 拒绝访问。\n"
        )
        assertEquals(DockerDaemon.DENIED, windowsZh.state)
    }

    @Test
    fun `an unreadable config file does not turn a stopped daemon into a denied one`() {
        val answer = Docker.parseDaemon(
            1,
            "WARNING: Error loading config file: /home/u/.docker/config.json: " +
                "open /home/u/.docker/config.json: permission denied\n" +
                "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. " +
                "Is the docker daemon running?\n"
        )
        assertEquals(DockerDaemon.STOPPED, answer.state)
        assertTrue(answer.detail!!.startsWith("Cannot connect"))
    }

    @Test
    fun `an unrecognised failure stays unknown and keeps its reason`() {
        val answer = Docker.parseDaemon(
            125,
            "Error: template: version:1:9: executing \"version\" at <.Server.Version>: " +
                "nil pointer evaluating *define.Version.Version\n"
        )
        assertEquals(DockerDaemon.UNKNOWN, answer.state)
        assertTrue(answer.detail!!.startsWith("Error: template"))

        // Exit 0 without a version is not proof of a running daemon.
        assertEquals(DockerDaemon.UNKNOWN, Docker.parseDaemon(0, "<no value>\n").state)
        assertEquals(DockerDaemon.UNKNOWN, Docker.parseDaemon(0, "").state)
    }

    @Test
    fun `no cli means docker is not installed`() {
        val status = Docker.probe(cli = null)
        assertFalse(status.installed)
        assertFalse(status.running)
    }

    @Test
    fun `probe reports a running daemon`() {
        if (Platform.isWindows) return
        val cli = fakeDocker(
            """
            case "${'$'}1" in
              --version) echo "Docker version 27.3.1, build ce12230" ;;
              version) echo "27.3.1" ;;
            esac
            """.trimIndent()
        )

        val status = Docker.probe(cli)
        assertTrue(status.installed)
        assertTrue(status.running)
        assertEquals(cli, status.cli)
        assertEquals("27.3.1", status.clientVersion)
        assertEquals("27.3.1", status.serverVersion)
    }

    @Test
    fun `probe reports an installed but stopped daemon`() {
        if (Platform.isWindows) return
        val cli = fakeDocker(
            """
            case "${'$'}1" in
              --version) echo "Docker version 27.3.1, build ce12230" ;;
              version)
                echo
                echo "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?" >&2
                exit 1 ;;
            esac
            """.trimIndent()
        )

        val status = Docker.probe(cli)
        assertTrue(status.installed)
        assertFalse(status.running)
        assertEquals(DockerDaemon.STOPPED, status.daemon)
        assertEquals("27.3.1", status.clientVersion)
        assertNull(status.serverVersion)
    }

    @Test
    fun `probe gives up on a daemon that never answers`() {
        if (Platform.isWindows) return
        val cli = fakeDocker(
            """
            case "${'$'}1" in
              --version) echo "Docker version 27.3.1, build ce12230" ;;
              version) sleep 8 ;;
            esac
            """.trimIndent()
        )

        val started = System.currentTimeMillis()
        val status = Docker.probe(cli, timeoutSec = 1)
        val elapsed = System.currentTimeMillis() - started

        assertEquals(DockerDaemon.UNKNOWN, status.daemon)
        assertEquals("27.3.1", status.clientVersion)
        assertEquals("no answer within 1s", status.detail)
        // The orphaned `sleep` keeps the pipe open; probe must not wait for it.
        assertTrue(elapsed < 6_000, "timeout took too long: ${elapsed}ms")
    }

    @Test
    fun `probe survives a cli that cannot start`() {
        val missing = File(Files.createTempDirectory("noir-docker").toFile(), "docker").absolutePath

        val status = Docker.probe(missing)
        assertTrue(status.installed)
        assertEquals(DockerDaemon.UNKNOWN, status.daemon)
        assertNotNull(status.detail)
    }

    @Test
    fun `marker files and variables identify the container runtime`() {
        fun detect(
            env: Map<String, String> = emptyMap(),
            files: Set<String> = emptySet(),
            cgroup: String? = null
        ) = Docker.detectContainer(env, { it in files }, cgroup)

        assertEquals(ContainerRuntime.DOCKER, detect(files = setOf("/.dockerenv")))
        assertEquals(ContainerRuntime.PODMAN, detect(files = setOf("/run/.containerenv")))
        assertEquals(ContainerRuntime.PODMAN, detect(env = mapOf("container" to "podman")))
        assertEquals(ContainerRuntime.LXC, detect(env = mapOf("container" to "lxc")))
        assertEquals(ContainerRuntime.OTHER, detect(env = mapOf("container" to "oci")))

        // A pod is still a pod when the node happens to run Docker underneath.
        assertEquals(
            ContainerRuntime.KUBERNETES,
            detect(env = mapOf("KUBERNETES_SERVICE_HOST" to "10.96.0.1"), files = setOf("/.dockerenv"))
        )
    }

    @Test
    fun `cgroup v1 names the runtime when the marker files are gone`() {
        val docker = """
            9:name=systemd:/docker/5ee95099ef2841a219aeb64bc156a17cd3d894287bd14e04bb066940ee26373d
            5:devices:/docker/5ee95099ef2841a219aeb64bc156a17cd3d894287bd14e04bb066940ee26373d
            0::/docker/5ee95099ef2841a219aeb64bc156a17cd3d894287bd14e04bb066940ee26373d
        """.trimIndent()
        val pod = "11:memory:/kubepods/burstable/pod3c1f0d5e/0a1b2c3d4e5f\n"
        val podman = "0::/machine.slice/libpod-0a1b2c3d4e5f.scope\n"

        fun detect(cgroup: String?) = Docker.detectContainer(emptyMap(), { false }, cgroup)

        assertEquals(ContainerRuntime.DOCKER, detect(docker))
        assertEquals(ContainerRuntime.KUBERNETES, detect(pod))
        assertEquals(ContainerRuntime.PODMAN, detect(podman))
    }

    @Test
    fun `an ordinary host is not a container`() {
        fun detect(cgroup: String?) = Docker.detectContainer(emptyMap(), { false }, cgroup)

        // systemd host on cgroup v1, on cgroup v2, and a host where /proc is unreadable.
        assertNull(detect("9:name=systemd:/\n8:pids:/\n5:devices:/\n"))
        assertNull(detect("0::/init.scope\n"))
        assertNull(detect(null))
        // An empty `container` variable is not a claim.
        assertNull(Docker.detectContainer(mapOf("container" to " "), { false }, "0::/\n"))
    }
}
