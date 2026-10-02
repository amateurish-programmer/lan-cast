plugins { id("com.android.application"); kotlin("android") }
android {
 namespace = "dev.lancast.phone"
 compileSdk = 35
    buildToolsVersion = "35.0.0"
 defaultConfig { applicationId = "dev.lancast.phone"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2"); implementation(project(":shared")); implementation("org.nanohttpd:nanohttpd:2.3.1") }
