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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
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
    
    implementation("androidx.appcompat:appcompat:1.4.2")
    implementation("androidx.core:core:1.6.0")
    implementation("com.google.android.material:material:1.4.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
}