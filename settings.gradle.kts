/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    maven("https://jitpack.io")
    maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
    maven("https://maven.scijava.org/content/repositories/public/")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    maven("https://s01.oss.sonatype.org/content/groups/public/")
    maven("https://plugins.gradle.org/m2/")
  }
}

rootProject.name = "adbUSB"

include(":app")