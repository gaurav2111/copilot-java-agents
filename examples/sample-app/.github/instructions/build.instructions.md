---
applyTo: "**/*.gradle,**/*.gradle.kts,**/gradle.properties,**/libs.versions.toml,**/settings.gradle*,**/pom.xml,**/rewrite.yml"
description: "Rules for editing build files"
---
# Build file rules

- Match the DSL already in use: Groovy (`build.gradle`, `settings.gradle`) or Kotlin (`*.gradle.kts`). Never convert a file from one to the other as part of another task; mirror the surrounding syntax (`runtimeOnly 'g:a:v'` vs `runtimeOnly("g:a:v")`, `options.release = 21` vs `options.release.set(21)`).
- Versions live in one place: `gradle/libs.versions.toml` where a catalog exists; in older Groovy builds, the root `ext { }` block or `gradle.properties` (Maven: parent pom `<properties>` / `<dependencyManagement>`). Never hard-code a version in a subproject that the catalog, `ext`, or platform manages.
- Prefer upgrading the platform / BOM (`implementation(platform(libs.spring.boot.bom))`, Spring Boot plugin version) over pinning individual transitives. For a transitive, prefer `dependencies { constraints { implementation("g:a:v") { because("CVE-...") } } }` (Maven: `dependencyManagement`) over `exclude`. Use `exclude` only when the artifact must not be on the classpath at all. Never use `resolutionStrategy.force` for a CVE fix; it hides conflicts.
- Every version change gets a one-line comment (TOML `#`, Gradle `//`, Maven `<!-- -->`) with the reason and ticket or CVE id, e.g. `# CVE-2022-1471: snakeyaml 1.30 -> 2.0`, or a `because("...")` on a constraint.
- Never add a repository (`repositories { }`, `pluginManagement.repositories`, Maven `<repository>`); the corporate mirror is configured in `~/.gradle/init.d` / `settings.xml`.
- Do not change the Java toolchain (`java.toolchain.languageVersion`, `sourceCompatibility`, `maven.compiler.release`), the Gradle wrapper version, plugin versions, or test task configuration unless the task is an upgrade and the plan listed it.
- Scope correctly: `implementation` by default, `api` only for types exposed in a library's public API, `testImplementation` for tests, `annotationProcessor` (and `testAnnotationProcessor`) for processors, `runtimeOnly` for drivers. Maven: `<scope>test</scope>`, `annotationProcessorPaths`.
- After any build-file change run `scripts/compile-errors.sh` for the affected module and `scripts/dep-path.sh` for the artifact you touched, and paste both summary lines in your report.
