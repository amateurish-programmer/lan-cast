plugins { id("com.android.library"); kotlin("android") }
android {
 namespace = "dev.lancast.updater"
 compileSdk = 35
 buildToolsVersion = "35.0.0"
 defaultConfig { minSdk = 26 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20240303"); implementation(project(":shared")); implementation("androidx.core:core:1.15.0") }
