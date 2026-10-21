plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")
    group = "com.acme"
    version = "1.0.0"
    repositories { mavenCentral() }

    // Java 21 bytecode compiled with the installed JDK (no toolchain block, so no JDK downloads).
    tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

    extensions.configure<JacocoPluginExtension> { toolVersion = rootProject.libs.versions.jacoco.get() }
    tasks.withType<Test>().configureEach { finalizedBy("jacocoTestReport") }
    tasks.withType<JacocoReport>().configureEach {
        reports { xml.required.set(true); html.required.set(false) }   // CoverageGaps.java needs the XML
    }
}
