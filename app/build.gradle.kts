plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "com.rustedwax.app"
	compileSdk = 35

	defaultConfig {
		applicationId = "com.rustedwax.app"
		minSdk = 26
		targetSdk = 35
		versionCode = 58
		versionName = rootProject.version.toString()

		// The instrumented suite is the only coverage of the real Android
		// callback boundary — `<redacted-private-path>` §8 records its absence as a
		// reason regressions survive a green JVM run.
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	buildTypes {
		/**
		 * A second, throwaway installation for testing fresh-install behaviour.
		 *
		 * Fresh-install defaults can only be observed by an install that has
		 * never stored anything, and clearing the field device's data would
		 * take the posting key in `EncryptedSharedPreferences` with it. Its own
		 * `applicationId` gets its own preferences file instead, so the defaults
		 * are observable on the real device and the real install is untouched.
		 * Uninstall it afterwards; it holds no key and grants nothing.
		 */
		create("freshtest") {
			initWith(getByName("debug"))
			applicationIdSuffix = ".freshtest"
			matchingFallbacks += listOf("debug")
		}

		release {
			isMinifyEnabled = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	kotlinOptions {
		jvmTarget = "17"
	}

	testOptions {
		unitTests {
			// `EventLog.append` calls `android.util.Log.d`, which throws on the JVM
			// unless stubbed. Without this the only way to observe the log from a
			// unit test is to leave it switched off, which makes "a shadow run
			// writes nothing to the log" untestable — the assertion would be about
			// a log nobody had turned on.
			isReturnDefaultValues = true
		}
	}

	buildFeatures {
		compose = true
		// Off by default in AGP 8. Needed so the MusicBrainz User-Agent can
		// report the real version instead of a hardcoded one that drifts.
		buildConfig = true
	}
}

dependencies {
	implementation(project(":core"))
	implementation(project(":identity-api"))
	implementation(project(":youtube-identity"))
	implementation(project(":android-sources"))
	implementation(project(":hive"))

	implementation(libs.androidx.core.ktx)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.bouncycastle)
	implementation(libs.androidx.security.crypto)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.runtime.compose)
	implementation(libs.androidx.activity.compose)

	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.compose.ui)
	implementation(libs.androidx.compose.ui.graphics)
	implementation(libs.androidx.compose.ui.tooling.preview)
	implementation(libs.androidx.compose.material3)
	// Explicit rather than transitive: `HorizontalPager` is foundation's, and a
	// screen whose navigation depends on it should not depend on material3
	// happening to bring it along.
	implementation(libs.androidx.compose.foundation)

	debugImplementation(libs.androidx.compose.ui.tooling)

	// The hive/ package is pure JVM by design so it can be verified against
	// dhive-generated golden vectors without a device. org.json ships with
	// Android at runtime but must be added explicitly for JVM unit tests.
	testImplementation(libs.junit)
	testImplementation(libs.json)
	testImplementation(libs.bouncycastle)

	// Runner and JUnit4 bridge only. Everything the instrumented suite asserts
	// on is the real platform and the real production classes, so nothing here
	// simulates Android and no view/UI-automation library is pulled in.
	androidTestImplementation(libs.junit)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.androidx.test.ext.junit)
}
