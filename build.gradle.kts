import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    jacoco
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

val localIdePath = providers.gradleProperty("localIdePath").orNull
val debugProjectPath = providers.gradleProperty("debugProjectPath")
val supportedIdeSinceBuild = providers.gradleProperty("supportedIdeSinceBuild")
val supportedIdeUntilBuild = providers.gradleProperty("supportedIdeUntilBuild")
val pluginVerifierIdeVersions =
    providers.gradleProperty("pluginVerifierIdeVersions").map { versions ->
        versions.split(',').map(String::trim)
    }
val zipSignerPath = providers.gradleProperty("zipSignerPath")
val marketplaceChannel =
    providers.gradleProperty("marketplaceChannel").orElse("beta").map { channel ->
        require(channel in setOf("beta", "default")) {
            "marketplaceChannel must be either 'beta' or 'default'"
        }
        channel
    }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly(kotlin("stdlib"))

    intellijPlatform {
        if (localIdePath == null) {
            webstorm(providers.gradleProperty("platformVersion"))
        } else {
            local(localIdePath)
        }

        bundledPlugin("JavaScript")
        bundledPlugin("AngularJS")
        bundledPlugin("com.intellij.css")
        bundledPlugin("com.intellij.modules.json")
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    instrumentCode.set(
        providers
            .gradleProperty("skipInstrumentation")
            .map { !it.toBoolean() }
            .orElse(true),
    )

    pluginConfiguration {
        ideaVersion {
            sinceBuild.set(supportedIdeSinceBuild)
            untilBuild.set(supportedIdeUntilBuild)
        }
    }

    signing {
        zipSignerPath.orNull?.let { path ->
            cliPath.set(file(path))
        }
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
        channels.set(marketplaceChannel.map { channel -> listOf(channel) })
    }

    pluginVerification {
        ides {
            pluginVerifierIdeVersions.get().forEach { version ->
                create(IntelliJPlatformType.WebStorm, version)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(tasks.named("instrumentCode"))

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareTestSandbox") {
    disabledPlugins.add("org.jetbrains.plugins.vue")
}

tasks {
    named("verifyPluginSignature") {
        dependsOn("signPlugin")
    }

    named("publishPlugin") {
        val publishVersion = providers.gradleProperty("pluginVersion").get()

        doFirst {
            require(!publishVersion.endsWith("-SNAPSHOT")) {
                "Refusing to publish a SNAPSHOT plugin version"
            }
        }
    }

    runIde {
        debugProjectPath.orNull?.let { projectPath ->
            args(projectPath)
        }
    }
}
