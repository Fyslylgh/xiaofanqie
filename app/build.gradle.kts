plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 受限环境下 ~/.android 不可写，AGP 无法自动生成 debug keystore。
// 若项目里存在 .toolchain/debug.keystore 就用它；不存在则回落标准 debug 签名，
// 因此把工程挪到别的机器上编译也不需要改这里的任何东西。
val localKeystore = rootProject.file(".toolchain/debug.keystore")
val useLocalKeystore = localKeystore.exists()

android {
    namespace = "com.fysly.pomodoro"
    // 36 = Android 16。实时活动（Notification.ProgressStyle）只在 API 36 上生效，
    // 低于 36 的系统会自动回落到普通通知样式。
    compileSdk = 36
    // 显式钉住，避免 AGP 默认值与本机已安装的版本对不上
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.fysly.pomodoro"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"
        vectorDrawables { useSupportLibrary = true }
    }

    if (useLocalKeystore) {
        signingConfigs {
            create("localDebug") {
                storeFile = localKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (useLocalKeystore) signingConfig = signingConfigs.getByName("localDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 用 debug 密钥签名，方便直接装机；正式发布请换成自己的 keystore
            signingConfig = if (useLocalKeystore) {
                signingConfigs.getByName("localDebug")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
