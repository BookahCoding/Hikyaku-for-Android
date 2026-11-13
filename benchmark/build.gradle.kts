plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "dev.herald.benchmark"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    targetProjectPath = ":androidApp"
    defaultConfig {
        minSdk = 28
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        create("profile") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

// Device tests are explicitly selected; ordinary app assembly never depends on these tasks.
androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark" || variant.buildType == "profile"
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.uiautomator)
}
