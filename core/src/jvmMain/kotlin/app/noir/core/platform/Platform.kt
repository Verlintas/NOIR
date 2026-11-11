/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core.platform

import java.io.File

/**
 * Locates the external tools on Windows 10/11, macOS and Debian-ish Linux.
 * No assumptions about PATH being correct — the common install locations are
 * checked explicitly so a plain double-click install works everywhere.
 */
object Platform {

    private val osName = System.getProperty("os.name").lowercase()
    val isWindows: Boolean = osName.contains("win")
    val isMac: Boolean = osName.contains("mac")
    val isLinux: Boolean = osName.contains("linux")

    fun describe(): String = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}"

    fun findAdb(explicit: String? = null): String? {
        explicit?.let { return it.takeIf(::usable) }
        System.getenv("ADB")?.takeIf { it.isNotBlank() }?.takeIf(::usable)?.let { return it }
        which("adb")?.let { return it }

        val home = System.getProperty("user.home")
        val candidates = buildList {
            val downloaded = File(Setup.toolsDir(), if (isWindows) "platform-tools/adb.exe" else "platform-tools/adb")
            if (downloaded.exists()) add(downloaded.absolutePath)
            when {
                isWindows -> {
                    val local = System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local"
                    add("$local\\Android\\Sdk\\platform-tools\\adb.exe")
                    add("$home\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe")
                    add("C:\\platform-tools\\adb.exe")
                    add("C:\\adb\\adb.exe")
                }
                isMac -> {
                    add("/opt/homebrew/bin/adb")
                    add("/usr/local/bin/adb")
                    add("$home/Library/Android/sdk/platform-tools/adb")
                }
                else -> {
                    add("/usr/bin/adb")
                    add("/usr/local/bin/adb")
                    add("/usr/lib/android-sdk/platform-tools/adb")
                    add("$home/Android/Sdk/platform-tools/adb")
                }
            }
        }
        return candidates.firstOrNull(::usable)
    }

    fun findJadx(explicit: String? = null): String? {
        explicit?.let { return it.takeIf(::usable) }
        System.getenv("JADX")?.takeIf { it.isNotBlank() }?.takeIf(::usable)?.let { return it }
        which("jadx")?.let { return it }

        val home = System.getProperty("user.home")
        val candidates = buildList {
            val downloaded = File(Setup.toolsDir(), if (isWindows) "jadx/bin/jadx.bat" else "jadx/bin/jadx")
            if (downloaded.exists()) add(downloaded.absolutePath)
            when {
                isWindows -> {
                    val local = System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local"
                    add("$local\\Programs\\jadx\\bin\\jadx.bat")
                    add("$local\\jadx\\bin\\jadx.bat")
                    add("C:\\jadx\\bin\\jadx.bat")
                }
                isMac -> {
                    add("/opt/homebrew/bin/jadx")
                    add("/usr/local/bin/jadx")
                }
                else -> {
                    add("/usr/bin/jadx")
                    add("/usr/local/bin/jadx")
                    add("/opt/jadx/bin/jadx")
                }
            }
        }
        return candidates.firstOrNull(::usable)
    }

    internal fun usable(path: String): Boolean = File(path).let { it.isFile && (isWindows || it.canExecute()) }

    /** Minimal `which`, without spawning a shell. */
    fun which(command: String): String? {
        val path = System.getenv("PATH") ?: return null
        val extensions = if (isWindows) listOf(".exe", ".bat", ".cmd", "") else listOf("")
        for (dir in path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            for (ext in extensions) {
                val candidate = File(dir, command + ext)
                if (candidate.isFile && (candidate.canExecute() || isWindows)) {
                    return candidate.absolutePath
                }
            }
        }
        return null
    }
}

/** Handles Windows batch shims (`jadx.bat`) transparently. */
object Exec {
    fun argv(bin: String, args: List<String>): List<String> {
        val lower = bin.lowercase()
        return if (Platform.isWindows && (lower.endsWith(".bat") || lower.endsWith(".cmd"))) {
            listOf("cmd", "/c", bin) + args
        } else {
            listOf(bin) + args
        }
    }
}
