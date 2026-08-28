plugins {
    id("java-library")
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.protocollib)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.paper.api)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.mockito.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        val props = mapOf("version" to version )
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform {
            excludeTags("stress")
        }
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }

    register<Test>("stressTest") {
        description = "Runs the high-volume item-frame claim stress test."
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform {
            includeTags("stress")
        }
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        systemProperty(
            "ppl.stress.frames",
            providers.gradleProperty("stressFrames").getOrElse("10000")
        )
        systemProperty(
            "ppl.stress.replays",
            providers.gradleProperty("stressReplays").getOrElse("8")
        )
        systemProperty(
            "ppl.stress.seed",
            providers.gradleProperty("stressSeed").getOrElse(System.nanoTime().toString())
        )
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = true
        }
    }
}
