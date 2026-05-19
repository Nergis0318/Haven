plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.core.local"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26

        externalNativeBuild {
            cmake {}
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:wayland"))
    implementation(project(":core:security"))
    implementation(project(":core:data"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Pure-Java XZ decoder for tar.xz rootfs tarballs (issue #162 phase 2).
    implementation(libs.xz)

    testImplementation(libs.junit)
}

val buildProot by tasks.registering(Exec::class) {
    val prootScript = rootProject.file("build-proot/build.sh")
    val prootSrc = rootProject.file("build-proot/proot-termux/src")
    val tallocSrc = rootProject.file("build-proot/talloc")
    val jniLibsDir = file("src/main/jniLibs")

    inputs.file(prootScript)
    inputs.dir(prootSrc)
    inputs.dir(tallocSrc)
    outputs.dir(jniLibsDir)

    workingDir = rootProject.file("build-proot")
    commandLine("bash", "build.sh")
    // Let build.sh auto-detect the newest NDK (needs r28+ for the ARM64
    // TLS alignment fix; we ship against r29 stable in CI).
    environment("PROOT_OUTPUT", jniLibsDir.absolutePath)
}

// Build the wayvnc capture-fallback shim (one .so per ABI) and stage
// it under src/main/assets/wayvnc-shim/<abi>/ so the APK packager
// picks it up. The shim is loaded inside the proot rootfs via
// LD_PRELOAD by the nested-Wayland launch script — see
// DesktopManager.launchNestedWayland for the call site, and
// wayland-android/wayvnc-shim/libhaven_wayvnc_shim.c for the why.
//
// Required toolchains: gcc-aarch64-linux-gnu and gcc-x86-64-linux-gnu
// (standard Debian/Ubuntu packages — F-Droid's buildserver has them).
val buildWayvncShim by tasks.registering(Exec::class) {
    val script = rootProject.file("wayland-android/build-wayvnc-shim.sh")
    val src = rootProject.file("wayland-android/wayvnc-shim/libhaven_wayvnc_shim.c")
    val assetsDir = file("src/main/assets/wayvnc-shim")

    inputs.file(script)
    inputs.file(src)
    outputs.dir(assetsDir)

    workingDir = rootProject.file("wayland-android")
    commandLine("bash", "build-wayvnc-shim.sh")
}

tasks.named("preBuild") {
    dependsOn(buildProot, buildWayvncShim)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
