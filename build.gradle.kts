plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

val externalBuildRoot = providers.environmentVariable("CONTEXTOTO_BUILD_ROOT").orNull
if (externalBuildRoot != null) {
    layout.buildDirectory.set(file("$externalBuildRoot/root"))
    subprojects {
        layout.buildDirectory.set(file("$externalBuildRoot/${project.name}"))
    }
}
