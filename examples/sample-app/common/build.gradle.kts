plugins { `java-library` }
dependencies {
    api(libs.commons.text)
    testImplementation(platform("org.junit:junit-bom:5.9.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.24.2")
}
tasks.test { useJUnitPlatform() }
