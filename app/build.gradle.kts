plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lianyu.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lianyu.ai.zzz"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.0.1"

        // Force multi-DEX output
        multiDexEnabled = true

        buildConfigField("String", "HARDENING_LEVEL", "\"OPEN_SOURCE\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    ndkVersion = "30.0.14904198"

    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = System.getenv("LIANYU_STORE_PASSWORD") ?: project.findProperty("LIANYU_STORE_PASSWORD") as String? ?: "debug_password_placeholder"
            keyAlias = System.getenv("LIANYU_KEY_ALIAS") ?: project.findProperty("LIANYU_KEY_ALIAS") as String? ?: "your_alias"
            keyPassword = System.getenv("LIANYU_KEY_PASSWORD") ?: project.findProperty("LIANYU_KEY_PASSWORD") as String? ?: "debug_password_placeholder"
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        
    }

    // jniLibs are picked up automatically from src/main/jniLibs/
}



// ── 让 sherpa-onnx 自带的 libonnxruntime.so 与官方 onnxruntime-android 共存 ──
// 原因：core:network / feature:chat 里的 sherpa-onnx aar 自带一份 libonnxruntime.so，
// 跟 feature:memory 直接依赖的官方 onnxruntime-android 里的 libonnxruntime.so 路径完全相同
// （lib/<abi>/libonnxruntime.so），打包时 mergeDebugNativeLibs 会因为两个不同文件同名而报错。
//
// 做法：不再"二选一剥离"，而是用 patchelf 把 sherpa 相关的三个 so
// （libsherpa-onnx-jni.so / libsherpa-onnx-c-api.so / libsherpa-onnx-cxx-api.so）
// 对 libonnxruntime.so 的动态链接引用，改写指向一个改名后的新文件 libonnxruntime_sherpa.so，
// 同时把 sherpa 自带的那份 so 本身也改名 + 改内部 SONAME。
// 这样 APK 里两份 onnxruntime 动态库完全独立共存：
//   libonnxruntime.so         → 官方版本，给 feature:memory 的 Java API 用
//   libonnxruntime_sherpa.so  → sherpa 自带、与其 JNI 完全匹配的版本，只给 sherpa 自己用
// 两边各用各的版本，不存在互相兼容风险。
val sherpaOnnxRawAar = file("../core/network/libs/sherpa-onnx-1.13.3.aar")

val extractSherpaOnnxAar = tasks.register<Copy>("extractSherpaOnnxAar") {
    from(zipTree(sherpaOnnxRawAar))
    into(layout.buildDirectory.dir("sherpaAarExtract"))
}

val patchSherpaOnnxRuntime = tasks.register("patchSherpaOnnxRuntime") {
    dependsOn(extractSherpaOnnxAar)
    doLast {
        val dir = layout.buildDirectory.dir("sherpaAarExtract").get().asFile
        listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach { abi ->
            val jniDir = file("$dir/jni/$abi")
            val originalOnnx = file("$jniDir/libonnxruntime.so")
            val renamedOnnx = file("$jniDir/libonnxruntime_sherpa.so")
            if (!originalOnnx.exists()) return@forEach

            originalOnnx.renameTo(renamedOnnx)
            providers.exec {
                commandLine("patchelf", "--set-soname", "libonnxruntime_sherpa.so", renamedOnnx.absolutePath)
            }.result.get()

            listOf("libsherpa-onnx-jni.so", "libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so").forEach { lib ->
                val target = file("$jniDir/$lib")
                if (target.exists()) {
                    providers.exec {
                        commandLine("patchelf", "--replace-needed", "libonnxruntime.so", "libonnxruntime_sherpa.so", target.absolutePath)
                    }.result.get()
                }
            }
            println("Patched sherpa-onnx so for $abi: libonnxruntime.so -> libonnxruntime_sherpa.so")
        }
    }
}

val repackSherpaOnnxAar = tasks.register<Zip>("repackSherpaOnnxAar") {
    dependsOn(patchSherpaOnnxRuntime)
    from(layout.buildDirectory.dir("sherpaAarExtract"))
    archiveFileName.set("sherpa-onnx-1.13.3-patched.aar")
    destinationDirectory.set(layout.buildDirectory.dir("sherpaAarOutput"))
}




dependencies {
    // Core modules
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    
    implementation(project(":core:ui-common"))

    // Feature modules
    implementation(project(":feature:companion"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:groupchat"))
    implementation(project(":feature:memory"))
    implementation(project(":feature:notification"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:localmodel"))
    
    implementation(project(":feature:backup"))
    

    // sherpa-onnx: 离线流式语音识别/合成，运行时由 app 模块提供
    // 使用下面 repackSherpaOnnxAar 任务生成的"改名版"aar
    // （sherpa 自带的 libonnxruntime.so 已改名为 libonnxruntime_sherpa.so，与官方版共存不冲突）
    implementation(files(repackSherpaOnnxAar.flatMap { it.archiveFile }))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    
    implementation(libs.coil.compose)
    
    
    implementation(libs.androidx.animation)
    implementation(libs.androidx.animation.core)
    implementation(libs.androidx.animation.graphics)
    
    
    implementation(libs.androidx.work.runtime.ktx)
    



    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
