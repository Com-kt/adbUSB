plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    namespace = "com.adb.kitty"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    
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
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "0.6-20260426"
        
        vectorDrawables { 
            useSupportLibrary = true
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    
    signingConfigs {
        create("adb") {
        // keystore file，.bks & .jks
            storeFile = file("keystore/adb.jks")
            storePassword = findProperty("ADB_KEY_PASSWORD") as String
            keyAlias = findProperty("ADB_KEY_ALIAS") as String
            keyPassword = findProperty("ADB_KEY_KEYPASSWORD") as String
            
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
          //  signingConfig = signingConfigs.getByName("adb")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
         //   signingConfig = signingConfigs.getByName("adb")
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }
    
}

tasks
    .withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>()
    .configureEach {
        compilerOptions
            .jvmTarget
            .set(
                org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
            )
    }

dependencies {
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
}
