// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.spotless)
  alias(libs.plugins.detekt) apply false
}

spotless {
  kotlin {
    target("app/src/**/*.kt")
    targetExclude("**/build/**")
    ktfmt("0.54").googleStyle()
  }
  kotlinGradle {
    target("*.gradle.kts", "app/*.gradle.kts")
    ktfmt("0.54").googleStyle()
  }
}
