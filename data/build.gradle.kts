plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.alibaba.data"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:parser"))

    implementation(libs.okhttp)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}
