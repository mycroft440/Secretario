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
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")

    // Official Google AI Edge runtime for on-device FunctionGemma / .litertlm models.
    // Pin runtime for reproducible GitHub Actions builds.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    // LiteRT-LM 0.14.0 is built against coroutines 1.11.0; pin it explicitly
    // to avoid resolving an older transitive version at runtime.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
