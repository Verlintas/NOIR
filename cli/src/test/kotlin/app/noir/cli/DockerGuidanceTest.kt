/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.cli

import app.noir.core.Lang
import app.noir.core.Tui
import app.noir.core.platform.ContainerRuntime
import app.noir.core.platform.DockerDaemon
import app.noir.core.platform.DockerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerGuidanceTest {

    private val han = Regex("[\\u4e00-\\u9fff]")

    private fun status(daemon: DockerDaemon, server: String? = null, detail: String? = null) =
        DockerStatus("/usr/bin/docker", "29.9.2", daemon, server, detail)

    private val everyState = listOf(
        DockerStatus(cli = null),
        status(DockerDaemon.RUNNING, server = "29.9.2"),
        status(DockerDaemon.STOPPED),
        status(DockerDaemon.DENIED),
        status(DockerDaemon.UNKNOWN, detail = "no answer within 10s")
    )

    @Test
    fun `missing docker is reported as optional, not as a failure`() {
        val row = Guidance.docker(DockerStatus(cli = null), Lang.EN)
        assertEquals(1, row.lines().size)
        assertTrue(row.contains("not installed"))
        assertTrue(row.contains("optional"))
    }

    @Test
    fun `a running daemon shows both versions and no advice`() {
        val rows = Guidance.docker(status(DockerDaemon.RUNNING, server = "29.9.1"), Lang.EN).lines()
        assertEquals(listOf("  docker    /usr/bin/docker", "            client 29.9.2 · daemon 29.9.1 running"), rows)
    }

    @Test
    fun `a stopped daemon ends with how to start it on this platform`() {
        fun advice(windows: Boolean, mac: Boolean) =
            Guidance.docker(status(DockerDaemon.STOPPED), Lang.EN, windows = windows, mac = mac).lines().last()

        assertTrue(advice(windows = false, mac = false).contains("systemctl start docker"))
        assertTrue(advice(windows = true, mac = false).contains("Docker Desktop"))
        assertTrue(advice(windows = false, mac = true).contains("Docker Desktop"))
    }

    @Test
    fun `a refused daemon ends with how to get access on this platform`() {
        fun advice(windows: Boolean, mac: Boolean) =
            Guidance.docker(status(DockerDaemon.DENIED), Lang.EN, windows = windows, mac = mac).lines().last()

        assertTrue(advice(windows = false, mac = false).contains("usermod -aG docker \$USER"))
        assertTrue(advice(windows = true, mac = false).contains("docker-users"))
        assertTrue(advice(windows = false, mac = true).contains("Docker Desktop"))
    }

    @Test
    fun `an unknown state passes the cli's own reason through`() {
        val rows = Guidance.docker(status(DockerDaemon.UNKNOWN, detail = "no answer within 10s"), Lang.EN).lines()
        assertTrue(rows[1].contains("daemon state unknown"))
        assertEquals("            no answer within 10s", rows.last())
    }

    @Test
    fun `english docker text has no chinese characters`() {
        val blocks = everyState.flatMap { state ->
            listOf(
                Guidance.docker(state, Lang.EN, windows = false, mac = false),
                Guidance.docker(state, Lang.EN, windows = true, mac = false),
                Guidance.docker(state, Lang.EN, windows = false, mac = true)
            )
        } + ContainerRuntime.entries.flatMap { runtime ->
            listOf(Guidance.container(runtime, Lang.EN), Guidance.containerDeviceGuide(runtime, Lang.EN))
        }
        blocks.forEach { block ->
            assertFalse(han.containsMatchIn(block), "untranslated guidance:\n$block")
        }
    }

    @Test
    fun `ascii mode keeps english docker text 7-bit`() {
        ASCII = true
        try {
            val blocks = everyState.map { Guidance.docker(it, Lang.EN) } +
                ContainerRuntime.entries.map { Guidance.containerDeviceGuide(it, Lang.EN) }
            blocks.forEach { block ->
                assertTrue(block.all { it.code < 128 }, "non-ASCII output in --ascii mode:\n$block")
            }
        } finally {
            ASCII = false
        }
    }

    @Test
    fun `container row names the runtime when it is known`() {
        assertEquals("  container docker", Guidance.container(ContainerRuntime.DOCKER, Lang.EN))
        assertEquals("  container podman", Guidance.container(ContainerRuntime.PODMAN, Lang.ZH))
        assertEquals("  container yes", Guidance.container(ContainerRuntime.OTHER, Lang.EN))
    }

    @Test
    fun `container guide explains usb passthrough and fits the terminal`() {
        val limit = Tui.terminalColumns()
        listOf(Lang.ZH, Lang.EN).forEach { lang ->
            ContainerRuntime.entries.forEach { runtime ->
                val guide = Guidance.containerDeviceGuide(runtime, lang)
                assertTrue(guide.contains("/dev/bus/usb"), "no passthrough hint:\n$guide")
                assertTrue(guide.contains("ADB_SERVER_SOCKET"), "no adb server hint:\n$guide")
                guide.lines().forEach { line ->
                    assertTrue(Tui.visibleWidth(line) <= limit, "line wider than $limit: [$line]")
                }
            }
        }
        assertTrue(Guidance.containerDeviceGuide(ContainerRuntime.DOCKER, Lang.EN).contains("(docker)"))
        assertFalse(Guidance.containerDeviceGuide(ContainerRuntime.OTHER, Lang.EN).contains("("))
    }
}
