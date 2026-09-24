import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/*
 * Release signing lives outside the repository. `keystore.properties` (gitignored) names the
 * keystore file and alias; the two passwords are read from HAVEN_STORE_PASSWORD /
 * HAVEN_KEY_PASSWORD so they never have to be written to disk (the properties file may hold
 * them as a fallback on a machine you trust). Without the file the release build stays unsigned,
 * exactly as before. See docs/RELEASE_SIGNING.md.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) keystorePropertiesFile.inputStream().use { load(it) }
}
fun signingSecret(property: String, env: String): String? = System.getenv(env) ?: keystoreProperties.getProperty(property)
val releaseSigningAvailable = keystorePropertiesFile.isFile &&
    keystoreProperties.getProperty("storeFile") != null &&
    signingSecret("storePassword", "HAVEN_STORE_PASSWORD") != null

android {
    namespace = "com.norypt.haven"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.norypt.haven"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = signingSecret("storePassword", "HAVEN_STORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("keyAlias") ?: "haven-release"
                keyPassword = signingSecret("keyPassword", "HAVEN_KEY_PASSWORD") ?: signingSecret("storePassword", "HAVEN_STORE_PASSWORD")
                // minSdk 31: v1 (JAR) signing is dead weight; v2 is mandatory, v3 carries the
                // rotation lineage. v4 (incremental install) is not needed for sideloaded APKs.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed only when keystore.properties exists (out-of-tree); see docs/RELEASE_SIGNING.md.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Only bundled English resources ship in v1; no runtime resource downloads exist.
        localeFilters += listOf("en")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
        // Android 15+ requires 16 KB aligned native libraries; SQLCipher and Argon2kt ship them.
        jniLibs.useLegacyPackaging = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

/*
 * OFFLINE GUARD. For every variant, parse the merged manifest and fail the build if any
 * network-related permission has been merged in (by Haven or by a dependency).
 */
val forbiddenPermissions = listOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.BLUETOOTH",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.NFC",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.READ_CONTACTS",
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    "com.google.android.c2dm.permission.RECEIVE",
)

abstract class VerifyOfflineManifestTask : DefaultTask() {
    @get:InputFile abstract val mergedManifest: RegularFileProperty
    @get:Input abstract val forbidden: ListProperty<String>
    @get:Input abstract val debugBuild: Property<Boolean>

    @TaskAction
    fun verify() {
        val text = mergedManifest.get().asFile.readText()
        val found = forbidden.get().filter { perm -> Regex("android:name=\"" + Regex.escape(perm) + "\"").containsMatchIn(text) }
        if (found.isNotEmpty()) {
            throw GradleException("Offline guard: forbidden permission(s) in merged manifest: " + found.joinToString())
        }
        val servicesExported = Regex("<service[^>]*android:exported=\"true\"").containsMatchIn(text)
        if (servicesExported) throw GradleException("Offline guard: an exported service was merged into the manifest")
        val providers = Regex("<provider[^>]*android:exported=\"true\"").containsMatchIn(text)
        if (providers) throw GradleException("Offline guard: an exported content provider was merged into the manifest")
        // Only the launcher activity and the system-broadcast receiver may be exported.
        val allowedExported = mutableSetOf("com.norypt.haven.MainActivity", "com.norypt.haven.alarm.SystemEventReceiver")
        // Debug builds carry a test hook receiver (src/debug); it must never appear in release.
        if (debugBuild.get()) allowedExported += "com.norypt.haven.debug.DebugTriggerReceiver"
        val componentRegex = Regex("<(activity|receiver)\\b([^>]*)>", RegexOption.DOT_MATCHES_ALL)
        val nameRegex = Regex("android:name=\"([^\"]+)\"")
        val exportedRegex = Regex("android:exported=\"true\"")
        val unexpected = componentRegex.findAll(text)
            .filter { exportedRegex.containsMatchIn(it.groupValues[2]) }
            .mapNotNull { nameRegex.find(it.groupValues[2])?.groupValues?.get(1) }
            .filter { it !in allowedExported }
            .toList()
        if (unexpected.isNotEmpty()) throw GradleException("Offline guard: unexpected exported components: " + unexpected.joinToString())
        logger.lifecycle("Offline guard: merged manifest declares no network permissions and no unexpected exported components")
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val task = project.tasks.register<VerifyOfflineManifestTask>("verifyOfflineManifest$name") {
            group = "verification"
            description = "Fails the build if the merged manifest for $name declares network permissions."
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            forbidden.set(forbiddenPermissions)
            debugBuild.set(variant.buildType == "debug")
        }
        project.afterEvaluate {
            project.tasks.named("assemble$name").configure { dependsOn(task) }
            project.tasks.named("package$name").configure { dependsOn(task) }
        }
    }
}

dependencies {
    implementation(project(":vault-crypto"))
    implementation(project(":vault-storage"))
    implementation(project(":recurrence-engine"))
    implementation(project(":alarm-runtime"))
    implementation(project(":backup-format"))
    implementation(project(":security-controls"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
