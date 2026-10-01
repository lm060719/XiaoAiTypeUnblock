plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.mo.xatype"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.mo.xatype"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "2.1.3"
    }

    buildTypes {
        configureEach {
            buildConfigField("boolean", "INPUT_DIAGNOSTICS", "false")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
        create("diagnostic") {
            initWith(getByName("release"))
            versionNameSuffix = "-pad-diag1"
            buildConfigField("boolean", "INPUT_DIAGNOSTICS", "true")
            matchingFallbacks += "release"
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    compileOnly(files("libs/libxposed-api-102.0.0.jar"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("junit:junit:4.13.2")
}
