plugins {
	alias(libs.plugins.kotlin.jvm)
}

kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

val generatedVersionDir = layout.buildDirectory.dir("generated/sources/version/kotlin")
val generateVersionSource by tasks.registering {
	val outputDir = generatedVersionDir
	// Read once, here, at *configuration* time, and written into the file from
	// this local rather than from `project`.
	//
	// `doLast` runs at execution time, where touching `project` is unsupported
	// under the configuration cache — which this build turns on in
	// `gradle.properties`. Gradle reports it ("invocation of 'Task.project' at
	// execution time is unsupported") and carries on, and what the generated
	// file then received was Gradle's default version, `unspecified`. That is
	// the string every Hive payload carries: `HiveScrobblePayload.APP_NAME` is
	// `"rustedwax/$BUILD_VERSION"`, so scrobbles and Snap metadata went on chain
	// claiming to come from `rustedwax/unspecified`, permanently and publicly.
	//
	// Worse than simply wrong, it was *nondeterministic*: a build that bypassed
	// the configuration cache had a real `Project` at execution time and emitted
	// `0.11.4`, so the same commit produced two different on-chain identities
	// depending on how it happened to be built.
	//
	// The version itself is unchanged — `project.version` is still the one
	// authority, set by `allprojects` in the root build script. Only *when* it
	// is read has moved.
	val applicationVersion = project.version.toString()
	inputs.property("applicationVersion", applicationVersion)
	outputs.dir(outputDir)
	doLast {
		val packageDir = outputDir.get().dir("com/rustedwax/hive").asFile
		packageDir.mkdirs()
		packageDir.resolve("BuildVersion.kt").writeText(
			"package com.rustedwax.hive\n\ninternal const val BUILD_VERSION = \"$applicationVersion\"\n",
		)
	}
}

kotlin.sourceSets.main {
	kotlin.srcDir(generatedVersionDir)
}
tasks.named("compileKotlin") {
	dependsOn(generateVersionSource)
}

dependencies {
	implementation(project(":core"))
	implementation(libs.bouncycastle)
	implementation(libs.json)
	testImplementation(libs.junit)
}
