plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.auris2.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "fr.auris2.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "3.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    testImplementation("junit:junit:4.13.2") // seule dépendance : tests unitaires
}
