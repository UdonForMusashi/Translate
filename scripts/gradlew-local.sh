#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLING_DIR="${PROJECT_DIR}/.tooling"

export JAVA_HOME="${TOOLING_DIR}/jdk-17"
export ANDROID_HOME="${TOOLING_DIR}/android-sdk"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
export GRADLE_USER_HOME="${TOOLING_DIR}/gradle-home"
export PATH="${JAVA_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

if [[ ! -x "${JAVA_HOME}/bin/java" || ! -x "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" ]]; then
    echo "Toolchain local ausente. Execute ./scripts/bootstrap-toolchain.sh" >&2
    exit 1
fi

if [[ -x "${TOOLING_DIR}/gradle-9.4.1/bin/gradle" ]]; then
    exec "${TOOLING_DIR}/gradle-9.4.1/bin/gradle" --project-dir "${PROJECT_DIR}" "$@"
fi

exec "${PROJECT_DIR}/gradlew" --project-dir "${PROJECT_DIR}" "$@"
