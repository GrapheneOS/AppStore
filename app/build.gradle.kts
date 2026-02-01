import java.io.FileInputStream
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val useKeystoreProperties = keystorePropertiesFile.canRead()
val keystoreProperties = Properties()
if (useKeystoreProperties) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    id("com.android.application")
    kotlin("android")
    id("com.google.devtools.ksp")
    id("androidx.navigation.safeargs")
    id("kotlin-parcelize")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

android {
    if (useKeystoreProperties) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                enableV4Signing = true
            }
        }
    }

    compileSdk = 36
    buildToolsVersion = "36.1.0"

    namespace = "app.grapheneos.apps"

    defaultConfig {
        applicationId = "app.grapheneos.apps"
        minSdk = 31
        targetSdk = 36
        versionCode = 34
        versionName = versionCode.toString()

        buildConfigField(String::class.java.name, "REPO_BASE_URL",
            "\"${System.getenv("REPO_BASE_URL") ?: "https://apps.grapheneos.org"}\"")

        buildConfigField(String::class.java.name, "REPO_PUBLIC_KEY", "\"${
            System.getenv("REPO_PUBLIC_KEY") ?: "RWQtZwEu1br1lMh911L3yPOs97cQb9LOks/ALBbqGl21ul695ocWR/ir"
        }\"")

        buildConfigField(String::class.java.name, "REPO_KEY_VERSION",
            "\"${System.getenv("REPO_KEY_VERSION") ?: "0"}\"")
    }

    buildTypes {
        getByName("release") {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (useKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en")
    }

    packaging {
        resources.excludes.addAll(listOf(
            "META-INF/versions/*/OSGI-INF/MANIFEST.MF",
            "org/bouncycastle/pqc/**.properties",
            "org/bouncycastle/x509/**.properties",
        ))
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.activity:activity-ktx:1.12.3")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.7")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")

    val lifecycleVersion = "2.10.0"
    implementation("androidx.lifecycle:lifecycle-viewmodel:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")

    implementation("com.google.android.material:material:1.13.0")

    implementation("org.bouncycastle:bcprov-jdk18on:1.83")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    val glideVersion = "5.0.5"
    implementation("com.github.bumptech.glide:glide:$glideVersion")
    ksp("com.github.bumptech.glide:ksp:$glideVersion")
}
