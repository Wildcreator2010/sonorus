plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

import java.util.Properties

// region 签名配置：凭据只从 local.properties 读取（已 gitignore），不进版本库
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile: String? = localProps.getProperty("RELEASE_STORE_FILE")?.trim()?.takeIf { it.isNotEmpty() }
val releaseStorePassword: String? = localProps.getProperty("RELEASE_STORE_PASSWORD")?.trim()
val releaseKeyAlias: String? = localProps.getProperty("RELEASE_KEY_ALIAS")?.trim()?.takeIf { it.isNotEmpty() }
val releaseKeyPassword: String? = localProps.getProperty("RELEASE_KEY_PASSWORD")?.trim()
val hasReleaseSigning: Boolean =
    releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null
// endregion

android {
    namespace = "com.wildcreator.sonorus"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wildcreator.sonorus"
        minSdk = 26 // Android 8.0
        targetSdk = 34
        versionCode = 6
        versionName = "0.1.5"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // 生产构建：开启代码压缩 + 资源收缩 + 日志剥离（见 proguard-rules.pro）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 无签名凭据时回退 debug 签名，保证干净机器上 release 依然可构建；
            // 但必须醒目警告：debug 签名的产物绝不可对外发布（供应链风险）。
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    """
                    |⚠️⚠️⚠️ Sonorus release 构建未检测到 release 签名凭据（local.properties 的 RELEASE_STORE_FILE 等），
                    |    已回退到 debug 签名！此 APK 仅可本地自测，绝不可分发上线。
                    """.trimMargin()
                )
                signingConfigs.getByName("debug")
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
    }
    testOptions {
        // 纯 JVM 单测里 android.jar 的方法默认抛 "not mocked"。
        // AppModule 的 @AppScope 异常处理器要调 Log.w，兜底路径本身不该让测试炸掉。
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    // 密钥安全存储：Keystore 主密钥 + EncryptedSharedPreferences
    implementation(libs.androidx.security.crypto)

    // 网络层只用 OkHttp：ProviderApiClient 直接构造请求，三套协议统一走 SSE 解析
    implementation(libs.okhttp)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // JVM 单元测试：协议适配层为纯函数，无需设备即可验证
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // 迁移测试用纯 JDBC sqlite 驱动直接执行迁移 SQL，不依赖 Robolectric / 设备
    testImplementation(libs.sqlite.jdbc)
}
