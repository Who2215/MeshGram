plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "com.meshchat.app"
    compileSdk = 35

    val donationUrl = providers.gradleProperty("MESHGRAM_DONATION_URL").orNull.orEmpty()
    val updateManifestUrl = providers.gradleProperty("MESHGRAM_UPDATE_MANIFEST_URL").orNull.orEmpty()
    val releasePublicKey = providers.gradleProperty("MESHGRAM_RELEASE_PUBLIC_KEY_BASE64").orNull.orEmpty()
    fun buildConfigString(value: String): String {
        return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    defaultConfig {
        applicationId = "com.meshchat.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 109
        versionName = "1.0.9"
    }

    val userKeystorePath = providers.gradleProperty("MESHGRAM_KEYSTORE_PATH").orNull
    val userKeystorePassword = providers.gradleProperty("MESHGRAM_KEYSTORE_PASSWORD").orNull
    val userKeyAlias = providers.gradleProperty("MESHGRAM_KEY_ALIAS").orNull
    val userKeyPassword = providers.gradleProperty("MESHGRAM_KEY_PASSWORD").orNull
    val hasUserSigning = listOf(
        userKeystorePath,
        userKeystorePassword,
        userKeyAlias,
        userKeyPassword
    ).all { !it.isNullOrBlank() }

    signingConfigs {
        create("userRelease") {
            if (hasUserSigning) {
                storeFile = file(userKeystorePath!!)
                storePassword = userKeystorePassword
                keyAlias = userKeyAlias
                keyPassword = userKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasUserSigning) {
                signingConfig = signingConfigs.getByName("userRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "MESHGRAM_DONATION_URL", buildConfigString(donationUrl))
        buildConfigField("String", "MESHGRAM_UPDATE_MANIFEST_URL", buildConfigString(updateManifestUrl))
        buildConfigField("String", "MESHGRAM_RELEASE_PUBLIC_KEY_BASE64", buildConfigString(releasePublicKey))
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
