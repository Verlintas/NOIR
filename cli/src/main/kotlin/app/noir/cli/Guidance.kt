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
import app.noir.core.platform.Platform

/** Human-friendly text blocks: every failure should end with an action. */
object Guidance {

    private fun v() = if (ASCII) "|" else "│"
    private fun h() = if (ASCII) "-" else "─"
    private fun asciiText(text: String) = if (ASCII) {
        text.replace("•", "-").replace("→", "->").replace("—", "-").replace("·", "-")
    } else {
        text
    }

    fun about(version: String): String {
        val total = (Tui.terminalColumns() - 2).coerceIn(40, 100)
        val inner = total - 4

        fun row(text: String) = v() + " " + pad(text, inner) + " " + v()

        val sb = StringBuilder()
        val tl = if (ASCII) "+" else "╭"
        val tr = if (ASCII) "+" else "╮"
        sb.append(tl).append(h().repeat(total - 2)).append(tr).append('\n')

        val logo = listOf(
            "  ███╗   ██╗ ██████╗ ██╗██████╗ ",
            "  ████╗  ██║██╔═══██╗██║██╔══██╗",
            "  ██╔██╗ ██║██║   ██║██║██████╔╝",
            "  ██║╚██╗██║██║   ██║██║██╔══██╗",
            "  ██║ ╚████║╚██████╔╝██║██║  ██║",
            "  ╚═╝  ╚═══╝ ╚═════╝ ╚═╝╚═╝  ╚═╝"
        )
        if (!ASCII && inner >= 40) {
            logo.forEach { sb.append(row(it)).append('\n') }
        } else {
            sb.append(row("  NOIR")).append('\n')
        }

        sb.append(row("")).append('\n')

        fun field(label: String, value: String) {
            val prefix = "  " + label.padEnd(10)
            val lines = wrap(asciiText(value), inner - prefix.length)
            if (lines.isEmpty()) {
                sb.append(row(prefix.trimEnd())).append('\n')
                return
            }
            sb.append(row(prefix + lines.first())).append('\n')
            lines.drop(1).forEach { sb.append(row(" ".repeat(prefix.length) + it)).append('\n') }
        }

        field("version", "$version · android leak audit")
        field("what", "inventories what installed apps can collect, and where they send it")
        field("not", "a root tool · not a malware scanner · not a firewall")
        field("writes", "nothing by default; --apply only sets reversible app-ops")
        field("license", "MIT")
        field("author", "Verlintas")
        field("repo", "github.com/Verlintas/NOIR")
        field("runs on", "Windows 10/11 · macOS · Debian/Ubuntu")

        sb.append(row("")).append('\n')
        sb.append(row("  commands")).append('\n')
        listOf(
            "noir" to "guided first run",
            "noir doctor" to "environment check",
            "noir scan" to "inventory + report",
            "noir diff a b" to "compare two reports",
            "noir ui" to "dashboard",
            "noir auto" to "plan (+ --apply)",
            "noir deep <pkg>" to "jadx dive",
            "noir setup" to "download adb / jadx"
        ).forEach { (cmd, desc) ->
            sb.append(row("    " + cmd.padEnd(18) + desc)).append('\n')
        }

        val bl = if (ASCII) "+" else "╰"
        val br = if (ASCII) "+" else "╯"
        sb.append(bl).append(h().repeat(total - 2)).append(br)
        return sb.toString()
    }

    fun deviceGuide(lang: Lang = LANG): String {
        val zh = listOf(
            "",
            "1. 打开开发者模式",
            "   小米 / HyperOS：设置 → 我的设备 → 全部参数 → 连点「MIUI 版本」7 次",
            "   原生 Android：设置 → 关于手机 → 连点「版本号」7 次",
            "",
            "2. 开启 USB 调试",
            "   设置 → 系统 → 开发者选项 → USB 调试",
            "   Xiaomi 额外开启：USB 调试（安全设置）",
            "",
            "3. 插线后手机会弹「允许 USB 调试吗？」",
            "   勾选「一律允许使用这台计算机进行调试」→ 允许",
            "",
            "4. 还不行就换一根数据线（很多线只能充电）"
        )
        val en = listOf(
            "",
            "1. Enable Developer options",
            "   Xiaomi / HyperOS: Settings → About phone → tap \"MIUI version\" 7 times",
            "   Stock Android: Settings → About phone → tap \"Build number\" 7 times",
            "",
            "2. Turn on USB debugging",
            "   Settings → System → Developer options → USB debugging",
            "   Xiaomi: also enable \"USB debugging (Security settings)\"",
            "",
            "3. Allow the prompt on the phone",
            "   Check \"Always allow from this computer\" → Allow",
            "",
            "4. Still nothing? Try another cable — many are charge-only."
        )
        return guideBox(if (lang == Lang.EN) "no device detected" else "没有检测到设备", if (lang == Lang.EN) en else zh)
    }

    fun unauthorizedGuide(lang: Lang = LANG): String = asciiText(if (lang == Lang.EN) {
        """
        |Device connected, but not authorised.
        |
        |  • Check the phone: an "Allow USB debugging?" dialog should be visible.
        |  • No dialog: Developer options → Revoke USB debugging authorisations → replug.
        |  • Still stuck: turn USB debugging off and on again.
        """.trimMargin()
    } else {
        """
        |设备已连接，但没有授权。
        |
        |  • 看手机屏幕：应该有一个「允许 USB 调试吗？」的弹窗
        |  • 没弹窗：开发者选项 → 撤销 USB 调试授权 → 重新插拔
        |  • 仍不行：开发者选项 → 关闭再打开 USB 调试
        """.trimMargin()
    })

    fun adbMissing(lang: Lang = LANG): String = asciiText(if (lang == Lang.EN) {
        """
        |  • macOS: brew install --cask android-platform-tools
        |  • Windows: scoop install adb  or  https://developer.android.com/tools/releases/platform-tools
        |  • Debian/Ubuntu: sudo apt install adb
        |  • Or let NOIR download it: noir setup
        """.trimMargin()
    } else {
        """
        |  • macOS：brew install --cask android-platform-tools
        |  • Windows：scoop install adb  或  https://developer.android.com/tools/releases/platform-tools
        |  • Debian/Ubuntu：sudo apt install adb
        |  • 或者让 NOIR 自己下载：noir setup
        """.trimMargin()
    })

    fun jadxMissing(lang: Lang = LANG): String = asciiText(if (lang == Lang.EN) {
        """
        |  • macOS: brew install jadx
        |  • Windows: scoop install jadx
        |  • Debian/Ubuntu: apt install jadx
        |  • Or let NOIR download it: noir setup
        """.trimMargin()
    } else {
        """
        |  • macOS：brew install jadx
        |  • Windows：scoop install jadx
        |  • Debian/Ubuntu：apt install jadx
        |  • 或者让 NOIR 自己下载：noir setup
        """.trimMargin()
    })

    /**
     * Doctor rows for Docker. Docker is optional, so "not installed" is a plain
     * fact; a daemon that is installed but unusable still ends with an action.
     */
    fun docker(
        status: DockerStatus,
        lang: Lang = LANG,
        windows: Boolean = Platform.isWindows,
        mac: Boolean = Platform.isMac
    ): String {
        fun l(zh: String, en: String) = if (lang == Lang.EN) en else zh

        if (!status.installed) {
            return l(
                "  docker    未安装（可选：只有想在容器里运行 NOIR 才需要）",
                "  docker    not installed (optional: only needed to run NOIR in a container)"
            )
        }

        val client = status.clientVersion?.let { l("客户端 $it", "client $it") }
        val daemon = when (status.daemon) {
            DockerDaemon.RUNNING ->
                status.serverVersion?.let { l("守护进程 $it 运行中", "daemon $it running") }
                    ?: l("守护进程运行中", "daemon running")
            DockerDaemon.STOPPED -> l("守护进程未运行", "daemon not running")
            DockerDaemon.DENIED -> l("守护进程拒绝访问", "daemon refused access")
            DockerDaemon.UNKNOWN -> l("守护进程状态未知", "daemon state unknown")
        }
        val next = when (status.daemon) {
            DockerDaemon.RUNNING -> null
            DockerDaemon.STOPPED ->
                if (windows || mac) {
                    l("启动方法：打开 Docker Desktop", "start it: open Docker Desktop")
                } else {
                    l("启动方法：sudo systemctl start docker", "start it: sudo systemctl start docker")
                }
            DockerDaemon.DENIED -> when {
                windows -> l(
                    "解决方法：把当前账号加入 docker-users 用户组，然后重新登录",
                    "fix: add your account to the docker-users group, then sign in again"
                )
                mac -> l("解决方法：重启 Docker Desktop", "fix: restart Docker Desktop")
                else -> l(
                    "解决方法：sudo usermod -aG docker \$USER，然后重新登录",
                    "fix: sudo usermod -aG docker \$USER, then log in again"
                )
            }
            DockerDaemon.UNKNOWN -> status.detail
        }

        val indent = " ".repeat(12)
        val sb = StringBuilder("  docker    ").append(status.cli)
        sb.append('\n').append(indent).append(asciiText(listOfNotNull(client, daemon).joinToString(" · ")))
        if (next != null) sb.append('\n').append(indent).append(asciiText(next))
        return sb.toString()
    }

    /** Doctor row shown only when NOIR itself runs inside a container. */
    fun container(runtime: ContainerRuntime, lang: Lang = LANG): String {
        val name = if (runtime == ContainerRuntime.OTHER) {
            if (lang == Lang.EN) "yes" else "是"
        } else {
            runtime.label
        }
        return "  container $name"
    }

    /**
     * "No device" inside a container: the cable is rarely the problem there,
     * the missing USB passthrough is.
     */
    fun containerDeviceGuide(runtime: ContainerRuntime, lang: Lang = LANG): String {
        val known = runtime != ContainerRuntime.OTHER
        val zh = listOf(
            "",
            "NOIR 正在容器里运行" + (if (known) "（${runtime.label}）" else "") +
                "，容器默认看不到宿主机的 USB 设备。",
            "",
            "1. Linux 宿主机：docker run 时加上这两个参数，把 USB 透传进容器",
            "   --device-cgroup-rule='c 189:* rmw' -v /dev/bus/usb:/dev/bus/usb",
            "   宿主机上的 adb 会占住手机，先执行 adb kill-server",
            "",
            "2. macOS / Windows：Docker Desktop 不能透传 USB",
            "   请改用便携包，直接在宿主机上运行 NOIR",
            "",
            "3. 手机端仍需开启 USB 调试，并在弹窗里点「允许」"
        )
        val en = listOf(
            "",
            "NOIR is running inside a container" + (if (known) " (${runtime.label})" else "") +
                ". Containers cannot see the host's USB devices by default.",
            "",
            "1. Linux host: add these two flags to docker run to pass USB through",
            "   --device-cgroup-rule='c 189:* rmw' -v /dev/bus/usb:/dev/bus/usb",
            "   A host adb server holds the phone; stop it first: adb kill-server",
            "",
            "2. macOS / Windows: Docker Desktop cannot pass USB through",
            "   Use the portable build and run NOIR on the host instead",
            "",
            "3. The phone still needs USB debugging on, and its prompt allowed."
        )
        return guideBox(
            if (lang == Lang.EN) "no device detected in the container" else "容器里没有检测到设备",
            if (lang == Lang.EN) en else zh
        )
    }

    fun error(title: String, hint: String? = null): String {
        val total = (Tui.terminalColumns() - 2).coerceIn(40, 100)
        val inner = total - 4

        fun row(text: String) = v() + " " + pad(text, inner) + " " + v()

        val sb = StringBuilder()
        if (ASCII) {
            sb.append("+- ERROR ").append("-".repeat(maxOf(0, total - 10))).append("+").append('\n')
        } else {
            sb.append("╭─ ERROR ").append("─".repeat(total - 10)).append("╮").append('\n')
        }
        wrap(asciiText(title), inner).forEach { sb.append(row(it)).append('\n') }
        if (hint != null) {
            if (ASCII) sb.append("+").append("-".repeat(total - 2)).append("+").append('\n')
            else sb.append('├').append("─".repeat(total - 2)).append('┤').append('\n')
            wrap(asciiText(hint), inner).forEach { sb.append(row(it)).append('\n') }
        }
        if (ASCII) sb.append("+").append("-".repeat(total - 2)).append("+")
        else sb.append('╰').append("─".repeat(total - 2)).append('╯')
        return sb.toString()
    }

    private fun guideBox(title: String, rows: List<String>): String {
        val total = (Tui.terminalColumns() - 2).coerceIn(40, 100)
        val inner = total - 4
        fun row(text: String) = v() + " " + pad(text, inner) + " " + v()

        val sb = StringBuilder()
        val head = (if (ASCII) "+- " else "╭─ ") + title + " "
        val tr = if (ASCII) "+" else "╮"
        sb.append(head).append(h().repeat(maxOf(0, total - width(head) - 1))).append(tr).append('\n')
        rows.forEach { text ->
            if (text.isEmpty()) {
                sb.append(row("")).append('\n')
            } else {
                wrap(asciiText(text), inner).forEach { sb.append(row(it)).append('\n') }
            }
        }
        val bl = if (ASCII) "+" else "╰"
        val br = if (ASCII) "+" else "╯"
        sb.append(bl).append(h().repeat(total - 2)).append(br)
        return sb.toString()
    }

    /**
     * Greedy wrap that prefers spaces (English) but still breaks anywhere when a
     * run of CJK text has no spaces to break on.
     */
    private fun wrap(text: String, width: Int): List<String> {
        val out = ArrayList<String>()
        text.lines().forEach { line ->
            if (line.isEmpty()) {
                out.add("")
                return@forEach
            }
            var start = 0
            while (start < line.length) {
                var i = start
                var used = 0
                var lastSpace = -1
                while (i < line.length) {
                    val cw = charWidth(line[i])
                    if (used + cw > width) break
                    if (line[i] == ' ') lastSpace = i
                    used += cw
                    i++
                }
                if (i >= line.length) {
                    out.add(line.substring(start))
                    break
                }
                if (lastSpace > start) {
                    out.add(line.substring(start, lastSpace))
                    start = lastSpace + 1
                } else {
                    out.add(line.substring(start, i))
                    start = i
                }
            }
        }
        return out
    }

    private fun pad(s: String, width: Int) = s + " ".repeat(maxOf(0, width - width(s)))

    private fun width(s: String) = s.sumOf { charWidth(it) }

    private fun charWidth(c: Char): Int = when {
        c.code in 0x1100..0x115F -> 2
        c.code in 0x2E80..0xA4CF -> 2
        c.code in 0xAC00..0xD7A3 -> 2
        c.code in 0xF900..0xFAFF -> 2
        c.code in 0xFE30..0xFE4F -> 2
        c.code in 0xFF00..0xFF60 -> 2
        c.code in 0xFFE0..0xFFE6 -> 2
        else -> 1
    }
}
