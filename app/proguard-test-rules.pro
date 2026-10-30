# The UI tests, shrunk alongside the shrunk build. Error Prone's annotations
# (in the test libraries) name a compiler class Android doesn't have.
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
# The tests are found by name: keep them whole.
-keep class com.yourversionnumber.changelog.TestCycle { *; }
-dontobfuscate
