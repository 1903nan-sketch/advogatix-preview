import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lista.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lista.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    // Chave de depuração versionada no repositório, para que qualquer build
    // (aqui ou em outra máquina) tenha a mesma assinatura e possa atualizar o
    // app instalado. Para publicar na Play Store, crie uma chave própria.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18.1")
}

// As capturas de tela (Robolectric) são pesadas: só rodam com -Pscreenshots.
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    if (!project.hasProperty("screenshots")) exclude("**/ScreenshotTest*")
}
