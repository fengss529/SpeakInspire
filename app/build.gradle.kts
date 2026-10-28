plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.hatio.speakinspire"
    // 对齐本机已装的 SDK（D:\soft\AndroidSdk只有 android-36）
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hatio.speakinspire"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "0.10.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 设置页的版本号直接读 BuildConfig.VERSION_NAME。
        // 以前是手写在 strings.xml 里的，发版时两次对不上（build.gradle 0.9.0、
        // 界面还显示 V0.8.10），用户报 bug 时报的版本号是错的。
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Robolectric 要读 res/ 和 assets（Room 迁移测试要读 schemas 里的 DDL）。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // 把 Room 导出的 schema 快照挂给单元测试，迁移测试要按版本号取建表语句。
    sourceSets {
        getByName("test").assets.srcDirs("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // 网络：只用 OkHttp 手拼请求。
    //
    // 这里曾经还挂着 retrofit + converter-moshi + moshi + moshi-kotlin，
    // 但代码里一行都没用过——所有请求体是自己拼的字符串，
    // 响应是 ResponseParser 手解的（为的是躲开 ProGuard 规则和反射开销）。
    // 四个库白占体积、白拖编译时间，已删。
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    // Robolectric 提供真实的 Android SQLite，让 Room 的迁移/级联能在 JVM 上验证。
    // 只为 data/local 下的几个测试服务，不影响其他 388 条纯逻辑测试的速度。
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}