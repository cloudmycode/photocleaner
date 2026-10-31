import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
val privacyUrl = providers.gradleProperty("privacyUrl").getOrElse("")
val supportEmail = providers.gradleProperty("supportEmail").getOrElse("")
val publisherName = providers.gradleProperty("publisherName").getOrElse("")
val admobAppId = providers.gradleProperty("admobAppId").getOrElse("")
val admobInterstitialId = providers.gradleProperty("admobInterstitialId").getOrElse("")
val adsConfigured = admobAppId.isNotBlank() && admobInterstitialId.isNotBlank()
val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testInterstitialId = "ca-app-pub-3940256099942544/1033173712"
val isolatedDeviceTest = providers.gradleProperty("isolatedDeviceTest").getOrElse("false").toBoolean()
// Machine-local credentials; this file is excluded from version control.
val releaseSigning = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val signingConfigured = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !releaseSigning.getProperty(it).isNullOrBlank() }
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Requires the configured publisher certificate for release builds."
    doLast {
        check(signingConfigured) { "Configure android/signing.properties before building a release. See signing.properties.example." }
        check(rootProject.file(releaseSigning.getProperty("storeFile")).isFile) { "The release signing certificate could not be found." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseSigning) }
tasks.register("verifyReleaseMetadata") {
    group = "verification"
    description = "Checks publisher information before creating a store release."
    doLast {
        val uri = runCatching { URI(privacyUrl) }.getOrNull()
        check(uri?.scheme == "https" && !uri.host.isNullOrBlank() && !uri.host.endsWith("example.com")) { "Set -PprivacyUrl to your public HTTPS privacy policy." }
        check(supportEmail.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) && !supportEmail.endsWith("@example.com")) { "Set -PsupportEmail to your monitored privacy/support mailbox." }
        if (admobAppId.isNotBlank() || admobInterstitialId.isNotBlank()) {
            check(admobAppId.matches(Regex("ca-app-pub-[0-9]{16}~[0-9]{10}")) &&
                admobInterstitialId.matches(Regex("ca-app-pub-[0-9]{16}/[0-9]{10}")) &&
                !admobAppId.contains("3940256099942544") && !admobInterstitialId.contains("3940256099942544")) {
                "Provide both production AdMob IDs for an ads-enabled release; sample IDs are debug-only."
            }
        }
        check(publisherName.isNotBlank()) { "Set -PpublisherName to your actual publisher name." }
    }
}
android {
    namespace = "com.maxdevlab.cleaner.photo"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.maxdevlab.cleaner.photo"
        minSdk = 27
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "Photo Cleaner"
        buildConfigField("boolean", "ISOLATED_TEST_BUILD", "false")
        buildConfigField("boolean", "ADS_ENABLED", adsConfigured.toString())
        buildConfigField("boolean", "AD_TEST_MODE", "false")
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", quoted(admobInterstitialId))
        manifestPlaceholders["admobAppId"] = admobAppId.ifBlank { testAdmobAppId }
        buildConfigField("String", "PRIVACY_URL", quoted(privacyUrl))
        buildConfigField("String", "SUPPORT_EMAIL", quoted(supportEmail))
        buildConfigField("String", "PUBLISHER_NAME", quoted(publisherName))
    }
    signingConfigs {
        if (signingConfigured) create("publisher") {
            storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "ADS_ENABLED", "true")
            buildConfigField("boolean", "AD_TEST_MODE", "true")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", quoted(testInterstitialId))
            manifestPlaceholders["admobAppId"] = testAdmobAppId
            buildConfigField("boolean", "ISOLATED_TEST_BUILD", isolatedDeviceTest.toString())
            if (isolatedDeviceTest) {
                applicationIdSuffix = ".devicetest"
                manifestPlaceholders["appLabel"] = "Photo Cleaner Test"
            }
        }
        release {
            signingConfig = signingConfigs.findByName("publisher")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // Non-debug isolated measurements. Keep classes unminified so instrumentation can link them.
    buildTypes.create("performance") {
        initWith(buildTypes.getByName("release"))
        isMinifyEnabled = false
        signingConfig = signingConfigs.getByName("debug")
        applicationIdSuffix = ".devicetest"
        manifestPlaceholders["appLabel"] = "Photo Cleaner Test"
        buildConfigField("boolean", "ISOLATED_TEST_BUILD", "true")
        buildConfigField("boolean", "ADS_ENABLED", "false")
        buildConfigField("boolean", "AD_TEST_MODE", "true")
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", quoted(testInterstitialId))
        manifestPlaceholders["admobAppId"] = testAdmobAppId
        matchingFallbacks += "release"
    }
    testBuildType = providers.gradleProperty("deviceTestBuildType").getOrElse("debug")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because("AdMob's transitive Fragment 1.1 conflicts with Activity Result APIs.")
        }
    }
    implementation("com.google.android.gms:play-services-ads:25.1.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
