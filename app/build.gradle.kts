import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.android)
}


configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.12.0")
        force("androidx.core:core-ktx:1.12.0")
        force("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.0")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
        force("androidx.room:room-common:2.6.1")
        force("com.squareup.okio:okio:3.6.0")
    }
}
android {
    namespace = "ru.queuejw.lumetro"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    ndkVersion = "27.0.12077973"

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "ru.queuejw.lumetro"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storeType = "PKCS12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // 方案 B：CI 提供固定 keystore（KEYSTORE_FILE）时用固定签名，否则沿用默认 debug 签名
            if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/ocrModelAssets"))
        }
    }

    androidResources {
        noCompress += "xz"
    }

    packaging {
        jniLibs.keepDebugSymbols.add("**/libc++_shared.so")
    }
}

val compressOcrModels by tasks.registering(Exec::class) {
    val sourceDir = layout.projectDirectory.dir("src/main/modelAssets/models")
    val outputDir = layout.buildDirectory.dir("generated/ocrModelAssets/models")
    val archiveFile = outputDir.map { it.file("ocr_models.tar.xz") }
    val versionFile = outputDir.map { it.file("ocr_models.version") }

    inputs.dir(sourceDir)
    inputs.file(rootProject.layout.projectDirectory.file("tools/compress_ocr_models.py"))
    outputs.file(archiveFile)
    outputs.file(versionFile)

    val defaultPythonExecutable = providers.systemProperty("os.name").map { name ->
        if (name.startsWith("Windows", ignoreCase = true)) "python" else "python3"
    }

    commandLine(
        providers.gradleProperty("pythonExecutable").orElse(defaultPythonExecutable).get(),
        rootProject.layout.projectDirectory.file("tools/compress_ocr_models.py").asFile.absolutePath,
        "--source",
        sourceDir.asFile.absolutePath,
        "--output",
        archiveFile.get().asFile.absolutePath,
        "--version-output",
        versionFile.get().asFile.absolutePath,
    )
}

tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
        it.name.contains("lint", ignoreCase = true)
}.configureEach {
    dependsOn(compressOcrModels)
}

dependencies {
    implementation("com.belerweb:pinyin4j:2.5.1")
    implementation("org.tukaani:xz:1.12")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core:1.12.0")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.collection)

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core.jvm)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.recyclerview)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.material)

    implementation(libs.androidx.constraintlayout)

    implementation(libs.skydoves.colorpickerview)
    implementation(libs.coil)

}
