# Facts (every line: fact - source - value)

## Build and toolchain
- build tool - settings.gradle.kts, gradlew present - Gradle, Kotlin DSL, 3 projects: :common, :order-service, :legacy-reports
- gradle wrapper - gradle/wrapper/gradle-wrapper.properties - 8.14.2 (supports Java 21; Java 25 would need 9.x)
- java bytecode level - build.gradle.kts:16 (`options.release.set(17)` in `subprojects {}`) - 17, set ONCE for all three projects
- java toolchain block - build.gradle.kts - none (compiles with the installed JDK; no auto-provisioning)
- installed JDK - `java -version` via scripts/jdk-check.sh - OpenJDK 21.0.12
- version catalog - gradle/libs.versions.toml - spring-boot 2.7.18, commons-text 1.9, guava 30.1-jre, junit4 4.13.2, hamcrest 2.2, jacoco 0.8.12
- jacoco XML report - build.gradle.kts:19-21 - enabled

## Target project :order-service
- plugins - order-service/build.gradle.kts:2-3 - org.springframework.boot 2.7.18, io.spring.dependency-management 1.1.6
- dependencies - order-service/build.gradle.kts:6-9 - project(:common), spring-boot-starter-web, guava; test: spring-boot-starter-test
- test framework - order-service/build.gradle.kts:11, OrderServiceTest.java imports - JUnit 5 + AssertJ; Mockito on classpath but unused
- javax usage - scripts/jdk-check.sh trouble spots - `javax.annotation.` in 1 file (OrderService.java:10, @PostConstruct)
- jdk-internal API usage - scripts/jdk-check.sh (jdeps) - none
- deprecated-for-removal against 21 - scripts/jdk-check.sh (jdeprscan) - none

## Baseline
- compile - `scripts/compile-errors.sh order-service` - COMPILE OK [gradle]
- tests - `scripts/test-summary.sh order-service` - total=2 failures=0 errors=0 skipped=0
- dependency surface - `scripts/dep-tree.sh` - 120 lines across 3 projects
- git - `git status` - NOT a git repository (cannot verify clean tree or branch)

## Known compatibility facts (from the dependency tree, not assumptions)
- Spring Boot 2.7.18 manages ByteBuddy 1.12.23 and Mockito 4.5.1 - build/deptree.txt - Mockito mock creation on JDK 21 requires ByteBuddy >= 1.14.9; currently no test uses Mockito, so tests pass
- Spring Boot 2.7.x OSS support ended 2023-11 - spring.io support page (not read in this session) - UNVERIFIED HERE, listed as a G1 question rather than a fact
