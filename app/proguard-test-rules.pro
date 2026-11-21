# The runner discovers instrumentation tests and helpers by reflection.
-keep class com.scandesk.app.** { *; }
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
# Optional compile-time Error Prone annotations in AndroidX Test.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
