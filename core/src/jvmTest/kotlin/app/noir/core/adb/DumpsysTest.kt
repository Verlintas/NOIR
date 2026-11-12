/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DumpsysTest {

    private fun dump(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun `merges duplicate package blocks`() {
        val text = dump(
            "  Package [com.example.app] (aaa):",
            "    appId=10200",
            "    flags=[ HAS_CODE ]",
            "    installerPackageName=null",
            "  Package [com.example.app] (bbb):",
            "    versionName=1.2.3",
            "    android.permission.INTERNET: granted=true"
        )

        val meta = Dumpsys.parse(text).getValue("com.example.app")
        assertTrue(meta.perms.contains("INTERNET"))
        assertEquals("10200", meta.appId)
        assertFalse(meta.isSystem)
    }

    @Test
    fun `inherits permissions from shared uid when the package block has none`() {
        val text = dump(
            "  Package [com.miui.securitycenter] (abc):",
            "    appId=1000",
            "    sharedUser=SharedUserSetting{def android.uid.system/1000}",
            "    flags=[ SYSTEM HAS_CODE ]",
            "  Package [com.example.userapp] (123):",
            "    appId=10500",
            "    flags=[ HAS_CODE ]",
            "",
            "Shared users:",
            "  SharedUser [android.uid.system] (def):",
            "    appId=1000",
            "    Packages",
            "      PackageSetting{abc com.miui.securitycenter/1000}",
            "    install permissions:",
            "      android.permission.INTERNET: granted=true",
            "      android.permission.QUERY_ALL_PACKAGES: granted=true",
            "      android.permission.READ_SMS: granted=true"
        )

        val parsed = Dumpsys.parse(text)
        val security = parsed.getValue("com.miui.securitycenter")
        assertTrue(security.perms.contains("INTERNET"), "shared perm must be merged in")
        assertTrue(security.perms.contains("QUERY_ALL_PACKAGES"))
        assertTrue(security.isSystem)

        val userApp = parsed.getValue("com.example.userapp")
        assertFalse(userApp.perms.contains("INTERNET"), "shared perms must not leak to other uids")
        assertFalse(userApp.isSystem)
    }

    @Test
    fun `accepts the legacy userId field`() {
        val meta = Dumpsys.parse(
            dump(
                "  Package [com.example] (abc):",
                "    userId=10200",
                "    flags=[ HAS_CODE ]"
            )
        ).getValue("com.example")
        assertEquals("10200", meta.appId)
    }

    @Test
    fun `marks packages created only by the shared section as system when appId is 1000`() {
        val text = dump(
            "Shared users:",
            "  SharedUser [android.uid.system] (def):",
            "    appId=1000",
            "    Packages",
            "      PackageSetting{abc com.only.in.shared/1000}",
            "    install permissions:",
            "      android.permission.INTERNET: granted=true"
        )

        val meta = Dumpsys.parse(text).getValue("com.only.in.shared")
        assertTrue(meta.isSystem)
        assertTrue(meta.perms.contains("INTERNET"))
        assertEquals("1000", meta.appId)
        assertEquals("android.uid.system", meta.sharedUser)
    }

    @Test
    fun `parses runtime permissions that carry a flags suffix`() {
        val text = dump(
            "  Package [com.example] (a):",
            "    appId=1001",
            "    android.permission.INTERNET: granted=true",
            "    android.permission.ACCESS_FINE_LOCATION: granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED]",
            "    android.permission.CAMERA: granted=true; flags=[ USER_SET]",
            "    android.permission.RECORD_AUDIO: granted=false, flags=[ USER_SET]"
        )

        val meta = Dumpsys.parse(text).getValue("com.example")
        assertTrue(meta.perms.contains("INTERNET"))
        assertTrue(meta.perms.contains("ACCESS_FINE_LOCATION"), "runtime permission with ', flags' must be kept")
        assertTrue(meta.perms.contains("CAMERA"), "runtime permission with '; flags' must be kept")
        assertFalse(meta.perms.contains("RECORD_AUDIO"), "granted=false must be ignored")
    }

    @Test
    fun `ignores ungranted permissions`() {
        val text = dump(
            "  Package [com.example.app] (aaa):",
            "    appId=10200",
            "    flags=[ HAS_CODE ]",
            "    android.permission.RECORD_AUDIO: granted=false",
            "    android.permission.INTERNET: granted=true"
        )

        val meta = Dumpsys.parse(text).getValue("com.example.app")
        assertTrue(meta.perms.contains("INTERNET"))
        assertFalse(meta.perms.contains("RECORD_AUDIO"))
    }

    @Test
    fun `accepts OEM indentation and package headers at column zero`() {
        val text = dump(
            "Packages:",
            "    Package [com.example.four] (a):",
            "        appId=10200",
            "        flags=[ HAS_CODE ]",
            "        android.permission.INTERNET: granted=true",
            "\tPackage [com.example.tab] (b):",
            "\t\tappId=10201",
            "\t\tandroid.permission.INTERNET: granted=true",
            "Package [com.example.zero] (c):",
            "  appId=10202",
            "  android.permission.INTERNET: granted=true"
        )

        val parsed = Dumpsys.parse(text)
        assertEquals(setOf("com.example.four", "com.example.tab", "com.example.zero"), parsed.keys)
        parsed.values.forEach { assertTrue(it.perms.contains("INTERNET")) }
    }

    @Test
    fun `parses a shared users section that appears before packages`() {
        val text = dump(
            "Shared users:",
            "  SharedUser [android.uid.system] (def):",
            "    appId=1000",
            "    Packages",
            "      PackageSetting{abc com.system.app/1000}",
            "    install permissions:",
            "      android.permission.INTERNET: granted=true",
            "Packages:",
            "    Package [com.system.app] (aaa):",
            "        flags=[ SYSTEM ]",
            "        android.permission.CAMERA: granted=true",
            "    Package [com.user.app] (bbb):",
            "        appId=10500",
            "        android.permission.INTERNET: granted=true"
        )

        val parsed = Dumpsys.parse(text)
        val system = parsed.getValue("com.system.app")
        assertTrue(system.isSystem)
        assertTrue(system.perms.contains("INTERNET"), "shared permission must be merged in")
        assertTrue(system.perms.contains("CAMERA"), "package permission must still be parsed after shared users")
        assertEquals("android.uid.system", system.sharedUser)

        val user = parsed.getValue("com.user.app")
        assertFalse(user.isSystem)
        assertTrue(user.perms.contains("INTERNET"))
        assertFalse(user.perms.contains("CAMERA"), "shared permissions must not leak to other uids")
    }

    @Test
    fun `parses CRLF output from adb shell`() {
        val text = listOf(
            "Packages:",
            "  Package [com.example.crlf] (aaa):",
            "    appId=10200",
            "    android.permission.INTERNET: granted=true"
        ).joinToString("\r\n")

        val meta = Dumpsys.parse(text).getValue("com.example.crlf")
        assertTrue(meta.perms.contains("INTERNET"))
        assertEquals("10200", meta.appId)
    }
}
