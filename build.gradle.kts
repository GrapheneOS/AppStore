plugins {
    id("com.android.application") version "8.8.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("androidx.navigation.safeargs") version "2.8.7" apply false
    id("com.google.devtools.ksp") version "2.1.10-1.0.29" apply false
}

allprojects {
    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint", "-Xlint:-classfile", "-Xlint:-rawtypes", "-Xlint:-serial"))
    }
}
