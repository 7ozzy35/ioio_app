plugins {
    id("com.android.application")
}

android {
    namespace = "com.ozancansari.ioio2"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ozancansari.ioio2"
        minSdk = 14
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_7
        targetCompatibility = JavaVersion.VERSION_1_7
    }
    
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        // Bluetooth izin hatalarını görmezden gel
        disable.addAll(listOf("MissingPermission", "HardcodedDebugMode"))
    }
}

dependencies {
    // IOIO kütüphanesi için local libs
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    
    // Android 4+ uyumlu support libraries
    implementation("com.android.support:appcompat-v7:28.0.0")
    implementation("com.android.support:support-v4:28.0.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("com.android.support.test:runner:1.0.2")
    androidTestImplementation("com.android.support.test.espresso:espresso-core:3.0.2")
}