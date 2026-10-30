plugins {
    alias(libs.plugins.android.library)}

android {
    namespace = "com.lianyu.ai.feature.localmodel"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    implementation(project(":core:common"))
    implementation(project(":core:domain"))

    implementation(libs.kotlinx.coroutines.core)
    
    implementation("io.github.ljcamargo:llamacpp-kotlin:0.4.0")
    testImplementation(libs.junit)
}
