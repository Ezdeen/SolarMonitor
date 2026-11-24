plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.futureroots.solarmonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.futureroots.solarmonitor"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // تصغير الحجم (R8)
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // للتجربة فقط: توقيع debug. للتوزيع الفعلي أنشئ keystore خاصاً بك وضعه هنا.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // ماسح QR من خدمات Google Play (بلا كاميرا مدمجة في التطبيق → حجم أصغر). اختياري: الصق الرمز يدوياً إن لم تتوفر الخدمات.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
