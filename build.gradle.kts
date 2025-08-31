plugins {
    id("com.android.application") version "8.12.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("androidx.navigation.safeargs") version "2.9.3" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}

allprojects {
    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint", "-Xlint:-classfile", "-Xlint:-rawtypes", "-Xlint:-serial"))
    }
}
