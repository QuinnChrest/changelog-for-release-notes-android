# The shrunk build only. Its tests run in the app's process and take shared
# libraries from the app, so R8 mustn't strip what only the tests use. Kept
# whole: Kotlin, coroutines and AndroidX. Changelog's own code and
# kotlinx.serialization are shrunk exactly as in release, which is what this
# build is for.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.** { *; }
-dontwarn kotlin.**
