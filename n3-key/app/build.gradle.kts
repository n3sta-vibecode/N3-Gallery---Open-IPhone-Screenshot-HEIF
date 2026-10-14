plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.n3vibecode.key"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.n3vibecode.key"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Installable, optimized local test build; not a production signing identity.
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8"; freeCompilerArgs += "-Xlambdas=class" }
}
