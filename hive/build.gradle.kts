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
	inputs.property("applicationVersion", project.version.toString())
	outputs.dir(outputDir)
	doLast {
		val packageDir = outputDir.get().dir("com/rustedwax/hive").asFile
		packageDir.mkdirs()
		packageDir.resolve("BuildVersion.kt").writeText(
			"package com.rustedwax.hive\n\ninternal const val BUILD_VERSION = \"${project.version}\"\n",
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
