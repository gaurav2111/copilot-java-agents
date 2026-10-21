plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}
dependencies {
    implementation(project(":common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.guava)
    testImplementation(libs.spring.boot.starter.test)   // JUnit 5, AssertJ, Mockito (Mockito not used: ByteBuddy 1.12 vs JDK 21 is a classic upgrade residue)
}
tasks.test { useJUnitPlatform() }
