buildscript {
    dependencies {
        // AGP 9.x has built-in Kotlin. Pin KGP to the same version used by
        // the Compose Compiler plugin so compiler/plugin versions stay aligned.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
