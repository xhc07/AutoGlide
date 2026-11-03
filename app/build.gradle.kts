import com.android.build.gradle.internal.api.BaseVariantOutputImpl

// AGP
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    // 命名空间
    namespace = "com.xhc07.autoglide"
    // 编译时使用的Android SDK版本
    compileSdk = 37
    // Android 37 使用次版本号，0 表示 37.0
    compileSdkMinor = 0
    // 使用已安装的 Build Tools，避免依赖默认的 35.0.0
    buildToolsVersion = "37.0.0"
    defaultConfig {
        // 应用ID: 包名
        applicationId = "com.xhc07.autoglide"
        // 最低支持SDK版本
        minSdk = 26
        // 目标设备的SDK版本
        targetSdk = 37
        // 版本号
        versionCode = 23
        // 版本名称
        versionName = "1.0.0"
        // 单元测试
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            // 使用Debug证书签名以便直接安装测试
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // 自定义APK输出名称
    applicationVariants.all {
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName =
                "AutoGlide-v${defaultConfig.versionName}.apk"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        // 启用视图绑定
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
