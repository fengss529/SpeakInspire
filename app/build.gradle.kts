import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ===== 发布签名 =====
//
// 密码与 keystore 路径放在项目根的 keystore.properties（已在 .gitignore）。
// 这个文件**不存在时**，release 仍然能构建，只是产出未签名包——
// 这是有意的，不是漏配：
//   · 别人 clone 下来跑 gradlew assembleRelease 不该卡在「找不到密钥」；
//   · 远程 CI 里没有密钥，它只需要跑测试。
//
// 想把签名带进 CI，就用环境变量注入，别把 jks 塞进仓库：
//   ORG_GRADLE_PROJECT_storePassword / keyPassword 之类（见 README「发布」一节）

// 见 buildTypes.release 里的说明：只在本地验证混淆时用
val useDebugSigningForRelease =
    providers.gradleProperty("useDebugSigningForRelease").orNull == "true"

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.hatio.speakinspire"
    // 对齐本机已装的 SDK（D:\soft\AndroidSdk只有 android-36）
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hatio.speakinspire"
        minSdk = 26
        targetSdk = 36
        versionCode = 22
        versionName = "0.11.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v1 签名要给 Android 6 及更早；minSdk 是 26，其实用不到，
                // 但留着不增加体积，且某些第三方分发渠道仍会读 v1
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
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
            // 没有签名配置时为 null，Gradle 产出 app-release-unsigned.apk
            //
            // 传 -PuseDebugSigningForRelease=true 会用 debug 签名构建这个变体。
            // 用途只有一个：在已装 debug 包的设备上覆盖安装，**保留原有数据**
            // 地验证 R8 混淆有没有把功能压坏。签名不同就必须先卸载，
            // 而卸载会连 Tink 主密钥一起销毁，加密存的 API Key 就再也解不开了。
            // 正式发包时绝不要带这个参数。
            signingConfig = if (useDebugSigningForRelease) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.findByName("release")
            }
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