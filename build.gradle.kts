plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.androidx.navigation.safeargs) apply false
    alias(libs.plugins.google.devtools.ksp) apply false
}

allprojects {
    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint", "-Xlint:-classfile", "-Xlint:-rawtypes", "-Xlint:-serial"))
    }
}
