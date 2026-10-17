#!/usr/bin/env bash
# Shared build-tool detection. Source this; do not run it.
#   source "$(dirname "$0")/lib.sh"
# Sets: BUILD (gradle|maven), BW (wrapper command), OFFLINE flag, and helpers gradle_path / maven_pl.
# Gradle is the default when both are present. Override with BUILD_TOOL=maven|gradle.
detect_build_tool() {
  if [ -n "${BUILD_TOOL:-}" ]; then BUILD="$BUILD_TOOL"
  elif [ -f settings.gradle ] || [ -f settings.gradle.kts ] || [ -f gradlew ]; then BUILD=gradle
  elif [ -f pom.xml ]; then BUILD=maven
  else echo "No settings.gradle(.kts), gradlew or pom.xml here. Run scripts from the repository root." >&2; exit 2; fi

  if [ "$BUILD" = gradle ]; then
    if [ -x ./gradlew ]; then BW=./gradlew; else BW=gradle; fi
    OFFLINE="${BUILD_OFFLINE:---offline}"          # set BUILD_OFFLINE="" to allow downloads
    BUILD_DIR=build
  else
    if [ -x ./mvnw ]; then BW=./mvnw; else BW=mvn; fi
    OFFLINE="${BUILD_OFFLINE:--o}"
    BUILD_DIR=target
  fi
  export BUILD BW OFFLINE BUILD_DIR
}

# ":order-service" or "order-service" or "services/order" -> ":order-service" / ":services:order"
gradle_path() {
  local m="${1:-}"; [ -z "$m" ] && { echo ""; return; }
  m="${m#:}"; m="${m//\//:}"; echo ":$m"
}
# Gradle project path -> directory ("services/order")
gradle_dir() { local m; m="$(gradle_path "$1")"; m="${m#:}"; echo "${m//://}"; }

# Maven "-pl module -am" or empty
maven_pl() { local m="${1:-}"; [ -n "$m" ] && echo "-pl $m -am" || echo ""; }

# List Gradle subproject paths (":a", ":a:b"); root excluded. Cached per run.
gradle_projects() {
  $BW -q projects 2>/dev/null | grep -oE "Project '(:[^']+)'" | sed -E "s/Project '(.*)'/\1/" | sort -u
}
