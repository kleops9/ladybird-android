import java.util.Properties

plugins {
    id("com.android.application") version "8.11.0"
    id("org.jetbrains.kotlin.android") version "2.1.20"
}

// With -PladybirdNativeBuildDir=<CMake build directory>, Gradle packages a native build made beforehand (with
// UI/Android/build-native.sh) instead of running CMake itself, which saves building everything a second time.
val prebuiltNativeBuildDir = providers.gradleProperty("ladybirdNativeBuildDir").orNull

// The version is in version.properties, which release.sh sets. Android orders the versions of an app by their version
// code, which major.minor.patch gives as major * 1000000 + minor * 1000 + patch.
val appVersion: String = Properties().apply { file("version.properties").inputStream().use { load(it) } }.getProperty("version")
val appVersionCode: Int = appVersion.split('.').let { parts ->
    require(parts.size == 3 && parts.all { it.toIntOrNull() != null }) { "version.properties: '$appVersion' isn't major.minor.patch" }
    parts[0].toInt() * 1_000_000 + parts[1].toInt() * 1_000 + parts[2].toInt()
}

// Release APKs are signed with the key in the keystore that LADYBIRD_KEYSTORE names, by LADYBIRD_KEY_ALIAS, with the
// passwords in LADYBIRD_KEYSTORE_PASSWORD and LADYBIRD_KEY_PASSWORD. Without it, they're left unsigned.
val releaseKeystore: String? = System.getenv("LADYBIRD_KEYSTORE")

android {
    namespace = "org.serenityos.ladybird"
    compileSdk = 35
    // FIXME: Replace the NDK version to a stable one (this is r29 beta 2)
    ndkVersion = "29.0.13599879"

    defaultConfig {
        applicationId = "org.serenityos.ladybird"
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (prebuiltNativeBuildDir == null) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++23"
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DLADYBIRD_VCPKG_TYPE=release",
                        "-DVCPKG_TARGET_ANDROID=ON",
                        "-DBUILD_SHARED_LIBS=ON",
                        "-DCMAKE_SKIP_INSTALL_RULES=ON"
                    )
                }
            }
        }
        ndk {
            // Specifies the ABI configurations of your native
            // libraries Gradle should build and package with your app.
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
    }

    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("LADYBIRD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LADYBIRD_KEY_ALIAS")
                keyPassword = System.getenv("LADYBIRD_KEY_PASSWORD") ?: System.getenv("LADYBIRD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-dev"
            if (prebuiltNativeBuildDir == null) externalNativeBuild {
                cmake {
                    // A CMake Debug build compiles with -ggdb3, which makes each LibWeb object ~12 MB and every
                    // binary that links LibWeb several GB. Line tables (-g1) still give symbolized backtraces.
                    arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo"
                    // The Lagom tools are exported without a configuration (NOCONFIG), which vcpkg's default
                    // RelWithDebInfo mapping does not fall back to.
                    arguments += "-DCMAKE_MAP_IMPORTED_CONFIG_RELWITHDEBINFO=RelWithDebInfo;Release;None;NOCONFIG;"
                }
            }
        }
        release {
            signingConfig = signingConfigs.findByName("release")
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
    if (prebuiltNativeBuildDir == null) {
        externalNativeBuild {
            cmake {
                path = file("../../CMakeLists.txt")
                version = "3.23.0+"
            }
        }
    }

    // The helper processes are executables, which Android only lets an app run from its native library directory.
    // CMake builds them as lib<Name>.so into build/helpers, and they're installed (extracted) like any library.
    // A prebuilt native build's libraries are gathered into build/prebuilt-jni by build-native.sh.
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("build/helpers")
            if (prebuiltNativeBuildDir != null)
                jniLibs.srcDir("build/prebuilt-jni")
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        prefab = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
