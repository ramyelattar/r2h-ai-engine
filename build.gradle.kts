// Root build file. Declares plugins for the entire project without applying them.
// Each module applies only the plugins it actually needs.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library)     apply false
    alias(libs.plugins.kotlin.android)      apply false
    alias(libs.plugins.kotlin.parcelize)    apply false
}
