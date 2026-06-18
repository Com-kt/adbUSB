@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.text.SimpleDateFormat
import java.util.Date
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.util.GradleVersion

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

abstract class GenerateKotlinMetadataTask : DefaultTask() {
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Input abstract val agpVersion: Property<String>
    @get:Input abstract val kotlinVersion: Property<String>
    @get:Input abstract val kspVersion: Property<String>
    @get:Input abstract val kotlinxCoroutinesVersion: Property<String>
    @get:Input abstract val composeBomVersion: Property<String>
    @get:Input abstract val kadbVersion: Property<String>
    @get:Input abstract val hiddenapibypassVersion: Property<String>
    @get:Input abstract val libsuVersion: Property<String>
    @get:Input abstract val mtDataFilesProviderVersion: Property<String>
    @get:Input abstract val lifecycleVersion: Property<String>
    @get:Input abstract val nayukiQRVersion: Property<String>
    
    @get:Input abstract val sourceCompatibility: Property<String>
    @get:Input abstract val targetCompatibility: Property<String>
    @get:Input abstract val kotlinLanguageVersion: Property<String>
    @get:Input abstract val kotlinApiVersion: Property<String>
    @get:Input abstract val kotlinJvmTarget: Property<String>

    @get:Input abstract val hmppEnabled: Property<Boolean>
    @get:Input abstract val compatibilityMetadataVariantEnabled: Property<Boolean>
    @get:Input abstract val kpmEnabled: Property<Boolean>

    @TaskAction
    fun run() {
        val dynamicSchemaVersion = try {
            val metadataClass = Class.forName("org.jetbrains.kotlin.tooling.KotlinToolingMetadata")
            metadataClass.getField("SCHEMA_VERSION").get(null) as? String ?: "1.1.0"
        } catch (e: Exception) {
            "1.1.0"
        }

        val currentGradleVersion = GradleVersion.current().version

        val jsonContent = """
        {
          "schemaVersion": "$dynamicSchemaVersion",
          "buildSystem": "Gradle",
          "buildSystemVersion": "$currentGradleVersion", 
          "buildPlugin": "org.jetbrains.kotlin.gradle.plugin.KotlinAndroidPluginWrapper",
          "buildPluginVersion": "${kotlinVersion.get()}",
          "projectSettings": {
            "isHmppEnabled": ${hmppEnabled.get()},
            "isCompatibilityMetadataVariantEnabled": ${compatibilityMetadataVariantEnabled.get()},
            "isKPMEnabled": ${kpmEnabled.get()},
            "androidGradlePluginVersion": "${agpVersion.get()}",
            "kspPluginVersion": "${kspVersion.get()}",
            "kotlinxCoroutinesVersion": "${kotlinxCoroutinesVersion.get()}",
            "androidxLifecycleVersion": "${lifecycleVersion.get()}",
            "composeBomVersion": "${composeBomVersion.get()}",
            "composeCompilerVersion": "${kotlinVersion.get()}",
            "kotlinLanguageVersion": "${kotlinLanguageVersion.get()}",
            "kotlinApiVersion": "${kotlinApiVersion.get()}",
            "kotlinJvmTarget": "${kotlinJvmTarget.get()}",
            "kadbVersion": "${kadbVersion.get()}",
            "org.lsposed.hiddenapibypass:hiddenapibypassVersion": "${hiddenapibypassVersion.get()}",
            "com.github.topjohnwu.libsu:libsuVersion": "${libsuVersion.get()}",
            "MTDataFilesProviderVersion": "${mtDataFilesProviderVersion.get()}",
            "nayukiQRVersion": "${nayukiQRVersion.get()}"
          },
          "projectTargets": [
            {
              "target": "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget",
              "platformType": "androidJvm",
              "extras": {
                "android": {
                  "sourceCompatibility": "${sourceCompatibility.get()}",
                  "targetCompatibility": "${targetCompatibility.get()}"
                }
              }
            }
          ]
        }
        """.trimIndent()

        val targetFile = outputDir.file("kotlin-tooling-metadata.json").get().asFile
        targetFile.parentFile.mkdirs()
        targetFile.writeText(jsonContent)
    }
}

val injectKotlinMetadataToRoot = tasks.register<GenerateKotlinMetadataTask>("injectKotlinMetadataToRoot") {
    outputDir.set(layout.buildDirectory.dir("generated/kotlin-metadata-root"))

    agpVersion.set(providers.provider { libs.versions.agp.get() })
    kotlinVersion.set(providers.provider { libs.versions.kotlin.get() })
    kspVersion.set(providers.provider { libs.versions.ksp.get() })
    kotlinxCoroutinesVersion.set(providers.provider { libs.versions.kotlinxCoroutines.get() })
    composeBomVersion.set(providers.provider { libs.versions.compose.bom.get() })
    kadbVersion.set(providers.provider { libs.versions.kadb.get() })
    hiddenapibypassVersion.set(providers.provider { libs.versions.hiddenapibypassVersion.get() })
    libsuVersion.set(providers.provider { libs.versions.libsuVersion.get() })
    mtDataFilesProviderVersion.set(providers.provider { libs.versions.mtDataFilesProvider.get() })
    lifecycleVersion.set(providers.provider { libs.versions.lifecycle.get() })
    nayukiQRVersion.set(providers.provider { libs.versions.nayukiQR.get() })

    sourceCompatibility.set(providers.provider { android.compileOptions.sourceCompatibility.toString() })
    targetCompatibility.set(providers.provider { android.compileOptions.targetCompatibility.toString() })
    
    kotlinLanguageVersion.set(providers.provider { kotlin.compilerOptions.languageVersion.get().version })
    kotlinApiVersion.set(providers.provider { kotlin.compilerOptions.apiVersion.get().version })
    kotlinJvmTarget.set(providers.provider { kotlin.compilerOptions.jvmTarget.get().target })

    hmppEnabled.set(providers.provider {
        val explicitFlag = project.findProperty("kotlin.mpp.enableGranularMetadataCompilation")?.toString()?.toBoolean()
        explicitFlag ?: project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") || true
    })

    compatibilityMetadataVariantEnabled.set(providers.provider {
        project.findProperty("kotlin.mpp.enableCompatibilityMetadataVariant")?.toString()?.toBoolean() ?: false
    })

    kpmEnabled.set(providers.provider {
        project.findProperty("kotlin.experimental.kpm.enabled")?.toString()?.toBoolean() ?: false
    })
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
            GenerateKotlinMetadataTask::outputDir
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
    implementation(libs.androidx.annotation.experimental)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.compose.lifecycle)
    debugImplementation(libs.bundles.compose.debug)
}