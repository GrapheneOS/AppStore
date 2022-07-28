buildscript {
    repositories {
        // dependabot cannot handle google()
        maven {
            url = uri("https://dl.google.com/dl/android/maven2")
        }
        // dependabot cannot handle mavenCentral()
        maven {
            url = uri("https://repo.maven.apache.org/maven2")
        }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:7.2.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.7.10")
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.43")
        classpath("androidx.navigation:navigation-safe-args-gradle-plugin:2.5.1")
    }
}

allprojects {
    tasks.withType<JavaCompile> {
        val compilerArgs = options.compilerArgs
        compilerArgs.add("-Xlint:unchecked")
        compilerArgs.add("-Xlint:deprecation")
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn"
        )
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
