#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLING_DIR="${PROJECT_DIR}/.tooling"
DOWNLOAD_DIR="${TOOLING_DIR}/downloads"
JDK_DIR="${TOOLING_DIR}/jdk-17"
GRADLE_DIR="${TOOLING_DIR}/gradle-9.4.1"
SDK_DIR="${TOOLING_DIR}/android-sdk"

JDK_ARCHIVE="OpenJDK17U-jdk_x64_linux_hotspot_17.0.18_8.tar.gz"
JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.18%2B8/${JDK_ARCHIVE}"
GRADLE_ARCHIVE="gradle-9.4.1-bin.zip"
GRADLE_URL="https://services.gradle.org/distributions/${GRADLE_ARCHIVE}"
CLI_ARCHIVE="commandlinetools-linux-15859902_latest.zip"
CLI_URL="https://dl.google.com/android/repository/${CLI_ARCHIVE}"
CLI_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"

if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
    echo "Este bootstrap está fixado para Linux x86_64." >&2
    exit 1
fi

for required_command in curl sha256sum tar unzip; do
    if ! command -v "${required_command}" >/dev/null 2>&1; then
        echo "Comando necessário ausente: ${required_command}" >&2
        exit 1
    fi
done

mkdir -p "${DOWNLOAD_DIR}" "${SDK_DIR}"

download() {
    local url="$1"
    local destination="$2"
    if [[ ! -f "${destination}" ]]; then
        curl --fail --location --retry 3 --output "${destination}.partial" "${url}"
        mv "${destination}.partial" "${destination}"
    fi
}

verify_sha256() {
    local archive="$1"
    local expected="$2"
    local actual
    actual="$(sha256sum "${archive}" | awk '{print $1}')"
    if [[ "${actual}" != "${expected}" ]]; then
        echo "SHA-256 inválido para ${archive}" >&2
        exit 1
    fi
}

download_with_official_sidecar() {
    local url="$1"
    local destination="$2"
    local checksum_file="${destination}.sha256.txt"
    download "${url}.sha256" "${checksum_file}"
    if [[ ! -s "${checksum_file}" ]]; then
        download "${url}.sha256.txt" "${checksum_file}"
    fi
    local expected
    expected="$(awk 'NR == 1 {print $1}' "${checksum_file}")"
    if [[ ! "${expected}" =~ ^[0-9a-fA-F]{64}$ ]]; then
        echo "Checksum oficial inválido para ${url}" >&2
        exit 1
    fi
    download "${url}" "${destination}"
    verify_sha256 "${destination}" "${expected,,}"
}

if [[ ! -x "${JDK_DIR}/bin/java" ]]; then
    JDK_DOWNLOAD="${DOWNLOAD_DIR}/${JDK_ARCHIVE}"
    if [[ ! -f "${JDK_DOWNLOAD}.sha256.txt" ]]; then
        download "${JDK_URL}.sha256.txt" "${JDK_DOWNLOAD}.sha256.txt"
    fi
    JDK_SHA256="$(awk 'NR == 1 {print $1}' "${JDK_DOWNLOAD}.sha256.txt")"
    if [[ ! "${JDK_SHA256}" =~ ^[0-9a-fA-F]{64}$ ]]; then
        echo "Checksum oficial inválido para o JDK" >&2
        exit 1
    fi
    download "${JDK_URL}" "${JDK_DOWNLOAD}"
    verify_sha256 "${JDK_DOWNLOAD}" "${JDK_SHA256,,}"
    mkdir -p "${JDK_DIR}"
    tar -xzf "${JDK_DOWNLOAD}" --strip-components=1 -C "${JDK_DIR}"
fi

export JAVA_HOME="${JDK_DIR}"
export PATH="${JAVA_HOME}/bin:${PATH}"

if [[ ! -x "${GRADLE_DIR}/bin/gradle" ]]; then
    GRADLE_DOWNLOAD="${DOWNLOAD_DIR}/${GRADLE_ARCHIVE}"
    download_with_official_sidecar "${GRADLE_URL}" "${GRADLE_DOWNLOAD}"
    unzip -q "${GRADLE_DOWNLOAD}" -d "${TOOLING_DIR}"
fi

if [[ ! -x "${SDK_DIR}/cmdline-tools/latest/bin/sdkmanager" ]]; then
    CLI_DOWNLOAD="${DOWNLOAD_DIR}/${CLI_ARCHIVE}"
    download "${CLI_URL}" "${CLI_DOWNLOAD}"
    verify_sha256 "${CLI_DOWNLOAD}" "${CLI_SHA256}"
    CLI_EXTRACT_DIR="${TOOLING_DIR}/cmdline-tools-extract"
    mkdir -p "${CLI_EXTRACT_DIR}" "${SDK_DIR}/cmdline-tools"
    unzip -q "${CLI_DOWNLOAD}" -d "${CLI_EXTRACT_DIR}"
    mv "${CLI_EXTRACT_DIR}/cmdline-tools" "${SDK_DIR}/cmdline-tools/latest"
fi

export ANDROID_HOME="${SDK_DIR}"
export ANDROID_SDK_ROOT="${SDK_DIR}"
export GRADLE_USER_HOME="${TOOLING_DIR}/gradle-home"
export PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

set +o pipefail
yes | sdkmanager --licenses >/dev/null
set -o pipefail
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"

if [[ ! -f "${PROJECT_DIR}/gradlew" ]]; then
    "${GRADLE_DIR}/bin/gradle" -p "${PROJECT_DIR}" wrapper \
        --gradle-version 9.4.1 \
        --distribution-type bin
fi

echo "Toolchain local pronta em ${TOOLING_DIR}"
echo "Use: ./scripts/gradlew-local.sh test lint assembleDebug"
