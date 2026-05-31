import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.skrgba.seeker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.skrgba.seeker"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "1.1.4"

        val localProps = Properties().also { props ->
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
        }
        buildConfigField("String", "HELIUS_RPC_URL", "\"${localProps.getProperty("HELIUS_RPC_URL", "")}\"")


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-26"
                )
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    val localProps = Properties().also { props ->
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    }

    signingConfigs {
        create("release") {
            storeFile = file(localProps.getProperty("KEYSTORE_PATH", ""))
            storePassword = localProps.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias = localProps.getProperty("KEY_ALIAS", "")
            keyPassword = localProps.getProperty("KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isJniDebuggable = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
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
        viewBinding = true
        buildConfig = true
    }
}

configurations.all {
    resolutionStrategy {
        force("androidx.core:core-ktx:1.15.0")
        force("androidx.core:core:1.15.0")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    
    // Solana & Wallet
    implementation(libs.solana.mobile.wallet)
    implementation(libs.solana.web3)
    implementation(libs.solana.rpc.core)
    implementation(libs.solana.rpc.solana)
    implementation(libs.multimult)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    
    // UI & Images
    implementation(libs.coil)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
