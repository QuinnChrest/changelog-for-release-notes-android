import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import java.time.temporal.ChronoUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Changelog numbers its builds by Your Version Number's arithmetic, counted
// from its own first day to the day a build is made, in Chicago: 0.0.0 on
// 2026-10-29, 0.1.0 a year on. The same as kit's YourVersionNumber, which
// tests it; keep the two in step. versionCode is the commit count.
val changelogStart: LocalDate = LocalDate.of(2026, 10, 29)

fun yourVersionNumber(since: LocalDate, day: LocalDate): String {
    fun anniversary(year: Int): LocalDate =
        if (since.monthValue == 2 && since.dayOfMonth == 29 && !Year.isLeap(year.toLong())) {
            LocalDate.of(year, 3, 1)
        } else {
            LocalDate.of(year, since.monthValue, since.dayOfMonth)
        }
    var year = day.year
    var last = anniversary(year)
    if (day < last) {
        year -= 1
        last = anniversary(year)
    }
    val age = year - since.year
    return "${age / 10}.${age % 10}.${ChronoUnit.DAYS.between(last, day)}"
}

fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

val buildVersion = yourVersionNumber(changelogStart, LocalDate.now(ZoneId.of("America/Chicago")))
val commitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val commit = git("rev-parse", "--short", "HEAD") ?: "unknown"

android {
    namespace = "com.yourversionnumber.changelog"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yourversionnumber.changelog"
        minSdk = 31
        targetSdk = 37
        versionCode = commitCount
        versionName = buildVersion
        buildConfigField("String", "COMMIT", "\"$commit\"")
        // Where a debug build's "Dev server" points. On a phone over USB,
        // `adb reverse tcp:8790 tcp:8790` and http://localhost:8790.
        buildConfigField("String", "DEV_SERVER", "\"${providers.gradleProperty("changelog.devServer").getOrElse("http://10.0.2.2:8790")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":kit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
