// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    val gradle_version = "9.2.0"
  //  val kotlin_version = "2.3.21"
    id("com.android.application") version "$gradle_version" apply false
    id("com.android.library") version "$gradle_version" apply false
   // id("org.jetbrains.kotlin.android") version "$kotlin_version" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}