@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

val propCompileSdk = providers.gradleProperty("COMPILE_SDK").get().toInt()
val propMinSdk = providers.gradleProperty("MIN_SDK").get().toInt()
val propTargetSdk = providers.gradleProperty("TARGET_SDK").get().toInt()
val propVersionCode = providers.gradleProperty("VERSION_CODE").get().toInt()

val buildDate = SimpleDateFormat("yyyyMMdd").format(Date())
val versionPrefix = providers.gradleProperty("VERSION_PREFIX").get()
val propNdk = providers.gradleProperty("NDK_VERSION").get()
val propCmake = providers.gradleProperty("CMAKE_VERSION").get()
val propBuildTools = providers.gradleProperty("BUILDTOOLS_VERSION").get()

val injectKotlinMetadataToRoot = tasks.register<Sync>("injectKotlinMetadataToRoot") {
    val kotlinMetadataTask = tasks.named("kotlinToolingMetadata")
    dependsOn(kotlinMetadataTask)
    from(kotlinMetadataTask.map { it.outputs.files })
    destinationDirectory.set(layout.buildDirectory.dir("generated/kotlin-metadata-root"))
}

android {
    namespace = "com.adb.kitty.compose"
    compileSdk = propCompileSdk
    buildToolsVersion = "$propBuildTools"
    ndkVersion = "$propNdk"
    
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
    
    androidResources {
        generateLocaleConfig = true
    }
    
    defaultConfig {
        applicationId = "com.adb.kitty.compose"
        minSdk = propMinSdk
        targetSdk = propTargetSdk
        versionCode = propVersionCode
        versionName = "$versionPrefix-$buildDate-android"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        vectorDrawables { 
            useSupportLibrary = true
        }
        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
        }
        externalNativeBuild {
            cmake {
                abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }
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
    
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "$propCmake"
        }
    }
    
    signingConfigs {
        create("adb") {
        // keystore file，.bks & .jks
            storeFile = file("${project.rootDir}/release.jks")
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            
            enableV1Signing = true
            enableV2Signing = true
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
                // 强行忽略 R8 报错提示中列出的精确字符串坐标
                ignoreFrom("com.github.L-JINBIN:MTDataFilesProvider")
                ignoreFrom("com.flyfishxu:kadb")
                ignoreFrom("org.conscrypt:conscrypt-openjdk-uber")
                ignoreFrom("org.jetbrains.kotlinx:kotlinx-coroutines-core")
                ignoreFrom("org.jetbrains.kotlinx:kotlinx-coroutines-android")
                ignoreFrom("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm")
                ignoreFrom("com.github.topjohnwu.libsu:service")
                ignoreFrom("com.github.topjohnwu.libsu:io")
                ignoreFrom("com.github.topjohnwu.libsu:core")
                ignoreFrom("org.lsposed.hiddenapibypass:hiddenapibypass")
                ignoreFrom("io.nayuki:qrcodegen")
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
        textReport = true
    }
    
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.resources?.addGeneratedSourceDirectory(
            injectKotlinMetadataToRoot,
            Sync::destinationDirectory
        )
    }
}

dependencies {
    runtimeOnly(libs.bundles.coroutines.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.mt.dataFilesProvider)
    implementation(libs.lsposed.hiddenapibypass)
    implementation(libs.nayuki.qrcode)
    implementation(libs.bundles.libsu)
    implementation(libs.com.flyfishxu.kadb)
    implementation(libs.org.conscrypt.openjdk.uber)
    implementation(libs.androidx.annotation.experimental)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.compose.lifecycle)
    debugImplementation(libs.bundles.compose.debug)
}