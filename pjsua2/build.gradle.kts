plugins {
    id("com.android.library")
}

android {
    namespace = "org.pjsip.pjsua2"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }

    sourceSets {
        getByName("main") {
            java.srcDir("generated/java")
            jniLibs.srcDir("generated/jniLibs")
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }
}
