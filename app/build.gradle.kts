import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Resolve a signing property from local.properties first, then the environment
// (so CI can inject secrets without a local.properties file). Returns null when
// unset or blank — a blank local.properties entry falls through to the env
// rather than masking it.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingProp(name: String): String? =
    localProps.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

// Sign only when the keystore is actually usable: all four properties present
// AND the store file exists on disk. Anything less (missing .jks, blank
// passwords) builds an unsigned release artifact instead of failing — with a
// warning, so a misconfigured CI secret can't silently ship an unsigned AAB.
val releaseSigningReady: Boolean = run {
    val names = listOf(
        "MATRON_UPLOAD_STORE_FILE",
        "MATRON_UPLOAD_STORE_PASSWORD",
        "MATRON_UPLOAD_KEY_ALIAS",
        "MATRON_UPLOAD_KEY_PASSWORD",
    )
    val values = names.associateWith { signingProp(it) }
    val storeFile = values["MATRON_UPLOAD_STORE_FILE"]
    when {
        values.values.all { it == null } -> false // signing simply not configured
        values.values.any { it == null } -> {
            logger.warn(
                "Release signing partially configured — missing ${
                    values.filterValues { it == null }.keys.joinToString()
                }; building UNSIGNED release artifacts."
            )
            false
        }
        !rootProject.file(storeFile!!).exists() -> {
            logger.warn(
                "Release signing configured but keystore '$storeFile' not found; " +
                    "building UNSIGNED release artifacts."
            )
            false
        }
        else -> true
    }
}

android {
    namespace = "chat.matron.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "chat.matron.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = rootProject.file(signingProp("MATRON_UPLOAD_STORE_FILE")!!)
                storePassword = signingProp("MATRON_UPLOAD_STORE_PASSWORD")
                keyAlias = signingProp("MATRON_UPLOAD_KEY_ALIAS")
                keyPassword = signingProp("MATRON_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Only sign when the keystore is fully usable; otherwise the build
            // still produces an (unsigned) artifact instead of failing.
            signingConfig = signingConfigs.getByName("release")
                .takeIf { releaseSigningReady }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources.excludes += "META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    debugImplementation(libs.compose.ui.tooling)

    // QR *generation* only (Settings → Link a Device). Pure Java; scanning
    // uses the Play-services code scanner instead (no camera permission).
    implementation(libs.zxing.core)

    // Sign-in QR scanning via the Play-services code scanner: Google-provided
    // capture UI, NO CAMERA permission and no manifest change. Degrades to the
    // manual link-code path when Play services is unavailable.
    implementation(libs.play.services.code.scanner)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.turbine)

    // Marketing screenshot rig only (androidTest never runs in CI — see
    // MarketingScreenshots.kt and tools/screenshots.sh).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
