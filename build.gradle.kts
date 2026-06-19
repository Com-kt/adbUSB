plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

allprojects {
    configurations.configureEach {
        dependencies.whenObjectAdded {
            if (this is Project) {
                logger.warn("🚨 抓到内鬼了！在配置 [${this@configureEach.name}] 中，有人直接添加了 Project 对象作为依赖！")
                Thread.dumpStack()
            }
        }
    }
}
