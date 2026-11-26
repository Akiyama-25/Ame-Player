@file:Suppress("DEPRECATION")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val customApplicationId = providers.gradleProperty("customApplicationId").orNull
val signingStoreFilePath = providers.gradleProperty("signingStoreFile")
    .orElse(providers.gradleProperty("android.injected.signing.store.file")).orNull
val signingStorePasswordVal = providers.gradleProperty("signingStorePassword")
    .orElse(providers.gradleProperty("android.injected.signing.store.password")).orNull
val signingKeyAliasVal = providers.gradleProperty("signingKeyAlias")
    .orElse(providers.gradleProperty("android.injected.signing.key.alias")).orNull
val signingKeyPasswordVal = providers.gradleProperty("signingKeyPassword")
    .orElse(providers.gradleProperty("android.injected.signing.key.password")).orNull

android {
    namespace = "Akari.NCM.player"
    compileSdk = 36

    defaultConfig {
        applicationId = customApplicationId ?: "Akari.NCM.player"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "release-0.6"

        fun escapeBuildConfigString(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")

        fun runGit(vararg args: String): String = runCatching {
            val proc = ProcessBuilder("git", *args).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (proc.waitFor() == 0) out else ""
        }.getOrDefault("")

        fun isGitAncestor(ancestorRef: String, descendantRef: String): Boolean = runCatching {
            val proc = ProcessBuilder("git", "merge-base", "--is-ancestor", ancestorRef, descendantRef).start()
            proc.waitFor() == 0
        }.getOrDefault(false)

        val gitCommitSha = runGit("rev-parse", "--short", "HEAD").ifBlank {
            System.getenv("GITHUB_SHA")?.trim()?.take(7) ?: ""
        }
        val gitCommitFullSha = runGit("rev-parse", "HEAD").ifBlank {
            System.getenv("GITHUB_SHA")?.trim() ?: ""
        }
        val exactGitTag = runGit("describe", "--tags", "--exact-match")
        val envRefType = System.getenv("GITHUB_REF_TYPE")?.trim().orEmpty()
        val envRefName = System.getenv("GITHUB_REF_NAME")?.trim().orEmpty()
        val envBuildChannel = System.getenv("AME_BUILD_CHANNEL")?.trim()?.lowercase().orEmpty()
        val envGitBranch = System.getenv("AME_GIT_BRANCH")?.trim().orEmpty()
        val envReleaseTag = System.getenv("AME_RELEASE_TAG")?.trim().orEmpty()

        val resolvedCommitBranchFromGit = run {
            val abbrev = runGit("rev-parse", "--abbrev-ref", "HEAD")
                .takeIf { it.isNotBlank() && it != "HEAD" }
            if (abbrev != null) {
                abbrev
            } else {
                val defaultRemoteBranch = runGit("symbolic-ref", "--short", "refs/remotes/origin/HEAD")
                    .removePrefix("origin/")
                    .trim()
                if (defaultRemoteBranch.isNotBlank() && isGitAncestor("HEAD", "refs/remotes/origin/$defaultRemoteBranch")) {
                    defaultRemoteBranch
                } else {
                    val remoteContaining = runGit("for-each-ref", "--contains=HEAD", "--format=%(refname:short)", "refs/remotes/origin")
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && it != "origin/HEAD" }
                        .map { it.removePrefix("origin/") }
                        .firstOrNull()
                    remoteContaining ?: runGit("for-each-ref", "--contains=HEAD", "--format=%(refname:short)", "refs/heads")
                        .lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.isNotBlank() }
                        .orEmpty()
                }
            }
        }

        val resolvedBranchCandidate = listOfNotNull(
            envGitBranch.ifBlank { null },
            System.getenv("GITHUB_HEAD_REF")?.trim()?.ifBlank { null },
            envRefName.takeIf { it.isNotBlank() && !envRefType.equals("tag", ignoreCase = true) },
            resolvedCommitBranchFromGit.ifBlank { null }
        ).firstOrNull()
            ?.removePrefix("refs/heads/")
            ?.removePrefix("refs/tags/")
            ?.trim()
            ?.ifBlank { null }

        val gitBranch = resolvedBranchCandidate
            ?: exactGitTag.removePrefix("refs/tags/").trim().ifBlank { null }
            ?: "unknown"
        val gitRefType = when {
            !resolvedBranchCandidate.isNullOrBlank() -> "branch"
            exactGitTag.isNotBlank() -> "tag"
            envRefType.isNotBlank() -> envRefType
            else -> "branch"
        }
        val buildReleaseTag = listOfNotNull(
            envReleaseTag.ifBlank { null },
            envRefName.takeIf { it.isNotBlank() && envRefType.equals("tag", ignoreCase = true) },
            exactGitTag.takeIf { it.isNotBlank() && envBuildChannel == "release" }
        ).firstOrNull()
            ?.removePrefix("refs/tags/")
            ?.trim()
            ?: ""
        val buildChannel = when {
            envBuildChannel.isNotBlank() -> envBuildChannel
            buildReleaseTag.isNotBlank() || envRefType.equals("tag", ignoreCase = true) -> "release"
            !System.getenv("GITHUB_ACTIONS").isNullOrBlank() -> "action"
            else -> "local"
        }
        val gitCommitTimestamp = runGit("show", "-s", "--format=%ct", "HEAD").toLongOrNull() ?: 0L
        val buildRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.trim() ?: ""

        buildConfigField("String", "GIT_COMMIT_SHA", "\"${escapeBuildConfigString(gitCommitSha)}\"")
        buildConfigField("String", "GIT_COMMIT_FULL_SHA", "\"${escapeBuildConfigString(gitCommitFullSha)}\"")
        buildConfigField("String", "GIT_BRANCH", "\"${escapeBuildConfigString(gitBranch)}\"")
        buildConfigField("String", "GIT_REF_TYPE", "\"${escapeBuildConfigString(gitRefType)}\"")
        buildConfigField("String", "BUILD_CHANNEL", "\"${escapeBuildConfigString(buildChannel)}\"")
        buildConfigField("String", "BUILD_RELEASE_TAG", "\"${escapeBuildConfigString(buildReleaseTag)}\"")
        buildConfigField("long", "GIT_COMMIT_TIMESTAMP", "${gitCommitTimestamp}L")
        buildConfigField("String", "GITHUB_RUN_NUMBER", "\"${escapeBuildConfigString(buildRunNumber)}\"")

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Only build universal APK, removing separate 32-bit and 64-bit split APKs
    splits {
        abi {
            isEnable = false
        }
    }

    signingConfigs {
        if (!signingStoreFilePath.isNullOrBlank() && !signingStorePasswordVal.isNullOrBlank()) {
            create("release") {
                storeFile = file(signingStoreFilePath)
                storeType = "PKCS12"
                storePassword = signingStorePasswordVal
                keyAlias = signingKeyAliasVal ?: "key0"
                keyPassword = signingKeyPasswordVal ?: signingStorePasswordVal
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "2048m"
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
            "-opt-in=androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi",
            "-opt-in=kotlinx.coroutines.DelicateCoroutinesApi"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // AndroidX
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose + Material 3
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Testing
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.ui.test.manifest)

    // Media3 (ExoPlayer)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource)
    implementation(libs.media3.session)
    implementation(libs.media3.database)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Ktor (HTTP client for API)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.okhttp)

    // ZXing for QR code rendering
    implementation(libs.zxing.core)

    // Coil (image loading)
    implementation(libs.coil.compose)

    // KotlinX
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
