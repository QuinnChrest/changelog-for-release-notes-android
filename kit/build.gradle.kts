import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
    // DevServerTest runs only with a dev server token: a run with one and a
    // run without aren't the same, so neither is taken from the other's cache.
    inputs.property("devServer", providers.environmentVariable("CHANGELOG_DEV_TOKEN").map { it.isNotEmpty() }.orElse(false))
    inputs.property("devServerUrl", providers.environmentVariable("CHANGELOG_DEV_URL").orElse(""))
}
