plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val requireReleaseSigning = providers.environmentVariable("REQUIRE_RELEASE_SIGNING").orNull == "true"
check(!requireReleaseSigning || !releaseKeystorePath.isNullOrBlank()) {
    "Release signing requires ANDROID_KEYSTORE_PATH."
}

android {
    namespace = "io.mo.xatype"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.mo.xatype"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "2.1.5"
    }

    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                check(storeFile!!.isFile) { "Release keystore file does not exist." }
                fun signingSecret(name: String): String =
                    providers.environmentVariable(name).orNull
                        ?.takeIf { it.isNotBlank() }
                        ?: error("Release signing requires $name.")
                storePassword = signingSecret("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingSecret("ANDROID_KEY_ALIAS")
                keyPassword = signingSecret("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        configureEach {
            buildConfigField("boolean", "INPUT_DIAGNOSTICS", "false")
        }
        release {
            if (!releaseKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
