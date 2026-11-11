/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.cli

import app.noir.core.ActionType
import app.noir.core.Advisor
import app.noir.core.AppEntry
import app.noir.core.Cache
import app.noir.core.Category
import app.noir.core.Classifier
import app.noir.core.Domains
import app.noir.core.Device
import app.noir.core.Lang
import app.noir.core.Recommendation
import app.noir.core.Report
import app.noir.core.ReportMeta
import app.noir.core.Rules
import app.noir.core.Tui
import app.noir.core.localized
import app.noir.core.adb.Adb
import app.noir.core.adb.DeviceScan
import app.noir.core.adb.Dumpsys
import app.noir.core.adb.PackagePath
import app.noir.core.apk.ApkScan
import app.noir.core.apk.ManifestAnalyzer
import app.noir.core.apk.ManifestFacts
import app.noir.core.apk.Trackers
import app.noir.core.jadx.Jadx
import app.noir.core.platform.ContainerRuntime
import app.noir.core.platform.Docker
import app.noir.core.platform.Platform
import app.noir.core.platform.Setup
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

private val VERSION: String =
    Guidance::class.java.`package`?.implementationVersion ?: "0.1.0-dev"

private val BOOLEAN_FLAGS = setOf(
    "apply", "yes", "verbose", "help", "about", "version", "ascii", "color", "no-color",
    "quiet", "no-clear", "all", "purge", "json", "fail-on-regression"
)

private val VALUE_FLAGS = setOf(
    "serial", "out", "min", "limit", "only", "adb", "jadx", "top", "jobs", "lang"
)

private val ALL_OPTIONS = BOOLEAN_FLAGS + VALUE_FLAGS

private val FALSE_VALUES = setOf("false", "0", "no", "off", "n")

private var QUIET = false

private fun say(message: String) {
    if (!QUIET) println(message)
}

private class NoirException(message: String, val hint: String? = null) : Exception(message)

private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

private fun requirePackageName(pkg: String): String {
    if (!PACKAGE_NAME.matches(pkg)) {
        throw NoirException(
            t("包名不合法：$pkg", "invalid package name: $pkg"),
            t(
                "只接受 Android 包名，例如 com.example.app。",
                "Expected an Android package name such as com.example.app."
            )
        )
    }
    return pkg
}

private fun currentUser(adb: Adb, device: Device): String =
    adb.shell(device.serial, "am get-current-user", timeoutSec = 15).output
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.toIntOrNull() != null }
        ?: "0"

fun main(args: Array<String>) {
    initLanguage(args.toList())
    val cleaned = args.toList().withoutLanguage().toTypedArray()
    val global = runCatching { Args(cleaned.toList()) }.getOrNull()
    QUIET = global?.flag("quiet") ?: rawBooleanFlag(cleaned, "--quiet")
    ASCII = global?.flag("ascii") ?: rawBooleanFlag(cleaned, "--ascii")
    val verbose = global?.flag("verbose") ?: rawBooleanFlag(cleaned, "--verbose")
    try {
        runMain(cleaned)
    } catch (e: NoirException) {
        System.err.println()
        System.err.println(Guidance.error(e.message ?: t("出错了", "something went wrong"), e.hint))
        exitProcess(1)
    } catch (e: Exception) {
        System.err.println()
        System.err.println(
            Guidance.error(
                e.message ?: e::class.simpleName ?: t("未知错误", "unknown error"),
                t(
                    "加 --verbose 可以看完整堆栈；也可以先运行 noir doctor 检查环境。",
                    "Add --verbose for the full stack trace; or run noir doctor to check the environment."
                )
            )
        )
        if (verbose) e.printStackTrace()
        exitProcess(1)
    }
}

private fun rawBooleanFlag(args: Array<String>, name: String): Boolean =
    args.any { it == name || it == "$name=true" || it == "$name=1" || it == "$name=yes" || it == "$name=on" }

/** Removes the global language option before command dispatch. */
private fun List<String>.withoutLanguage(): List<String> {
    val out = ArrayList<String>(size)
    var index = 0
    while (index < size) {
        val arg = this[index]
        if (arg == "--lang" && index + 1 < size && !this[index + 1].startsWith("--")) {
            index += 2
            continue
        }
        if (arg.startsWith("--lang=")) {
            index++
            continue
        }
        out.add(arg)
        index++
    }
    return out
}

private fun runMain(args: Array<String>) {
    // Validate options before help/version shortcuts or any device access.
    Args(args.toList())

    if (args.any { it == "--help" || it == "-h" } || args.firstOrNull() == "help") {
        usage()
        return
    }
    if (args.any { it == "--version" || it == "-v" } || args.firstOrNull() == "version") {
        println("NOIR $VERSION")
        return
    }
    if (args.any { it == "--about" } || args.firstOrNull() == "about") {
        println(Guidance.about(VERSION))
        return
    }

    val commandIndex = findCommandIndex(args)
    if (commandIndex == null) {
        if (args.isEmpty()) {
            if (isInteractive()) guided() else usage()
        } else {
            usage()
        }
        return
    }

    val command = args[commandIndex]
    val commandArgs = args.filterIndexed { index, _ -> index != commandIndex }
    when (command) {
        "devices" -> cmdDevices(commandArgs)
        "scan" -> cmdScan(commandArgs)
        "diff" -> cmdDiff(commandArgs)
        "auto" -> cmdAuto(commandArgs)
        "ui" -> cmdUi(commandArgs)
        "deep" -> cmdDeep(commandArgs)
        "deepscan" -> cmdDeepScan(commandArgs)
        "block" -> cmdBlock(commandArgs)
        "setup" -> cmdSetup(commandArgs)
        "doctor" -> cmdDoctor(commandArgs)
        else -> {
            System.err.println(t("不认识的命令：$command", "unknown command: $command"))
            usage()
            exitProcess(2)
        }
    }
}

/** Locates the command while skipping value-taking global options. */
private fun findCommandIndex(args: Array<String>): Int? {
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        if (!arg.startsWith("--")) return index
        val name = arg.substring(2).substringBefore('=')
        val hasInlineValue = arg.contains('=')
        index += if (!hasInlineValue && name in VALUE_FLAGS && index + 1 < args.size) 2 else 1
    }
    return null
}

private fun usage() {
    println(
        """
        NOIR — audit what your phone can leak, from the desktop.
        runs on Windows 10/11, macOS and Debian/Ubuntu.

        ${t("第一次用？直接运行 `noir`（不带参数），会进入向导。", "First time? Run `noir` with no arguments for the guided flow.")}
        ${t("想先了解它是什么：noir --about", "To understand what it is: noir --about")}

        usage:
          noir                                guided setup + scan (interactive)
          noir --about                        what this is, what it writes, license
          noir --version                      print the version
          noir doctor                         check adb / jadx / docker / device
          noir setup                          download missing tools into ~/.noir/tools
          noir devices                        list connected devices
          noir scan [options]                 inventory apps, permissions and endpoints
          noir diff <old> <new> [options]     compare two report.json files
          noir auto [options]                 scan + classify + plan
          noir ui [options]                   same, rendered as a dashboard
          noir deep <package> [options]       pull one APK, run jadx, grep for red flags
          noir deepscan [options]             decompile the riskiest apps, then offer cleanup
          noir block <package...> [options]   print (or apply) neutralisation commands

        options:
          --serial <id>      target a specific device
          --out <dir>        output directory (default: noir-out)
          --min <score>      only keep apps at or above this score
          --limit <n>        cap reported apps (default 500)
          --only <regex>     restrict scanning to matching package names
          --adb <path>       override adb binary
          --jadx <path>      override jadx binary

        diff:
          --json                  emit a machine-readable diff
          --fail-on-regression    exit 1 when apps, scores, permissions or endpoints increase

        auto / deepscan:
          --apply            execute the safe, reversible app-ops actions
          --yes              skip confirmation prompts (automation)

        deepscan only:
          --top <n>          how many apps to decompile (default 15)
          --all              decompile every flagged app (slow, lots of disk)
          --jobs <n>         parallel jadx processes (default: cpu/2, max 6)
          --purge            also offer to uninstall risky user apps

        display:
          --lang <auto|zh|en> interface language (device locale, then host locale; or NOIR_LANG)
          --ascii            plain ASCII borders (legacy Windows console)
          --color            force ANSI colour (auto-detected on a TTY; off on legacy cmd.exe)
          --no-color         disable ANSI colour
          --quiet            suppress scan progress
          --no-clear         do not clear the screen before the dashboard
        """.trimIndent()
    )
}

// ── guided first run ───────────────────────────────────────────────────────

private fun bannerSmall() {
    println(
        """
        |  ███╗   ██╗ ██████╗ ██╗██████╗
        |  ████╗  ██║██╔═══██╗██║██╔══██╗
        |  ██╔██╗ ██║██║   ██║██║██████╔╝
        |  ██║╚██╗██║██║   ██║██║██╔══██╗
        |  ██║ ╚████║╚██████╔╝██║██║  ██║
        |  ╚═╝  ╚═══╝ ╚═════╝ ╚═╝╚═╝  ╚═╝
        """.trimMargin()
    )
}

private fun guided() {
    val adb = ensureAdbInteractive()
    val device = awaitDevice(adb, null, interactive = true)
    applyDeviceLanguage(adb, device)

    bannerSmall()
    println(t("  首次运行向导 · Ctrl+C 随时退出", "  first-run guide · Ctrl+C to quit at any time"))
    println()
    println(
        t(
            "  设备：${device.model.ifBlank { device.serial }}  (${device.serial})",
            "  device: ${device.model.ifBlank { device.serial }}  (${device.serial})"
        )
    )
    print(t("  开始扫描并生成报告? [Y/n] ", "  scan and write a report? [Y/n] "))
    val proceed = readLine()?.trim()?.lowercase()
    if (proceed == "n" || proceed == "no") {
        println(t("  已取消。", "  cancelled."))
        return
    }

    val args = Args(emptyList())
    val result = performScan(args)
    val recommendations = effectiveRecommendations(adb, result, Advisor.advise(result.apps, LANG))
    writeReports(result, recommendations)

    println()
    print(renderDashboard(args, result, recommendations))

    val planned = effectiveActions(adb, result, recommendations)
    println()
    println(
        t(
            "  可以安全执行 ${planned.size} 条（appops，可回滚）",
            "  ${planned.size} safe action(s) available (app-ops, reversible)"
        )
    )
    if (planned.isEmpty()) {
        println(t("  没有需要自动处理的项。", "  nothing needs an automatic action."))
        return
    }
    print(t("  现在执行? [y/N] ", "  apply now? [y/N] "))
    val apply = readLine()?.trim()?.lowercase()
    if (apply == "y" || apply == "yes") {
        if (applyCommands(adb, result, planned) > 0) exitProcess(1)
    } else {
        println(
            t(
                "  已跳过。之后可以运行：noir auto --apply",
                "  skipped. You can run `noir auto --apply` later."
            )
        )
    }
}

private fun ensureAdbInteractive(): Adb {
    Platform.findAdb()?.let { return Adb(it) }

    println(t("  没有找到 adb。", "  adb was not found."))
    println(Guidance.adbMissing())
    print(t("  要现在下载 Android platform-tools（约 15 MB）吗? [Y/n] ", "  download Android platform-tools now (~15 MB)? [Y/n] "))
    val answer = readLine()?.trim()?.lowercase()
    if (answer == "n" || answer == "no") {
        throw NoirException(t("需要 adb 才能继续。", "adb is required to continue."), Guidance.adbMissing())
    }

    print(t("  下载中 ", "  downloading "))
    val path = Setup.installAdb { read, total -> renderDownload(read, total) }
    println()
    if (path == null) {
        throw NoirException(
            t("adb 下载失败。", "adb download failed."),
            t("检查网络后重试，或手动安装 adb。", "Check the network and retry, or install adb manually.")
        )
    }
    println(t("  已安装：$path", "  installed: $path"))
    return Adb(path)
}

private fun renderDownload(read: Long, total: Long) {
    if (QUIET) return
    if (total > 0) {
        print("\r  ${read * 100 / total}%  ${read / 1_048_576}/${total / 1_048_576} MB   ")
    } else {
        print("\r  ${read / 1_048_576} MB   ")
    }
    System.out.flush()
}

// ── commands ───────────────────────────────────────────────────────────────

private fun cmdDevices(raw: List<String>) {
    val args = Args(raw)
    val adb = connectAdb(args)
    val filter = args.value("serial")
    val devices = adb.devices().let { all -> if (filter == null) all else all.filter { it.serial == filter } }
    if (devices.isEmpty()) {
        println(noDeviceGuide())
        return
    }
    devices.firstOrNull { it.state == "device" }?.let { applyDeviceLanguage(adb, it) }
    println("%-22s %-14s %-22s %s".format("SERIAL", "STATE", "MODEL", "PRODUCT"))
    devices.forEach {
        println("%-22s %-14s %-22s %s".format(it.serial, it.state, it.model, it.product))
    }
    if (devices.any { it.state == "unauthorized" }) {
        println()
        println(Guidance.unauthorizedGuide())
    }
}

internal data class ScanResult(
    val device: Device,
    val apps: List<AppEntry>,
    val outDir: File,
    val android: String,
    val installed: Int,
    val paths: Map<String, String>,
    val meta: ReportMeta,
    val adb: Adb
)

private fun cmdScan(raw: List<String>) {
    val args = Args(raw)
    val result = performScan(args)
    writeReports(result, emptyList())

    println()
    println("%5s  %-12s %-10s %-44s %s".format("SCORE", "CATEGORY", "VENDOR", "PACKAGE", "PERM/UPLOAD/CMD"))
    result.apps.take(40).forEach { a ->
        println(
            "%5d  %s %-10s %-44s %d/%d/%d".format(
                a.score, Tui.padRight(a.category.localized(LANG), 12), a.vendor ?: "—", a.pkg,
                a.permissions.size, a.uploadEndpoints.size, a.commandEndpoints.size
            )
        )
    }
    println()
    println(Tui.clip("[+] ${result.outDir.absolutePath}", ascii = ASCII))
}

private fun cmdDiff(raw: List<String>) {
    val args = Args(raw)
    if (args.positional.size != 2) {
        throw NoirException(
            t(
                "用法：noir diff <old-report.json> <new-report.json>",
                "usage: noir diff <old-report.json> <new-report.json>"
            ),
            t(
                "也可以直接传两个扫描输出目录，例如 noir diff noir-out-old noir-out-new。",
                "You can also pass two scan output directories, e.g. noir diff noir-out-old noir-out-new."
            )
        )
    }

    fun reportFile(path: String): File {
        val file = File(path)
        return if (file.isDirectory) File(file, "report.json") else file
    }

    val oldFile = reportFile(args.positional[0])
    val newFile = reportFile(args.positional[1])
    if (!oldFile.isFile) {
        throw NoirException(
            t("找不到旧报告：${oldFile.path}", "old report not found: ${oldFile.path}"),
            t("先运行 noir scan --out <dir>。", "Run noir scan --out <dir> first.")
        )
    }
    if (!newFile.isFile) {
        throw NoirException(
            t("找不到新报告：${newFile.path}", "new report not found: ${newFile.path}"),
            t("先运行 noir scan --out <dir>。", "Run noir scan --out <dir> first.")
        )
    }

    val result = diffReports(readReport(oldFile.path), readReport(newFile.path))
    println(if (args.flag("json")) renderDiffJson(result) else renderDiff(result))
    if (args.flag("fail-on-regression") && result.regressions) exitProcess(1)
}

private fun cmdAuto(raw: List<String>) {
    val args = Args(raw)
    val result = performScan(args)
    val recommendations = effectiveRecommendations(result.adb, result, Advisor.advise(result.apps, LANG))
    writeReports(result, recommendations)

    println()
    print(renderDashboard(args, result, recommendations))
    println()
    println(t("== 建议动作 (前 15) ==", "== recommended actions (first 15) =="))
    recommendations.take(15).forEach { r ->
        println("  %-5s %s %-46s %s".format(r.risk, Tui.padRight(r.action.localized(LANG), 16), r.pkg, r.reason))
    }

    val planned = effectiveActions(result.adb, result, recommendations)
    println()
    println(
        t(
            "== 将执行（${planned.size} 条，全部可回滚）==",
            "== will run (${planned.size}, all reversible) =="
        )
    )
    planned.forEach { println("  ${it.setCommand(result.meta.user)}") }
    println()
    println(Tui.clip("[+] ${result.outDir.absolutePath}", ascii = ASCII))

    if (!args.flag("apply")) {
        println()
        println(
            t(
                "[*] dry-run — 加 --apply 执行这 ${planned.size} 条",
                "[*] dry-run — add --apply to execute these ${planned.size}"
            )
        )
        return
    }

    if (!args.flag("yes")) {
        if (!isInteractive()) {
            throw NoirException(
                t(
                    "--apply 在非交互环境下需要同时加 --yes",
                    "--apply needs --yes when stdin is not interactive"
                )
            )
        }
        print(t("执行 ${planned.size} 条 appops 动作? [y/N] ", "apply ${planned.size} app-ops action(s)? [y/N] "))
        val answer = readLine()?.trim()?.lowercase()
        if (answer != "y" && answer != "yes") {
            println(t("已取消", "aborted"))
            return
        }
    }
    if (applyCommands(connectAdb(args), result, planned) > 0) exitProcess(1)
}

private fun cmdUi(raw: List<String>) {
    val args = Args(raw)
    val result = performScan(args)
    val recommendations = effectiveRecommendations(result.adb, result, Advisor.advise(result.apps, LANG))
    writeReports(result, recommendations)

    if (!args.flag("no-clear") && System.console() != null) {
        print("\u001b[2J\u001b[H")
    }
    println()
    print(renderDashboard(args, result, recommendations))
    println()
    println(Tui.clip("  report  ${result.outDir.absolutePath}", ascii = ASCII))
    println()
}

private fun cmdDeep(raw: List<String>) {
    val args = Args(raw)
    val pkg = requirePackageName(
        args.positional.firstOrNull()
            ?: throw NoirException(
                t("缺少包名。", "missing package name."),
                t(
                    "用法：noir deep <package> [--serial S] [--out DIR]",
                    "usage: noir deep <package> [--serial S] [--out DIR]"
                )
            )
    )

    val adb = connectAdb(args)
    val device = awaitDevice(adb, args.value("serial"), interactive = isInteractive())
    applyDeviceLanguage(adb, device)
    val outDir = File(args.value("out") ?: "noir-out/deep-${pkg.replace('.', '_')}").apply { mkdirs() }

    val remotes = adb.shell(device.serial, "pm path $pkg").output
        .lineSequence()
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").trim() }
        .filter { it.isNotEmpty() }
        .toList()
    if (remotes.isEmpty()) {
        throw NoirException(
            t("这台设备上没有安装 $pkg。", "$pkg is not installed on this device."),
            t("先运行 noir scan 看看实际包名。", "Run noir scan first to see the actual package names.")
        )
    }

    val jadxBin = Platform.findJadx(args.value("jadx"))
        ?: throw NoirException(t("没有找到 jadx。", "jadx was not found."), Guidance.jadxMissing())

    val urls = LinkedHashSet<String>()
    val sourceDirs = ArrayList<File>()
    println("[*] pulling ${remotes.size} APK(s)")
    remotes.forEachIndexed { index, remote ->
        val suffix = if (index == 0) "" else "-$index"
        val apk = File(outDir, "$pkg$suffix.apk")
        apk.delete()
        val pulled = adb.pull(device.serial, remote, apk)
        if (!pulled.ok || !apk.isFile) {
            throw NoirException(
                t("APK 拉取失败：$pkg", "failed to pull APK: $pkg"),
                t("检查设备连接后重试。", "Check the device connection and retry.")
            )
        }
        urls += ApkScan.urls(apk)

        val src = File(outDir, if (index == 0) "src" else "src-$index")
        src.deleteRecursively()
        println("[*] decompiling split $index with $jadxBin ...")
        val code = Jadx(jadxBin).decompile(apk, src)
        if (code != 0 && !File(src, "sources").isDirectory) {
            throw NoirException(
                t("jadx 反编译失败（exit=$code）。", "jadx failed (exit=$code)."),
                t("检查 jadx 或重试。", "Check jadx or retry.")
            )
        }
        sourceDirs += src
    }

    println("[*] endpoints: ${urls.size}")
    urls.filter { Rules.isUpload(it) || Rules.isCommand(it) }.forEach { println("    ! $it") }

    val hits = sourceDirs.flatMap {
        Jadx(jadxBin).grep(
            it,
            listOf(
                "Runtime\\.exec|Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec",
                "ProcessBuilder", "setApplicationEnabledSetting",
                "enabled_accessibility_services", "DexClassLoader", "loadLibrary",
                "https?://", "verify\\(", "Cipher", "getInstalledPackages",
                "getRunningTasks", "MediaProjection"
            )
        )
    }
    println("[*] hits: ${hits.size}")
    hits.take(40).forEach { println("    ${it.file}:${it.line}  ${it.text}") }
    sourceDirs.forEach { println("[+] sources: ${it.absolutePath}") }
}

private fun cmdBlock(raw: List<String>) {
    val args = Args(raw)
    val pkgs = args.positional.map { requirePackageName(it) }
    if (pkgs.isEmpty()) {
        throw NoirException(
            t("缺少包名。", "missing package name."),
            t(
                "用法：noir block <package...> [--apply]",
                "usage: noir block <package...> [--apply]"
            )
        )
    }

    val adb = connectAdb(args)
    val device = awaitDevice(adb, args.value("serial"), interactive = isInteractive())
    applyDeviceLanguage(adb, device)
    val user = currentUser(adb, device)

    // `block` is a per-user uninstall. AppOps on an uninstalled package are
    // guaranteed no-ops, so they are not part of this command.
    val actions = pkgs.map { "pm uninstall --user $user $it" }
    val rollback = pkgs.map {
        "adb -s ${device.serial} shell pm install-existing --user $user $it"
    }

    println("# rollback (restores the APK, not the app data)")
    rollback.forEach { println("#   $it") }
    println()
    actions.forEach { println("adb -s ${device.serial} shell $it") }

    if (!args.flag("apply")) {
        println()
        println(t("# dry-run — 加 --apply 执行", "# dry-run — add --apply to execute"))
        return
    }

    val outDir = File(args.value("out") ?: "noir-out").apply { mkdirs() }
    File(outDir, "block-rollback.txt").writeText(
        buildString {
            appendLine("# NOIR block rollback")
            appendLine("# generated: ${OffsetDateTime.now()}")
            appendLine("# device: ${device.model} (${device.serial}) · user $user")
            appendLine("# pm install-existing restores the APK; app data is not restored")
            rollback.forEach { appendLine(it) }
        }
    )

    println()
    var failed = 0
    actions.forEach { action ->
        print("> $action ... ")
        val result = adb.shell(device.serial, action)
        if (!result.ok) failed++
        println(if (result.ok) "ok" else "failed (${result.code}) ${result.output.trim().take(120)}")
    }
    println("[*] rollback: ${File(outDir, "block-rollback.txt").absolutePath}")
    if (failed > 0) {
        System.err.println(t("$failed 条命令失败。", "$failed command(s) failed."))
        exitProcess(1)
    }
}

private fun cmdSetup(raw: List<String>) {
    val args = Args(raw)
    val autoYes = args.flag("yes")

    println("NOIR setup")
    println("  platform  ${Platform.describe()}")

    val adb = Platform.findAdb(args.value("adb"))
    if (adb != null) {
        println("  adb       $adb")
    } else {
        println(t("  adb       未找到", "  adb       not found"))
        if (!autoYes && !confirm(t("  下载 Android platform-tools（约 15 MB）? [Y/n] ", "  download Android platform-tools (~15 MB)? [Y/n] "))) {
            println(Guidance.adbMissing())
        } else {
            print(t("  下载中 ", "  downloading "))
            val path = Setup.installAdb { read, total -> renderDownload(read, total) }
            println()
            if (path == null) {
                throw NoirException(
                    t("adb 下载失败。", "adb download failed."),
                    t("检查网络后重试。", "Check the network and retry.")
                )
            }
            println("  adb       $path")
        }
    }

    val jadx = Platform.findJadx(args.value("jadx"))
    if (jadx != null) {
        println("  jadx      $jadx")
    } else {
        println(t("  jadx      未找到（只有 deep 命令需要）", "  jadx      not found (only needed by deep)"))
        if (!autoYes && !confirm(t("  下载 jadx（约 73 MB）? [Y/n] ", "  download jadx (~73 MB)? [Y/n] "))) {
            println(Guidance.jadxMissing())
        } else {
            print(t("  下载中 ", "  downloading "))
            val path = Setup.installJadx { read, total -> renderDownload(read, total) }
            println()
            if (path == null) {
                throw NoirException(
                    t("jadx 下载失败。", "jadx download failed."),
                    t("检查网络后重试。", "Check the network and retry.")
                )
            }
            println("  jadx      $path")
        }
    }

    println()
    println(t("完成。下一步：noir doctor", "done. Next: noir doctor"))
}

private fun cmdDoctor(raw: List<String>) {
    val args = Args(raw)
    println("NOIR doctor")
    println("  platform  ${Platform.describe()}")
    println("  java      ${System.getProperty("java.version")}")

    // Docker is optional: report it before anything that can end the check, never fail on it.
    CONTAINER?.let { println(Guidance.container(it)) }
    val docker = Docker.probe()
    // Inside a container the Docker CLI is normally absent; "not installed" would only confuse.
    if (docker.installed || CONTAINER == null) println(Guidance.docker(docker))

    val adbPath = Platform.findAdb(args.value("adb"))
    if (adbPath == null) {
        println(t("  adb       未找到", "  adb       not found"))
        println(Guidance.adbMissing())
        exitProcess(1)
    }
    val adb = Adb(adbPath)
    println("  adb       $adbPath")
    val version = adb.exec(listOf("version"), timeoutSec = 15)
    if (!version.ok) {
        println(t("  adb      无法运行", "  adb      failed to run"))
        exitProcess(1)
    }
    version.output.lineSequence()
        .firstOrNull { it.startsWith("Version") }
        ?.let { println("            $it") }

    val devices = adb.devices()
    devices.firstOrNull { it.state == "device" }?.let { applyDeviceLanguage(adb, it) }
    if (devices.isEmpty()) {
        println(t("  device    未连接", "  device    not connected"))
        println()
        println(noDeviceGuide())
        exitProcess(1)
    } else {
        devices.forEach { println("  device    ${it.serial}  ${it.state}  ${it.model}") }
        if (devices.any { it.state == "unauthorized" }) {
            println()
            println(Guidance.unauthorizedGuide())
            exitProcess(1)
        }
    }

    val jadx = Platform.findJadx(args.value("jadx"))
    if (jadx == null) {
        println(t("  jadx      未找到（只有 deep 需要）", "  jadx      not found (only needed by deep)"))
    } else {
        println("  jadx      $jadx")
    }
}

private fun confirm(prompt: String): Boolean {
    print(prompt)
    val answer = readLine()?.trim()?.lowercase()
    return answer != "n" && answer != "no"
}

// ── scan pipeline ──────────────────────────────────────────────────────────

private fun performScan(args: Args, limitOverride: Int? = null): ScanResult {
    val adb = connectAdb(args)
    val device = awaitDevice(adb, args.value("serial"), interactive = isInteractive())
    applyDeviceLanguage(adb, device)
    val outDir = File(args.value("out") ?: "noir-out").apply { mkdirs() }
    val minScore = intOption(args, "min", 0, 0)
    val limit = limitOverride ?: intOption(args, "limit", 500, 0)

    say("[*] host: ${Platform.describe()}")
    say("[*] device: ${device.serial} (${device.model})")

    val paths = DeviceScan.packagePaths(adb, device.serial)
    say("[*] packages: ${paths.size}")

    if (!QUIET) print("[*] reading dumpsys package ... ")
    val dump = adb.shell(device.serial, "dumpsys package", timeoutSec = 600)
    say("${dump.output.length / 1024} KiB")

    val meta = Dumpsys.parse(dump.output)
    say("[*] parsed: ${meta.size}")

    val only = args.value("only")?.let {
        runCatching { Regex(it) }.getOrElse {
            throw NoirException(
                t("--only 不是合法的正则表达式。", "--only is not a valid regular expression."),
                t("检查后重试，例如 --only '^com\\.tencent\\.'。", "Check it and retry, e.g. --only '^com\\.tencent\\.'.")
            )
        }
    }
    val candidates = meta.entries.filter { (pkg, m) ->
        m.perms.contains("INTERNET") &&
            m.perms.count { it in Rules.SENSITIVE } > 0 &&
            paths.containsKey(pkg) &&
            (only == null || only.containsMatchIn(pkg))
    }
    say("[*] candidates (internet + sensitive): ${candidates.size}")

    val android = adb.shell(device.serial, "getprop ro.build.version.release").output.trim()
    val reportMeta = readReportMeta(adb, device, android).copy(minScore = minScore, limit = limit)

    val targets = candidates.map { PackagePath(it.key, paths.getValue(it.key)) }
    val endpoints = HashMap<String, List<String>>()
    val missing = ArrayList<PackagePath>()
    val cacheScope = "${device.serial}|${reportMeta.fingerprint}|${reportMeta.sdk}"

    targets.forEach { target ->
        val hit = Cache.get(target.pkg, target.path, cacheScope)
        if (hit != null) endpoints[target.pkg] = hit else missing += target
    }

    if (missing.isNotEmpty()) {
        say("[*] analysing ${missing.size} new/changed APKs (cache hits: ${targets.size - missing.size})")
        val started = System.currentTimeMillis()
        val fresh = DeviceScan.urls(adb, device.serial, missing, File(outDir, "work")) { done, total ->
            if (!QUIET) {
                print(progressLine(started, done, total))
                System.out.flush()
            }
        }
        if (!QUIET) println()
        missing.forEach { target ->
            val found = fresh.urls[target.pkg]
            if (found != null) {
                endpoints[target.pkg] = found
                Cache.put(target.pkg, target.path, found, cacheScope)
            } else {
                // Do not cache a pull/parse failure as "no endpoints".
                endpoints[target.pkg] = emptyList()
            }
        }
        Cache.flush()
        if (fresh.failed.isNotEmpty()) {
            say("[!] ${fresh.failed.size} APK(s) could not be analysed; they will be retried next scan")
        }
    } else {
        say("[*] all endpoints served from cache")
    }

    val apps = candidates.map { (pkg, m) ->
        val perms = m.perms.filter { it in Rules.SENSITIVE }
        val urls = endpoints[pkg].orEmpty()
        AppEntry(
            pkg = pkg,
            uid = m.appId,
            isSystem = m.isSystem,
            debuggable = m.debuggable,
            installer = m.installer,
            permissions = perms,
            endpoints = urls,
            score = Rules.score(perms, urls, m.isSystem, m.debuggable),
            category = Classifier.category(pkg, urls, perms, m.isSystem),
            vendor = Classifier.vendor(pkg, urls),
            sharedUid = m.sharedUser
        )
    }
        .filter { it.score >= minScore }
        .sortedByDescending { it.score }
        .take(limit)

    return ScanResult(device, apps, outDir, android, paths.size, paths, reportMeta, adb)
}

private fun readReportMeta(adb: Adb, device: Device, android: String): ReportMeta {
    fun prop(key: String): String =
        adb.shell(device.serial, "getprop $key", timeoutSec = 15).output.trim()

    val userOut = adb.shell(device.serial, "am get-current-user", timeoutSec = 15).output
    val user = userOut.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.toIntOrNull() != null }
        ?: "0"

    return ReportMeta(
        tool = "NOIR $VERSION",
        host = Platform.describe(),
        android = android,
        sdk = prop("ro.build.version.sdk"),
        build = prop("ro.build.display.id").ifBlank { prop("ro.build.id") },
        fingerprint = prop("ro.build.fingerprint"),
        user = user,
        generatedAt = OffsetDateTime.now().toString()
    )
}

private fun writeReports(result: ScanResult, recommendations: List<Recommendation>) {
    File(result.outDir, "report.json").writeText(
        Report.json(result.device, result.apps, recommendations, result.meta, LANG)
    )
    File(result.outDir, "report.md").writeText(
        Report.markdown(result.device, result.apps, recommendations, result.meta, LANG)
    )
    File(result.outDir, "report.html").writeText(
        Report.html(result.device, result.apps, recommendations, result.meta, LANG)
    )
    writeDomainFiles(result.outDir, result.apps)
    val effective = effectiveActions(result.adb, result, recommendations)
    File(result.outDir, "plan.txt").writeText(
        effective.joinToString("\n") { it.setCommand(result.meta.user) } + if (effective.isEmpty()) "" else "\n"
    )
    // rollback.txt is written by applyCommands from the actual before-state.
    // A dry run or a later scan must never erase the last real rollback record.
}

private fun writeDomainFiles(outDir: File, apps: List<AppEntry>) {
    val export = Domains.export(apps)
    val date = java.time.LocalDate.now().toString()

    fun render(title: String, note: String, hosts: List<String>): String =
        buildString {
            append("# NOIR — ").append(title).append('\n')
            append("# generated ").append(date).append(" · ").append(hosts.size).append(" hosts\n")
            append("# ").append(note).append('\n')
            append("#\n")
            hosts.forEach { append(it).append('\n') }
        }

    val trackers = render(
        "tracker domains",
        "analytics / ads / crash reporting. Safe to block: these do not carry user-facing features.",
        export.trackers
    )
    val review = render(
        "unclassified domains",
        "review before blocking: these may be app APIs, maps, security updates or third-party SDKs.",
        export.review
    )
    val functional = render(
        "functional domains",
        "DO NOT BLOCK: push, login, updates, payments, app stores, cloud sync and connectivity checks.",
        export.functional
    )

    File(outDir, "domains-trackers.txt").writeText(trackers)
    File(outDir, "domains-other.txt").writeText(review)
    File(outDir, "domains-functional.txt").writeText(functional)

    // Backwards-compatible name, now containing only the safe tier.
    File(outDir, "domains.txt").writeText(trackers)
}

internal data class AppOpAction(val pkg: String, val op: String) {
    fun setCommand(user: String?): String =
        "cmd appops set${userArg(user)} $pkg $op ignore"

    fun getCommand(user: String?): String =
        "cmd appops get${userArg(user)} $pkg $op"

    fun rollbackCommand(serial: String, user: String?, mode: String = "default"): String =
        "adb -s $serial shell cmd appops set${userArg(user)} $pkg $op ${mode.ifBlank { "default" }}"

    private fun userArg(user: String?): String =
        if (user.isNullOrBlank()) "" else " --user $user"
}

private fun plannedActions(recommendations: List<Recommendation>): List<AppOpAction> =
    recommendations.filter { it.autoSafe }.mapNotNull { r ->
        when (r.action) {
            ActionType.APPOP_PROJECT_MEDIA -> AppOpAction(r.pkg, "PROJECT_MEDIA")
            ActionType.APPOP_USAGE_STATS -> AppOpAction(r.pkg, "GET_USAGE_STATS")
            else -> null
        }
    }.distinct()

internal fun parseAppOpMode(output: String, op: String): String? {
    val explicit = Regex("(?m)^\\s*${Regex.escape(op)}:\\s*([a-zA-Z_]+)").find(output)
    if (explicit != null) return explicit.groupValues[1]
    val default = Regex("(?m)^\\s*Default mode:\\s*([a-zA-Z_]+)").find(output)
    return default?.groupValues?.get(1)
}

/**
 * Drops AppOp recommendations that are already in their target state. A
 * declared capability is not a change: PROJECT_MEDIA is only relevant when the
 * app currently holds the permanent screen-capture grant.
 */
internal fun effectiveRecommendations(
    adb: Adb,
    result: ScanResult,
    recommendations: List<Recommendation>
): List<Recommendation> {
    val user = result.meta.user.ifBlank { null }
    return recommendations.filter { recommendation ->
        val op = when (recommendation.action) {
            ActionType.APPOP_PROJECT_MEDIA -> "PROJECT_MEDIA"
            ActionType.APPOP_USAGE_STATS -> "GET_USAGE_STATS"
            else -> return@filter true
        }
        val query = adb.shell(
            result.device.serial,
            AppOpAction(recommendation.pkg, op).getCommand(user),
            timeoutSec = 30
        )
        if (!query.ok) return@filter true
        val current = parseAppOpMode(query.output, op) ?: return@filter true
        when (recommendation.action) {
            ActionType.APPOP_PROJECT_MEDIA -> current == "allow"
            ActionType.APPOP_USAGE_STATS -> current != "ignore"
            else -> true
        }
    }
}

/**
 * Keeps only AppOp actions whose target mode differs from the effective mode
 * already present on the device. A capability declaration is not a change.
 */
internal fun effectiveActions(
    adb: Adb,
    result: ScanResult,
    recommendations: List<Recommendation>
): List<AppOpAction> {
    val user = result.meta.user.ifBlank { null }
    return plannedActions(recommendations).filter { action ->
        val query = adb.shell(result.device.serial, action.getCommand(user), timeoutSec = 30)
        if (!query.ok) return@filter true
        val current = parseAppOpMode(query.output, action.op)
        current == null || current != "ignore"
    }
}

private fun flatOutput(output: String): String =
    output.trim().replace("\n", " | ").ifBlank { "(no output)" }

private fun classifyAppOpFailure(output: String): String = when {
    output.contains("device offline", ignoreCase = true) ||
        output.contains("no devices", ignoreCase = true) ||
        output.contains("device not found", ignoreCase = true) -> "adb connection"
    output.contains("SecurityException", ignoreCase = true) ||
        output.contains("Permission Denial", ignoreCase = true) ||
        output.contains("not allowed", ignoreCase = true) -> "system permission"
    output.contains("Unknown operation", ignoreCase = true) ||
        output.contains("Unknown op", ignoreCase = true) -> "unknown AppOp"
    output.contains("No such package", ignoreCase = true) ||
        (output.contains("package", ignoreCase = true) && output.contains("not found", ignoreCase = true)) -> "package/user"
    else -> "rejected"
}

internal fun applyCommands(adb: Adb, result: ScanResult, planned: List<AppOpAction>): Int {
    if (planned.isEmpty()) return 0
    val user = result.meta.user.ifBlank { null }
    val applied = ArrayList<String>()
    val rollback = loadRollback(result.outDir)
    var failed = 0
    var permissionFailures = 0

    applied.add("# NOIR applied commands")
    applied.add("# generated: ${result.meta.generatedAt}")
    applied.add("# tool: ${result.meta.tool}")
    applied.add("# host: ${result.meta.host}")
    applied.add("# device: ${result.device.model} (${result.device.serial}) · ${result.device.product}")
    applied.add("# android: ${result.meta.android} (SDK ${result.meta.sdk})")
    applied.add("# build: ${result.meta.build}")
    applied.add("# fingerprint: ${result.meta.fingerprint}")
    applied.add("# user: ${result.meta.user.ifBlank { "unknown (default user)" }}")
    applied.add("#")

    println()
    planned.forEach { action ->
        val before = adb.shell(result.device.serial, action.getCommand(user), timeoutSec = 30)
        val res = adb.shell(result.device.serial, action.setCommand(user), timeoutSec = 30)
        val after = adb.shell(result.device.serial, action.getCommand(user), timeoutSec = 30)
        val status = when {
            res.timedOut -> "timeout (${res.code})"
            res.ok -> "ok (0)"
            else -> "failed (${res.code})"
        }
        val kind = if (!res.ok) classifyAppOpFailure(res.output) else ""
        if (!res.ok) {
            failed++
            if (kind == "system permission") permissionFailures++
        }

        applied.add("${action.setCommand(user)}\t$status${if (kind.isNotBlank()) " [$kind]" else ""}")
        applied.add("  before: ${flatOutput(before.output)}")
        applied.add("  after:  ${flatOutput(after.output)}")
        if (res.output.isBlank()) {
            applied.add("  output: (empty)")
        } else {
            applied.add("  output:")
            res.output.trimEnd().lineSequence().forEach { applied.add("    $it") }
        }

        println("  %-72s %s%s".format(action.setCommand(user), status, if (kind.isNotBlank()) " [$kind]" else ""))
        if (!res.ok) {
            val lines = res.output.trim().lineSequence().filter { it.isNotBlank() }.toList()
            lines.take(4).forEach { println("      $it") }
            if (lines.size > 4) println("      ... (${lines.size - 4} more lines in applied.txt)")
        }

        if (res.ok) {
            val prior = parseAppOpMode(before.output, action.op) ?: "default"
            val key = "${action.pkg}|${action.op}"
            // Re-applying an already-ignored op must not overwrite the original
            // rollback mode with "ignore".
            if (prior != "ignore" || key !in rollback) {
                rollback[key] = action.rollbackCommand(result.device.serial, user, prior)
            }
        }
    }

    File(result.outDir, "applied.txt").writeText(applied.joinToString("\n", postfix = "\n"))

    val rollbackText = buildString {
        appendLine("# NOIR rollback")
        appendLine("# generated: ${result.meta.generatedAt}")
        appendLine("# tool: ${result.meta.tool}")
        appendLine("# merged across runs; only successful changes are listed")
        appendLine("# device: ${result.device.model} (${result.device.serial}) · user ${result.meta.user.ifBlank { "unknown" }}")
        if (rollback.isEmpty()) {
            appendLine("# no appops changes were applied")
        } else {
            rollback.values.forEach { appendLine(it) }
        }
    }
    File(result.outDir, "rollback.txt").writeText(rollbackText)
    File(result.outDir, "applied.txt").let { println("\n[*] applied: ${it.absolutePath}") }
    println("[*] rollback: ${File(result.outDir, "rollback.txt").absolutePath}")
    println("[*] ${planned.size - failed}/${planned.size} appops commands executed")

    if (failed > 0) {
        println("[!] $failed command(s) failed — full stdout/stderr is in applied.txt")
        if (permissionFailures > 0) {
            println("[i] The device rejected shell-level AppOps changes. On ColorOS/OPPO/OnePlus,")
            println("    check Developer options → Permission monitoring, or use Shizuku.")
        }
    }
    return failed
}

private val ROLLBACK_LINE = Regex(
    "^adb(?: -s \\S+)? shell cmd appops set(?: --user \\S+)? (\\S+) (\\S+) (\\S+)$"
)

private fun loadRollback(outDir: File): LinkedHashMap<String, String> {
    val result = LinkedHashMap<String, String>()
    val file = File(outDir, "rollback.txt")
    if (!file.isFile) return result
    file.forEachLine { line ->
        val match = ROLLBACK_LINE.find(line) ?: return@forEachLine
        val (pkg, op, mode) = match.destructured
        result["$pkg|$op"] = line
    }
    return result
}

internal fun ansiSupported(
    windows: Boolean = Platform.isWindows,
    env: Map<String, String> = System.getenv()
): Boolean {
    if (windows) {
        return !env["WT_SESSION"].isNullOrBlank() ||
            !env["TERM_PROGRAM"].isNullOrBlank() ||
            !env["ANSICON"].isNullOrBlank() ||
            env["ConEmuANSI"].equals("ON", ignoreCase = true) ||
            (!env["TERM"].isNullOrBlank() && !env["TERM"].equals("dumb", ignoreCase = true))
    }
    return !env["TERM"].equals("dumb", ignoreCase = true)
}

private fun renderDashboard(args: Args, result: ScanResult, recommendations: List<Recommendation>): String {
    val ascii = args.flag("ascii")
    val forceColor = args.flag("color")
    val noColor = args.flag("no-color") || System.getenv("NO_COLOR") != null
    val color = !ascii && (
        forceColor || (System.console() != null && !noColor && ansiSupported())
    )
    return Tui.dashboard(
        device = result.device,
        apps = result.apps,
        recommendations = recommendations,
        android = result.android,
        host = Platform.describe(),
        installed = result.installed,
        ascii = ascii,
        color = color,
        lang = LANG
    )
}

private fun progressLine(startedMs: Long, done: Int, total: Int): String {
    val elapsed = (System.currentTimeMillis() - startedMs).coerceAtLeast(1)
    val perItem = elapsed.toDouble() / done.coerceAtLeast(1)
    val eta = ((total - done) * perItem / 1000).toLong().coerceAtLeast(0)
    val width = 26
    val pct = done * 100 / total
    val filled = (done.toLong() * width / total).toInt().coerceIn(0, width)
    val bar = if (ASCII) {
        "#".repeat(filled) + ".".repeat(width - filled)
    } else {
        "█".repeat(filled) + "░".repeat(width - filled)
    }
    val spin = if (ASCII) "|/-\\"[done % 4] else "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"[done % 10]
    return "\r  " + bar + " " + pct + "%  " + done + "/" + total +
        "  " + (if (ASCII) "|" else "·") + "  ETA " +
        (eta / 60) + ":" + (eta % 60).toString().padStart(2, '0') + "  " + spin
}


// ── deep scan ──────────────────────────────────────────────────────────────

private data class DeepFinding(
    val pkg: String,
    val score: Int,
    val codeRisk: Int,
    val signals: Map<String, Int>,
    val trackers: Set<String>,
    val manifest: ManifestFacts?,
    val src: File?,
    val error: String?
)

private val DEEP_SIGNALS: Map<String, Pair<Regex, Int>> = linkedMapOf(
    "exec" to (Regex("Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec|Runtime\\s*\\.\\s*exec|ProcessBuilder") to 4),
    "dynamic-load" to (Regex("DexClassLoader|PathClassLoader|loadLibrary") to 3),
    "disable-app" to (Regex("setApplicationEnabledSetting") to 5),
    "a11y-hijack" to (Regex("enabled_accessibility_services") to 5),
    "screen-capture" to (Regex("MediaProjection|createScreenCaptureIntent") to 4),
    "reflection" to (Regex("Class\\.forName|getDeclaredMethod") to 1),
    "crypto" to (Regex("Cipher\\.getInstance|MessageDigest") to 1),
    "network" to (Regex("HttpURLConnection|OkHttpClient|openConnection") to 1),
    "set-prop" to (Regex("SystemProperties\\.set|Settings\\.(Global|Secure)\\.put") to 5),
    "sms" to (Regex("SmsManager|sendTextMessage|content://sms") to 4),
    "device-id" to (Regex("getDeviceId|getImei|getSubscriberId|getAndroidId|getSerial") to 3),
    "webview-bridge" to (Regex("addJavascriptInterface") to 3),
    "accessibility" to (Regex("AccessibilityService") to 4),
    "notification-listener" to (Regex("NotificationListenerService") to 3),
    "device-admin" to (Regex("DeviceAdminReceiver") to 4)
)

private val DEEP_SKIP = listOf(
    "androidx/", "kotlin/", "kotlinx/", "org/intellij/", "org/jetbrains/",
    "com/google/gson/", "okhttp3/", "retrofit2/", "com/google/protobuf/"
)

private fun cmdDeepScan(raw: List<String>) {
    val args = Args(raw)
    val adb = connectAdb(args)
    val all = args.flag("all")
    val scan = performScan(args, if (all) Int.MAX_VALUE else null)
    writeReports(scan, emptyList())
    val outDir = File(scan.outDir, "deepscan").apply { mkdirs() }

    val top = intOption(args, "top", 15, 1)
    val jobs = intOption(
        args,
        "jobs",
        (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2),
        1
    ).coerceIn(1, 6)
    val targets = if (all) scan.apps else scan.apps.take(top)

    if (targets.isEmpty()) {
        println(t("没有被标记的应用，不需要 DeepScan。", "no flagged apps; DeepScan is not needed."))
        return
    }

    val jadxBin = Platform.findJadx(args.value("jadx"))
        ?: throw NoirException(t("没有找到 jadx。", "jadx was not found."), Guidance.jadxMissing())

    println()
    println("== DeepScan ==")
    println(
        t(
            "  目标    ${targets.size} 个应用（共 ${scan.apps.size} 个被标记）",
            "  targets   ${targets.size} apps (${scan.apps.size} flagged in total)"
        )
    )
    println(t("  并行    $jobs 个 jadx 进程", "  jobs      $jobs jadx processes"))
    println(t("  输出    ${outDir.absolutePath}", "  output    ${outDir.absolutePath}"))
    if (args.flag("all")) {
        println(
            t(
                "  注意    --all 会反编译所有被标记应用，可能耗时数小时、占用数 GB",
                "  note      --all decompiles every flagged app; this can take hours and several GB"
            )
        )
    }
    println()

    val pool = Executors.newFixedThreadPool(jobs)
    val done = AtomicInteger()
    val started = System.currentTimeMillis()

    val findings = targets.map { app ->
        pool.submit<DeepFinding> {
            val result = try {
                analyzeOne(adb, scan, outDir, jadxBin, app)
            } catch (e: Exception) {
                DeepFinding(app.pkg, app.score, 0, emptyMap(), emptySet(), null, null, e.message ?: e::class.simpleName)
            }
            val n = done.incrementAndGet()
            val elapsed = (System.currentTimeMillis() - started) / 1000
            println("  [$n/${targets.size}] ${app.pkg}  code-risk=${result.codeRisk}  (${elapsed}s)")
            result
        }
    }.map { future ->
        try {
            future.get()
        } catch (e: Exception) {
            DeepFinding("unknown", 0, 0, emptyMap(), emptySet(), null, null, e.message)
        }
    }
    pool.shutdown()

    val ranked = findings.sortedByDescending { it.codeRisk }
    writeDeepReports(outDir, ranked)

    println()
    println("%5s %5s  %-44s %s".format("RISK", "SCAN", "PACKAGE", "SIGNALS"))
    ranked.take(20).forEach { f ->
        val signals = f.signals.entries.sortedByDescending { it.value }.take(3)
            .joinToString(", ") { "${it.key}x${it.value}" }
            .ifBlank { f.error ?: "-" }
        println("%5d %5d  %-44s %s".format(f.codeRisk, f.score, f.pkg, signals))
    }
    println()
    println("[+] ${File(outDir, "deepscan.md").absolutePath}")
    println("[+] ${File(outDir, "deepscan.tsv").absolutePath}")

    offerCleanup(args, adb, scan, ranked)
}

private fun analyzeOne(adb: Adb, scan: ScanResult, outDir: File, jadxBin: String, app: AppEntry): DeepFinding {
    val remotes = adb.shell(scan.device.serial, "pm path ${app.pkg}", timeoutSec = 30).output
        .lineSequence()
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").trim() }
        .filter { it.isNotEmpty() }
        .toList()
        .ifEmpty { listOfNotNull(scan.paths[app.pkg]) }
    if (remotes.isEmpty()) {
        return DeepFinding(app.pkg, app.score, 0, emptyMap(), emptySet(), null, null, "no apk path")
    }

    val src = File(outDir, "src/${app.pkg}")
    val marker = File(src, ".noir-ok")
    val remoteKey = remotes.joinToString("|")
    val apks = remotes.mapIndexed { index, _ ->
        File(outDir, "apks/${app.pkg}-$index.apk")
    }
    val cached = apks.all { it.isFile } && marker.isFile && marker.readText().trim() == remoteKey

    if (!cached) {
        src.deleteRecursively()
        src.mkdirs()
        apks.forEachIndexed { index, apk ->
            apk.delete()
            apk.parentFile?.mkdirs()
            val pulled = adb.pull(scan.device.serial, remotes[index], apk)
            if (!pulled.ok || !apk.isFile) {
                return DeepFinding(app.pkg, app.score, 0, emptyMap(), emptySet(), null, null, "pull failed")
            }
        }
        apks.forEachIndexed { index, apk ->
            val splitDir = if (index == 0) src else File(src, "split-$index")
            splitDir.deleteRecursively()
            val exit = Jadx(jadxBin).decompile(apk, splitDir)
            if (exit != 0 && !File(splitDir, "sources").isDirectory) {
                return DeepFinding(app.pkg, app.score, 0, emptyMap(), emptySet(), ManifestAnalyzer.analyze(apk), null, "jadx exit $exit")
            }
        }
        runCatching { marker.writeText(remoteKey) }
    }

    val mergedSignals = HashMap<String, Int>()
    remotes.indices.forEach { index ->
        val dir = if (index == 0) src else File(src, "split-$index")
        countSignals(dir).forEach { (key, count) ->
            mergedSignals[key] = (mergedSignals[key] ?: 0) + count
        }
    }
    var risk = DEEP_SIGNALS.entries.sumOf { (key, def) ->
        (mergedSignals[key] ?: 0).coerceAtMost(3) * def.second
    }
    val manifest = apks.firstNotNullOfOrNull { ManifestAnalyzer.analyze(it) }
    manifest?.let { facts ->
        risk += facts.riskyCount.coerceAtMost(4)
        if (facts.cleartext == true && app.uploadEndpoints.isNotEmpty()) risk += 2
        if (facts.allowBackup == true) risk += 1
        if (facts.debuggable == true) risk += 3
    }

    val trackers = remotes.indices.flatMap { index ->
        val dir = if (index == 0) src else File(src, "split-$index")
        Trackers.fromSources(dir)
    }.toSet()

    return DeepFinding(
        app.pkg, app.score, risk.coerceAtMost(40), mergedSignals,
        Trackers.fromEndpoints(app.endpoints) + trackers,
        manifest, src, null
    )
}

private fun countSignals(dir: File): Map<String, Int> {
    val counts = HashMap<String, Int>()
    if (!dir.exists()) return counts

    // jadx writes decompiled classes under <out>/sources; skip bundled libraries by
    // their path relative to that root, not the output root.
    val root = File(dir, "sources").takeIf { it.isDirectory } ?: dir
    var budget = 40L * 1024 * 1024
    val files = root.walkTopDown()
        .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
        .filter { file ->
            val path = file.relativeTo(root).path
            DEEP_SKIP.none { path.startsWith(it) }
        }
        .take(8000)

    for (file in files) {
        if (budget <= 0) break
        val text = try {
            file.readText()
        } catch (e: Exception) {
            continue
        }
        budget -= text.length
        DEEP_SIGNALS.forEach { (key, def) ->
            val hits = def.first.findAll(text).count()
            if (hits > 0) counts[key] = (counts[key] ?: 0) + hits
        }
    }
    return counts
}

private fun writeDeepReports(outDir: File, findings: List<DeepFinding>) {
    val tsv = StringBuilder("code_risk\tscan_score\tpackage\tsignals\ttrackers\texported_no_perm\terror\n")
    findings.forEach { f ->
        val signals = f.signals.entries.joinToString(";") { "${it.key}=${it.value}" }
        tsv.append(f.codeRisk).append('\t')
            .append(f.score).append('\t')
            .append(f.pkg).append('\t')
            .append(signals).append('\t')
            .append(f.trackers.joinToString(",")).append('\t')
            .append(f.manifest?.exportedWithoutPermission?.size ?: 0).append('\t')
            .append(f.error ?: "").append('\n')
    }
    File(outDir, "deepscan.tsv").writeText(tsv.toString())

    val md = StringBuilder()
    md.append("# DeepScan\n\n")
    md.append("| code risk | scan score | package | signals | trackers | error |\n")
    md.append("|---:|---:|---|---|---|---|\n")
    findings.forEach { f ->
        val signals = f.signals.entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key} ×${it.value}" }
        md.append("| ").append(f.codeRisk)
            .append(" | ").append(f.score)
            .append(" | `").append(f.pkg).append("`")
            .append(" | ").append(signals.ifBlank { "—" })
            .append(" | ").append(f.trackers.joinToString(", ").ifBlank { "—" })
            .append(" | ").append(f.error ?: "")
            .append(" |\n")
    }
    md.append("\n## details\n")
    findings.forEach { f ->
        md.append("\n### ").append(f.pkg).append("\n\n")
        md.append("- code risk: ").append(f.codeRisk).append("\n")
        md.append("- scan score: ").append(f.score).append("\n")
        if (f.signals.isNotEmpty()) {
            md.append("- signals: ").append(f.signals.entries.joinToString(", ") { "${it.key}=${it.value}" }).append("\n")
        }
        if (f.trackers.isNotEmpty()) {
            md.append("- trackers: ").append(f.trackers.joinToString(", ")).append("\n")
        }
        f.manifest?.let { m ->
            md.append("- targetSdk: ").append(m.targetSdk ?: "?").append("\n")
            md.append("- cleartext: ").append(m.cleartext ?: "unset")
                .append(" · allowBackup: ").append(m.allowBackup ?: "unset")
                .append(" · debuggable: ").append(m.debuggable ?: "false")
                .append(" · networkSecurityConfig: ").append(m.networkSecurityConfig).append("\n")
            if (m.exportedWithoutPermission.isNotEmpty()) {
                md.append("- exported without permission (").append(m.exportedWithoutPermission.size).append("):\n")
                m.exportedWithoutPermission.take(10).forEach { c ->
                    md.append("  - ").append(c.kind).append(" `").append(c.name).append("`")
                    c.authorities?.let { md.append(" (").append(it).append(")") }
                    md.append("\n")
                }
            }
        }
        f.src?.let { md.append("- sources: `").append(it.absolutePath).append("`\n") }
        if (f.error != null) md.append("- error: ").append(f.error).append("\n")
    }
    File(outDir, "deepscan.md").writeText(md.toString())
}

private fun offerCleanup(args: Args, adb: Adb, scan: ScanResult, findings: List<DeepFinding>) {
    val high = findings.filter { it.codeRisk >= 8 && it.error == null }
    if (high.isEmpty()) {
        println()
        println(t("没有代码级高风险项，不需要额外清理。", "no high-risk code findings; no extra cleanup is needed."))
        return
    }

    val highPkgs = high.map { it.pkg }.toSet()
    val appsByPkg = scan.apps.associateBy { it.pkg }
    val recommendations = effectiveRecommendations(
        adb,
        scan,
        Advisor.advise(scan.apps, LANG).filter { it.pkg in highPkgs }
    )
    val planned = effectiveActions(adb, scan, recommendations)

    println()
    println(t("== 代码级高风险 ${high.size} 个 ==", "== high-risk code findings: ${high.size} =="))
    high.forEach { f ->
        val system = appsByPkg[f.pkg]?.isSystem ?: true
        println("  %-44s risk %2d  %s".format(f.pkg, f.codeRisk, if (system) "system" else "user"))
    }
    println(t("  可执行的安全 appops: ${planned.size} 条", "  safe app-ops available: ${planned.size}"))

    if (!args.flag("apply")) {
        println()
        println(
            t(
                "[*] dry-run — 加 --apply 执行安全清理；加 --purge 可同时卸载高风险用户应用",
                "[*] dry-run — add --apply for safe cleanup; add --purge to uninstall high-risk user apps"
            )
        )
        return
    }

    if (!args.flag("yes")) {
        if (!isInteractive()) {
            throw NoirException(
                t(
                    "--apply 在非交互环境下需要同时加 --yes",
                    "--apply needs --yes when stdin is not interactive"
                )
            )
        }
        print(t("执行这 ${planned.size} 条 appops? [y/N] ", "apply these ${planned.size} app-ops? [y/N] "))
        val answer = readLine()?.trim()?.lowercase()
        if (answer != "y" && answer != "yes") {
            println(t("已跳过。", "skipped."))
            return
        }
    }
    if (applyCommands(adb, scan, planned) > 0) exitProcess(1)

    if (!args.flag("purge")) return

    val userHigh = high.filter { appsByPkg[it.pkg]?.isSystem == false }
    if (userHigh.isEmpty()) {
        println(t("高风险项里没有用户应用，无需卸载。", "no high-risk user apps; nothing to uninstall."))
        return
    }
    val user = scan.meta.user.ifBlank { "0" }
    val rollbackFile = File(scan.outDir, "purge-rollback.txt")
    val rollback = LinkedHashSet<String>()
    if (rollbackFile.isFile) {
        rollbackFile.readLines().filter { it.startsWith("adb ") }.forEach { rollback += it }
    }
    var failed = 0

    println()
    println(t("== 卸载高风险用户应用 ==", "== uninstall high-risk user apps =="))
    println(
        t(
            "# 恢复命令只能重装 APK，应用数据不会恢复；--user $user",
            "# restore only reinstalls the APK; app data is not restored; --user $user"
        )
    )
    userHigh.forEach { f ->
        val pkg = requirePackageName(f.pkg)
        if (!args.flag("yes") && isInteractive()) {
            print(t("  卸载 $pkg? [y/N] ", "  uninstall $pkg? [y/N] "))
            val answer = readLine()?.trim()?.lowercase()
            if (answer != "y" && answer != "yes") return@forEach
        }
        val result = adb.shell(scan.device.serial, "pm uninstall --user $user $pkg")
        if (result.ok) {
            rollback += "adb -s ${scan.device.serial} shell pm install-existing --user $user $pkg"
        } else {
            failed++
        }
        println("  %-44s %s".format(pkg, if (result.ok) "uninstalled" else "failed (${result.code})"))
    }

    if (rollback.isNotEmpty()) {
        rollbackFile.writeText(
            buildString {
                appendLine("# NOIR purge rollback")
                appendLine("# device: ${scan.device.model} (${scan.device.serial}) · user $user")
                appendLine("# pm install-existing restores the APK; app data is not restored")
                rollback.forEach { appendLine(it) }
            }
        )
        println("[*] rollback: ${rollbackFile.absolutePath}")
    }
    if (failed > 0) {
        System.err.println(t("$failed 个应用卸载失败。", "$failed app(s) failed to uninstall."))
        exitProcess(1)
    }
}

// ── plumbing ───────────────────────────────────────────────────────────────

private fun isInteractive(): Boolean =
    System.console() != null || System.getenv("NOIR_INTERACTIVE") == "1"

/** The container runtime NOIR itself runs in; null on an ordinary host. */
private val CONTAINER: ContainerRuntime? by lazy { Docker.container() }

/** Inside a container a missing device is usually missing USB passthrough, not a bad cable. */
private fun noDeviceGuide(): String =
    CONTAINER?.let { Guidance.containerDeviceGuide(it) } ?: Guidance.deviceGuide()

private fun connectAdb(args: Args): Adb {
    val explicit = args.value("adb")
    if (explicit != null && !File(explicit).exists()) {
        throw NoirException(
            t("指定的 adb 不存在：$explicit", "adb not found at: $explicit"),
            t("检查路径，或运行 noir setup 自动安装。", "Check the path, or run noir setup to install it.")
        )
    }
    val bin = Platform.findAdb(explicit)
        ?: throw NoirException(t("没有找到 adb。", "adb was not found."), Guidance.adbMissing())
    return Adb(bin)
}

private fun awaitDevice(adb: Adb, requested: String?, interactive: Boolean): Device {
    var waited = 0
    var printed = false

    while (true) {
        val devices = adb.devices()
        val online = devices.filter { it.state == "device" }

        if (requested != null) {
            online.firstOrNull { it.serial == requested }?.let { return it }
            if (online.isNotEmpty()) {
                throw NoirException(
                    t("没有找到设备 $requested。", "device not found: $requested"),
                    t(
                        "当前已连接：" + online.joinToString { "${it.serial} (${it.model})" },
                        "Connected now: " + online.joinToString { "${it.serial} (${it.model})" }
                    )
                )
            }
        } else if (online.size == 1) {
            return online.first()
        } else if (online.size > 1) {
            // Prefer a physical device over an emulator when both are attached.
            val physical = online.filterNot { it.serial.startsWith("emulator-") }
            if (physical.size == 1) return physical.first()

            applyDeviceLanguage(adb, online.first())
            if (interactive) {
                println(t("检测到多台设备：", "multiple devices detected:"))
                online.forEachIndexed { index, device ->
                    println("  ${index + 1}) ${device.serial}  ${device.model}")
                }
                print(t("选择设备 [1-${online.size}] ", "choose a device [1-${online.size}] "))
                val choice = readLine()?.trim()?.toIntOrNull()
                if (choice != null && choice in 1..online.size) return online[choice - 1]
                throw NoirException(
                    t("没有选择设备。", "no device selected."),
                    t("用 --serial <id> 指定要扫描的设备。", "Use --serial <id> to choose the device to scan.")
                )
            }

            val chosen = physical.firstOrNull() ?: online.first()
            println(
                t(
                    "提示：检测到 ${online.size} 台设备，使用 ${chosen.serial}；可用 --serial 指定。",
                    "note: ${online.size} devices detected; using ${chosen.serial}. Use --serial to choose."
                )
            )
            return chosen
        }

        val unauthorized = devices.any { it.state == "unauthorized" }
        if (!printed) {
            println(if (unauthorized) Guidance.unauthorizedGuide() else noDeviceGuide())
            printed = true
        }

        if (!interactive) {
            throw NoirException(
                t("没有可用设备。", "no usable device."),
                if (unauthorized) {
                    Guidance.unauthorizedGuide()
                } else {
                    t(
                        "插上手机后重新运行，或先运行 noir doctor。",
                        "Plug in the phone and retry, or run noir doctor first."
                    )
                }
            )
        }
        if (waited >= 180) {
            throw NoirException(
                t("等了 180 秒仍然没有设备。", "no device appeared after 180 seconds."),
                noDeviceGuide()
            )
        }

        print(t("\r  等待设备接入 ... ${180 - waited}s   ", "\r  waiting for a device ... ${180 - waited}s   "))
        System.out.flush()
        Thread.sleep(2000)
        waited += 2
    }
}

private fun intOption(args: Args, name: String, default: Int, min: Int): Int {
    val raw = args.value(name) ?: return default
    val value = raw.toIntOrNull()
        ?: throw NoirException(
            t("--$name 需要整数，收到：$raw", "--$name needs an integer, got: $raw")
        )
    if (value < min) {
        throw NoirException(
            t("--$name 不能小于 $min。", "--$name must be at least $min.")
        )
    }
    return value
}

internal class Args(raw: List<String>) {
    val positional = ArrayList<String>()
    val flags = HashSet<String>()
    val values = HashMap<String, String>()

    init {
        var i = 0
        while (i < raw.size) {
            val arg = raw[i]
            if (arg.startsWith("--")) {
                val body = arg.substring(2)
                val equals = body.indexOf('=')
                if (equals > 0) {
                    val name = body.substring(0, equals)
                    val value = body.substring(equals + 1)
                    when {
                        name in BOOLEAN_FLAGS ->
                            if (value.trim().lowercase() !in FALSE_VALUES) flags.add(name)
                        name in VALUE_FLAGS && value.isEmpty() ->
                            throw NoirException(t("--$name 需要一个值。", "--$name requires a value."))
                        value.isEmpty() -> flags.add(name)
                        else -> values[name] = value
                    }
                } else {
                    when {
                        body in BOOLEAN_FLAGS -> flags.add(body)
                        body in VALUE_FLAGS -> {
                            if (i + 1 >= raw.size || raw[i + 1].startsWith("--")) {
                                throw NoirException(
                                    t("--$body 需要一个值。", "--$body requires a value.")
                                )
                            }
                            values[body] = raw[i + 1]
                            i++
                        }
                        else -> flags.add(body)
                    }
                }
            } else {
                positional.add(arg)
            }
            i++
        }

        val unknown = (flags + values.keys).firstOrNull { it !in ALL_OPTIONS }
        if (unknown != null) throw NoirException("unknown option: --$unknown")
    }

    fun flag(name: String): Boolean = name in flags
    fun value(name: String): String? = values[name]
}
