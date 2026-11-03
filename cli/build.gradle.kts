plugins {
    kotlin("jvm")
    application
}

// Override in CI: ./gradlew :cli:installDist -PnoirVersion=0.2.0
val noirVersion = providers.gradleProperty("noirVersion").orElse("0.2.5")
version = noirVersion.get()

tasks.jar {
    manifest {
        attributes["Implementation-Title"] = "NOIR"
        attributes["Implementation-Version"] = noirVersion.get()
    }
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("app.noir.cli.MainKt")
    applicationName = "noir"
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8"
    )
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val batFile = windowsScript
        if (batFile.exists()) {
            val text = batFile.readText(Charsets.UTF_8)
            batFile.writeText(
                text.replace("@if \"%DEBUG%\"==\"\" @echo off", "@echo off\r\nchcp 65001 >nul"),
                Charsets.UTF_8
            )
        }
    }
}
