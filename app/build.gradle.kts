import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.adb.kitty"
    compileSdk = 37
    
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    
    defaultConfig {
        applicationId = "com.adb.kitty"
        minSdk = 26
        targetSdk = 37
        versionCode = 10
        versionName = "2.0-20260512-xiaomi-version"
        
        vectorDrawables { 
            useSupportLibrary = true
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
    
    signingConfigs {
        create("adb") {
        // keystore file，.bks & .jks
            storeFile = file("${project.rootDir}/release.jks")
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("adb")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("adb")
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }
    
    lint {
        textReport = true
    }
    
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

tasks.withType<KotlinJvmCompile>()
    .configureEach {
        compilerOptions
            .jvmTarget
            .set(
                JvmTarget.JVM_25
            )
    }

dependencies {
    runtimeOnly(libs.bundles.coroutines.runtime)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.bundles.bouncycastle)
}