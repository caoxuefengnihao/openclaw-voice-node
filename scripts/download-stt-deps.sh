#!/usr/bin/env bash
# download-stt-deps.sh
#
# 一次性脚本:下 STT 集成需要的所有依赖到本地。
# - sherpa-onnx JVM jar → mvn install:install-file 进 /Volumes/ssd/mavenwarehouse
# - sherpa-onnx native lib (按平台) → 同样 mvn install
# - Paraformer-zh INT8 模型 → 放到 models/stt/
#
# 用法:
#   ./scripts/download-stt-deps.sh
#
# 跨平台:自动检测 osx-aarch64 / osx-x64 / linux-x64 / win-x64
# 重跑安全(已有的不重下)
#
# 模型来源:https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models

set -euo pipefail

VERSION="${SHERPA_ONNX_VERSION:-1.13.4}"
RELEASE_TAG="v${VERSION}"
RELEASE_BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/${RELEASE_TAG}"
MODEL_URL="${RELEASE_BASE}/sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS_DIR="${PROJECT_ROOT}/libs"
MODELS_DIR="${PROJECT_ROOT}/models/stt"

# ===== 1. 平台检测 =====
detect_platform() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"
    case "${os}:${arch}" in
        Darwin:arm64)  echo "osx-aarch64" ;;
        Darwin:x86_64) echo "osx-x64"     ;;
        Linux:x86_64)  echo "linux-x64"   ;;
        Linux:aarch64) echo "linux-aarch64" ;;
        Linux:armv7l)  echo "linux-armv7" ;;
        *) echo "❌ 不支持的平台: ${os}:${arch}" >&2; exit 1 ;;
    esac
}

PLATFORM="$(detect_platform)"
echo "📍 检测到平台: ${PLATFORM}"

# ===== 2. 创建目录 =====
mkdir -p "${LIBS_DIR}" "${MODELS_DIR}"

# ===== 3. 下 JVM jar (跨平台通用) =====
JVM_JAR="${LIBS_DIR}/sherpa-onnx-jvm-${VERSION}.jar"
if [[ ! -f "${JVM_JAR}" ]]; then
    echo "⬇️  下 sherpa-onnx-jvm-${VERSION}.jar ..."
    curl -sL "${RELEASE_BASE}/sherpa-onnx-jvm-${VERSION}.jar" -o "${JVM_JAR}"
fi
ls -lh "${JVM_JAR}"

# ===== 4. 下 native lib (按平台) =====
NATIVE_JAR="${LIBS_DIR}/sherpa-onnx-native-lib-${PLATFORM}-${VERSION}.jar"
if [[ ! -f "${NATIVE_JAR}" ]]; then
    echo "⬇️  下 sherpa-onnx-native-lib-${PLATFORM}-${VERSION}.jar ..."
    curl -sL "${RELEASE_BASE}/sherpa-onnx-native-lib-${PLATFORM}-${VERSION}.jar" -o "${NATIVE_JAR}"
fi
ls -lh "${NATIVE_JAR}"

# ===== 5. 装到本地 Maven 仓库 (/Volumes/ssd/mavenwarehouse) =====
MAVEN_REPO="$(mvn help:evaluate -Dexpression=settings.localRepository -q -DforceStdout 2>/dev/null || echo ~/.m2/repository)"
echo "📦 Maven 本地仓库: ${MAVEN_REPO}"

echo ""
echo "🔧 装 sherpa-onnx JVM jar 到 Maven 仓库..."
mvn install:install-file \
    -Dfile="${JVM_JAR}" \
    -DgroupId=com.k2fsa \
    -DartifactId=sherpa-onnx \
    -Dversion="${VERSION}" \
    -Dpackaging=jar \
    -q

echo "🔧 装 sherpa-onnx native lib jar 到 Maven 仓库..."
mvn install:install-file \
    -Dfile="${NATIVE_JAR}" \
    -DgroupId=com.k2fsa \
    -DartifactId=sherpa-onnx-native \
    -Dversion="${VERSION}" \
    -Dclassifier="${PLATFORM}" \
    -Dpackaging=jar \
    -q

# ===== 6. 下 Paraformer-zh 模型 =====
MODEL_DIR="${MODELS_DIR}"
MODEL_INT8="${MODEL_DIR}/model.int8.onnx"
TOKENS="${MODEL_DIR}/tokens.txt"

if [[ -f "${MODEL_INT8}" && -f "${TOKENS}" ]]; then
    echo "✅ 模型已存在,跳过下载"
else
    echo "⬇️  下 Paraformer-zh INT8 模型 (~230MB)..."
    cd "${MODEL_DIR}"
    TARBZ2="${MODEL_DIR}/paraformer.tar.bz2"
    if [[ ! -f "${TARBZ2}" ]]; then
        curl -sL "${MODEL_URL}" -o "${TARBZ2}"
    fi
    tar -xjf "${TARBZ2}" --strip-components=1
    rm -f "${TARBZ2}"
    cd "${PROJECT_ROOT}"
fi

echo ""
echo "✅ STT 依赖就绪"
echo "   - sherpa-onnx JVM:       ${JVM_JAR}"
echo "   - sherpa-onnx native:    ${NATIVE_JAR}"
echo "   - Paraformer-zh model:   ${MODEL_DIR}/"
ls -lh "${MODEL_INT8}" "${TOKENS}" 2>/dev/null