plugins {
	alias(libs.plugins.kotlin.jvm)
}

kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
	implementation(project(":core"))
	implementation(project(":identity-api"))
	implementation(libs.json)
	testImplementation(libs.junit)
}
