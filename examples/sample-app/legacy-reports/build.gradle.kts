plugins { `java-library` }
dependencies {
    implementation(project(":common"))
    testImplementation(libs.junit4)       // migration target: JUnit 4 + Hamcrest -> JUnit 5 + AssertJ
    testImplementation(libs.hamcrest)
}
tasks.test { useJUnit() }
