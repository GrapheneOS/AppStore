plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("androidx.navigation.safeargs") version "2.8.1" apply false
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
}

allprojects {
    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint", "-Xlint:-rawtypes", "-Xlint:-serial"))
    }
}
