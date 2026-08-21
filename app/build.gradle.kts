plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.callguard.ai"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.callguard.ai"
        minSdk = 29
        // Android 17 targeting remains intentionally deferred until the app and
        // LiteRT-LM native path are exercised on Android 17 behavior changes.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("public") {
            dimension = "distribution"
            buildConfigField("boolean", "PRIVILEGED_PSTN_AUDIO", "false")
            buildConfigField("boolean", "UNIVERSAL_VOIP", "false")
        }
        create("privileged") {
            dimension = "distribution"
            // Keep the OEM/system experiment separate from the Play-safe package.
            applicationIdSuffix = ".privileged"
            versionNameSuffix = "-privileged"
            buildConfigField("boolean", "PRIVILEGED_PSTN_AUDIO", "true")
            buildConfigField("boolean", "UNIVERSAL_VOIP", "false")
        }
        create("voip") {
            dimension = "distribution"
            // Android 10+ universal route: the phone number must arrive by SIP/VoIP
            // (direct DID, number porting, or carrier forwarding to the SIP provider).
            applicationIdSuffix = ".voip"
            versionNameSuffix = "-voip"
            buildConfigField("boolean", "PRIVILEGED_PSTN_AUDIO", "false")
            buildConfigField("boolean", "UNIVERSAL_VOIP", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // compileSdk 37 is used for API compatibility checks, but targetSdk 37
        // deliberately waits for Android 17 behavior-change testing on hardware.
        disable += "OldTargetApi"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")

    // Official Google AI Edge runtime for on-device FunctionGemma / .litertlm models.
    // Keep this pinned for reproducible builds; upgrades are validated by CI before merge.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // PJSIP/PJSUA2 is generated from the official 2.17 source by GitHub Actions.
    // The VoIP flavor is the only distribution linked to it.
    "voipImplementation"(project(":pjsua2"))

    // Offline mobile PT-BR STT bootstrap. The small Portuguese model is installed
    // separately (~31 MB); it is not embedded in the APK.
    "voipImplementation"("com.alphacephei:vosk-android:0.3.47@aar")
    "voipImplementation"("net.java.dev.jna:jna:5.18.1@aar")
}
