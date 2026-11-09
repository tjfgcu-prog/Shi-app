plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lianyu.ai.network"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:common"))
    

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    // Kotlin coroutines
    implementation(libs.kotlinx.coroutines.android)
    // sherpa-onnx: 离线语音识别/合成引擎 (v1.13.3)
    // compileOnly: library 模块不能打包 AAR，运行时由 app 模块提供
    compileOnly(files("libs/sherpa-onnx-1.13.3.aar"))

    // JNA：直连 libcrispasr.so（Breeze TTS 本地合成）。必须用 @aar，里面自带 libjnidispatch.so
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    testImplementation(libs.junit)
}
