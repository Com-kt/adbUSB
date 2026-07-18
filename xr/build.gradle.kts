@file:Suppress("UnstableApiUsage")

import java.text.SimpleDateFormat
import java.util.Date
import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val propCompileSdk = providers.gradleProperty("COMPILE_SDK").get().toInt()
val propXrMinSdk = providers.gradleProperty("XR_MIN_SDK").get().toInt()
val propTargetSdk = providers.gradleProperty("TARGET_SDK").get().toInt()
val buildDate = SimpleDateFormat("yyyyMMdd").format(Date())
val versionPrefix = providers.gradleProperty("VERSION_PREFIX").get()
val propVersionCode = providers.gradleProperty("VERSION_CODE").get().toInt()
val propBuildTools = providers.gradleProperty("BUILDTOOLS_VERSION").get()

val envNewStorePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: ""
val envNewKeyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: ""
val envNewKeyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: ""

android {
    namespace = "com.kitty.compose.xr"
    compileSdk = propCompileSdk
    buildToolsVersion = "$propBuildTools"
    
    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    defaultConfig {
        applicationId = "com.kitty.compose.xr"
        minSdk = propXrMinSdk
        targetSdk = propTargetSdk
        versionCode = propVersionCode
        versionName = "$versionPrefix-$buildDate"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_25
        }
    }
    
    signingConfigs {
        create("adb") {
        // keystore file，.bks & .jks & .p12
            storeFile = file("new_full_ec_key.jks")
            storePassword = envNewStorePassword
            keyAlias = envNewKeyAlias
            keyPassword = envNewKeyPassword
            storeType = "PKCS12"
            enableV1Signing = false
            enableV2Signing = false
            enableV3Signing = true
            enableV4Signing = true
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = false
            vcsInfo.include = false
            signingConfig = signingConfigs.getByName("adb")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("adb")
        }
    }
    
    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
        prefab = true
    }
    
    lint {
        checkDependencies = false
    }
    
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.bundles.coroutines.runtime)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.annotation.experimental)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.bundles.compose.xr)
    debugImplementation(libs.bundles.compose.xr.debug)
}
