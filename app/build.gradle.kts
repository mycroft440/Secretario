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
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    // Keep 0.14.0 pinned until our manual tool-call integration is validated against
    // a newer runtime on real Android hardware.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    // LiteRT-LM 0.14.0 expects coroutines 1.11.x at runtime; pin it explicitly.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
