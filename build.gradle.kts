import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PatchPluginXmlTask
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    java
    jacoco
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

abstract class VerifyMarketplaceDescriptionTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val patchedPluginXml: RegularFileProperty

    @get:Input
    abstract val expectedDescription: Property<String>

    @TaskAction
    fun verify() {
        val generatedXml = patchedPluginXml.get().asFile
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(generatedXml)
        val descriptions = document.getElementsByTagName("description")

        require(descriptions.length == 1) { "Expected exactly one Marketplace description in patched plugin.xml" }
        require(descriptions.item(0).textContent.trim() == expectedDescription.get()) {
            "Patched plugin.xml description differs from the README.md Marketplace section"
        }
    }
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
val marketplaceDescription =
    providers
        .fileContents(layout.projectDirectory.file("README.md"))
        .asText
        .map { readme ->
            val start = "<!-- marketplace-description:start -->"
            val end = "<!-- marketplace-description:end -->"
            val from = readme.indexOf(start)
            val to = readme.indexOf(end)

            require(from >= 0 && to > from) { "Missing or misplaced Marketplace description markers in README.md" }
            require(readme.indexOf(start, from + start.length) == -1 && readme.indexOf(end, to + end.length) == -1) {
                "Marketplace description markers must appear exactly once"
            }

            readme.substring(from + start.length, to).trim().also { html ->
                require(html.isNotBlank()) { "Marketplace description must not be empty" }
                require("]]>" !in html) { "Marketplace description cannot contain a CDATA terminator" }
            }
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
        description.set(marketplaceDescription)

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

val verifyMarketplaceDescription =
    tasks.register<VerifyMarketplaceDescriptionTask>("verifyMarketplaceDescription") {
        group = "verification"
        description = "Verify the patched plugin.xml description matches the README.md Marketplace section"

        patchedPluginXml.set(tasks.named<PatchPluginXmlTask>("patchPluginXml").flatMap { it.outputFile })
        expectedDescription.set(marketplaceDescription)
    }

tasks.named("check") {
    dependsOn(verifyMarketplaceDescription)
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("started", "failed", "skipped")
    }
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
