/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core.adb

data class PkgMeta(
    val flags: String,
    val appId: String,
    val installer: String?,
    val perms: Set<String>,
    val sharedUser: String? = null
) {
    val isSystem: Boolean get() = flags.contains("SYSTEM")
    val debuggable: Boolean get() = flags.contains("DEBUGGABLE")
}

/**
 * Parses `adb shell dumpsys package`.
 *
 * Two quirks matter:
 *  - the dump lists every package more than once (summary + details), so entries are merged;
 *  - packages sharing a UID (e.g. `android.uid.system`, which covers ~58 apps) carry their
 *    granted permissions in the trailing "Shared users" section, not in their own block.
 *    Ignoring that section silently hides the most privileged apps on the device.
 */
object Dumpsys {

    // OEM dumpsys output is not always two-space indented, and some releases
    // put "Shared users:" before "Packages:". Match any indentation and keep
    // the two sections independent instead of treating the rest of the dump as
    // a shared-user section.
    private val PKG = Regex("^\\s*Package\\s*\\[([^\\]]+)]")
    private val SHARED_USER = Regex("^sharedUser=SharedUserSetting\\{[^\\s]+ ([^/]+)/")
    private val SHARED = Regex("^\\s*SharedUser\\s*\\[([^\\]]+)]")
    private val PKG_SETTING = Regex("^PackageSetting\\{[^\\s]+ ([^/]+)/")
    private val GRANTED = Regex("^android\\.permission\\.([A-Z0-9_]+): granted=true(?:[,;].*)?$")

    fun parse(text: String): Map<String, PkgMeta> {
        val acc = LinkedHashMap<String, Mutable>()
        val sharedPackages = HashMap<String, MutableList<String>>()
        val sharedPerms = HashMap<String, MutableSet<String>>()
        val sharedAppId = HashMap<String, String>()

        var current: Mutable? = null
        var currentShared: String? = null

        for (line in text.lineSequence()) {
            val pkgMatch = PKG.find(line)
            if (pkgMatch != null) {
                current = acc.getOrPut(pkgMatch.groupValues[1]) { Mutable() }
                currentShared = null
                continue
            }

            val sharedMatch = SHARED.find(line)
            if (sharedMatch != null) {
                currentShared = sharedMatch.groupValues[1]
                current = null
                sharedPackages.getOrPut(currentShared) { mutableListOf() }
                sharedPerms.getOrPut(currentShared) { mutableSetOf() }
                continue
            }

            // A top-level section header ends the current package/shared block.
            // Package and SharedUser headers are matched above, so a section
            // such as "Packages:" or "Dexopt state:" is safe to reset on.
            if (line.isNotEmpty() && !line[0].isWhitespace() && line.trimEnd().endsWith(":")) {
                current = null
                currentShared = null
                continue
            }

            val s = line.trim()
            if (s.isEmpty()) continue

            val shared = currentShared
            if (shared != null) {
                when {
                    s.startsWith("appId=") -> sharedAppId[shared] = s.removePrefix("appId=")
                    else -> {
                        PKG_SETTING.find(s)?.let { sharedPackages.getValue(shared).add(it.groupValues[1]) }
                        GRANTED.find(s)?.let { sharedPerms.getValue(shared).add(it.groupValues[1]) }
                    }
                }
                continue
            }

            val c = current ?: continue
            when {
                (s.startsWith("appId=") || s.startsWith("userId=")) && c.appId.isEmpty() ->
                    c.appId = s.substringAfter('=')
                s.startsWith("flags=[") && c.flags.isEmpty() -> c.flags = s
                s.startsWith("installerPackageName=") && c.installer == null -> {
                    val v = s.removePrefix("installerPackageName=")
                    c.installer = if (v == "null") null else v
                }
                s.startsWith("sharedUser=") && c.sharedUser == null ->
                    c.sharedUser = SHARED_USER.find(s)?.groupValues?.get(1)
                else -> GRANTED.find(s)?.let { c.perms.add(it.groupValues[1]) }
            }
        }

        sharedPackages.forEach { (shared, packages) ->
            val perms = sharedPerms[shared].orEmpty()
            val appId = sharedAppId[shared]
            packages.forEach { pkg ->
                val entry = acc.getOrPut(pkg) { Mutable() }
                entry.perms.addAll(perms)
                if (entry.appId.isEmpty() && appId != null) entry.appId = appId
                if (entry.flags.isEmpty() && appId == "1000") entry.flags = "SYSTEM"
                if (entry.sharedUser == null) entry.sharedUser = shared
            }
        }

        return acc.mapValues { PkgMeta(it.value.flags, it.value.appId, it.value.installer, it.value.perms, it.value.sharedUser) }
    }

    private class Mutable {
        var flags: String = ""
        var appId: String = ""
        var installer: String? = null
        var sharedUser: String? = null
        val perms: MutableSet<String> = HashSet()
    }
}
