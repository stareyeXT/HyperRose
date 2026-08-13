plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val localSigningFile =
    listOf(
        rootProject.file("release-signing/keystore-password.txt"),
        file("C:/daima/release-signing/keystore-password.txt"),
    ).firstOrNull { it.isFile }
        ?: rootProject.file("release-signing/keystore-password.txt")
val localSigning =
    if (localSigningFile.isFile) {
        localSigningFile.readLines().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null
            else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }.toMap()
    } else {
        emptyMap()
    }

fun signingValue(environmentName: String, localName: String): String? =
    System.getenv(environmentName)?.takeIf { it.isNotBlank() }
        ?: localSigning[localName]?.takeIf { it.isNotBlank() }

val releaseKeystoreFile = signingValue("ANDROID_KEYSTORE_FILE", "storeFile")
val releaseKeystorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
val hasReleaseSigningConfig =
    listOf(
        releaseKeystoreFile,
        releaseKeystorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() }
val allowUnsignedRelease =
    providers.gradleProperty("allowUnsignedRelease").orNull?.toBooleanStrictOrNull() == true

if (!hasReleaseSigningConfig && !allowUnsignedRelease) {
    tasks.matching { it.name == "preReleaseBuild" }.configureEach {
        doFirst {
            throw GradleException(
                "Release signing is not configured. Set ANDROID_KEYSTORE_* variables or " +
                    "create release-signing/keystore-password.txt (or C:/daima/release-signing/" +
                    "keystore-password.txt); alternatively pass " +
                    "-PallowUnsignedRelease=true for a local unsigned artifact.",
            )
        }
    }
}

android {
    namespace = "com.dohex.hyperrose"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.dohex.hyperrose"
        minSdk = 35
        targetSdk = 37
        versionCode = 6
        versionName = "0.1.5"
    }
    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}


dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.hyper.notification.focus.api)
    implementation(libs.kavaref.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.navigation3.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.ui)

    compileOnly(libs.libxposed.api)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.compose.ui.tooling)
}
