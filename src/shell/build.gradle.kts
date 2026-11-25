import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
    id("com.google.protobuf") version "0.9.4"
}

group = "app.kaze"
version = "1.0.3"

kotlin { jvmToolchain(21) }

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.28.3" }
}

sourceSets {
    main {
        proto { srcDir(layout.projectDirectory.dir("../../proto")) }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    implementation("com.google.protobuf:protobuf-java:4.28.3")
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

compose.desktop {
    application {
        mainClass = "app.kaze.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Kaze"
            packageVersion = "1.0.3"
            description = "Kaze - runtime text workbench for games"
            vendor = "Kaze"
            windows {
                val appIcon = project.file("icons/kaze.ico")
                if (appIcon.isFile) iconFile.set(appIcon)
                menuGroup = "Kaze"
                shortcut = true
                perUserInstall = true
                upgradeUuid = "8f6a1f7e-2d4b-4a38-9f43-6f0c2f9a1b77"
            }
        }
    }
}
