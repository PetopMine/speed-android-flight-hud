// Top-level build file. Plugins are declared here and applied in the modules.
// NOTE: since AGP 9.0 the `com.android.application` plugin compiles Kotlin itself.
// Applying `org.jetbrains.kotlin.android` on top of it is an error, so it is not declared here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
