plugins {
    id("com.android.application")
}

android {
    namespace = "tw.celeste.saodu"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.celeste.saodu"
        minSdk = 21
        targetSdk = 35
        versionCode = 8
        versionName = "1.7"
    }

    // 簽章檔放在 GitHub 的 Secrets（不放進公開程式庫），打包時才還原。
    // 固定使用同一把簽章，之後的新版本才能直接覆蓋安裝。
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    val keystorePassword = System.getenv("SIGNING_PASSWORD")?.trim()
    val hasFamilyKey = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()

    signingConfigs {
        if (hasFamilyKey) {
            create("family") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "saodu"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // 沒有設定 Secrets 時，退回用 debug 簽章，仍然可以安裝。
            signingConfig = if (hasFamilyKey) signingConfigs.getByName("family") else signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
