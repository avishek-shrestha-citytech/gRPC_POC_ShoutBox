plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)

    id("com.google.protobuf")
}

android {
    namespace = "com.example.grpc_poc_shoutbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.grpc_poc_shoutbox"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // RxJava 3
    implementation("io.reactivex.rxjava3:rxjava:3.1.8")

    // RxAndroid (AndroidSchedulers.mainThread())
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.1")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    
    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.5")

    // gRPC dependencies
    implementation("io.grpc:grpc-okhttp:1.63.0")// Transport for Android (uses OkHttp)
    implementation("io.grpc:grpc-stub:1.63.0")// For calling RPC methods
    implementation("io.grpc:grpc-protobuf-lite:1.63.0")// Protobuf support (lite = smaller + Android-optimized)
    implementation("javax.annotation:javax.annotation-api:1.3.2")// Needed for generated code (like @Generated)
}

// gRPC code generation block
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        register("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.63.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                register("grpc") {
                    option("lite")
                }
            }
            task.builtins {
                register("java") {
                    option("lite")
                }
            }
        }
    }
}