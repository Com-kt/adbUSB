@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.text.SimpleDateFormat
import java.util.Date
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val propCompileSdk = providers.gradleProperty("COMPILE_SDK").get().toInt()
val propMinSdk = providers.gradleProperty("MIN_SDK").get().toInt()
val propTargetSdk = providers.gradleProperty("TARGET_SDK").get().toInt()
val propVersionCode = providers.gradleProperty("VERSION_CODE").get().toInt()

val buildDate = SimpleDateFormat("yyyyMMdd").format(Date())
val versionPrefix = providers.gradleProperty("VERSION_PREFIX").get()
val propBuildTools = providers.gradleProperty("BUILDTOOLS_VERSION").get()

val envNewStorePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: ""
val envNewKeyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: ""
val envNewKeyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: ""

android {
    namespace = "com.web.view.kitty"
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
        applicationId = "com.web.view.kitty"
        minSdk = propMinSdk
        targetSdk = propTargetSdk
        versionCode = propVersionCode
        versionName = "$versionPrefix-$buildDate"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        vectorDrawables { 
            useSupportLibrary = true
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
    
    kotlin {
        compilerOptions {
            languageVersion = KotlinVersion.KOTLIN_2_4
            apiVersion = KotlinVersion.KOTLIN_2_4
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
    
    testBuildType = "debug"
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), 
                "proguard-rules.pro"
            )
            optimization.keepRules {
                // ignoreFrom 只允许忽略来自远程库的依赖
                ignoreFrom("org.jetbrains.kotlinx:kotlinx-coroutines-android")
                ignoreFrom("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm")
            }
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
      //  abortOnError = false
    }
    
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    runtimeOnly(libs.bundles.coroutines.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.webgpu)
    implementation(libs.coil.compose)
    implementation(libs.androidx.annotation.experimental)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.compose.lifecycle)
}
