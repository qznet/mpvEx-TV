#!/bin/bash
set -e

# 配置路径
PROJECT_ROOT=$(pwd)
AAR_PATH="$PROJECT_ROOT/app/libs/mpv-android-lib-v0.0.1.aar"
BUILD_DIR="$PROJECT_ROOT/buildscripts"
TEMP_DIR="$PROJECT_ROOT/temp_aar_build"
ARCHS=("arm64" "armv7l" "x86" "x86_64")

# 设置环境变量
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="/Users/peilinxiong/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "🚀 开始自动更新 MPV AAR 核心..."

# 1. 确保构建脚本有执行权限
chmod +x $BUILD_DIR/*.sh $BUILD_DIR/scripts/*.sh

# 2. 下载/更新源码依赖
echo "📥 正在检查/下载最新的源码依赖 (FFmpeg, mpv, etc.)..."
cd $BUILD_DIR
./download.sh

# 3. 循环编译各架构的核心库
for ARCH in "${ARCHS[@]}"; do
    echo "🛠️ 正在编译架构: $ARCH ..."
    ./buildall.sh --arch $ARCH mpv
done

# 4. 准备解压并更新 AAR
echo "📦 正在注入新的 .so 文件到 AAR..."
rm -rf $TEMP_DIR
mkdir -p $TEMP_DIR
unzip -q $AAR_PATH -d $TEMP_DIR

# 映射架构名称（buildscripts 的名称 vs Android 规范名称）
function get_android_arch() {
    case $1 in
        "arm64") echo "arm64-v8a" ;;
        "armv7l") echo "armeabi-v7a" ;;
        "x86") echo "x86" ;;
        "x86_64") echo "x86_64" ;;
    esac
}

# 5. 替换 .so 文件
for ARCH in "${ARCHS[@]}"; do
    ANDROID_ARCH=$(get_android_arch $ARCH)
    PREFIX_DIR="$BUILD_DIR/prefix/$ARCH/lib"
    TARGET_JNI_DIR="$TEMP_DIR/jni/$ANDROID_ARCH"

    if [ -d "$PREFIX_DIR" ]; then
        echo "📂 正在更新 $ANDROID_ARCH 的库文件..."
        mkdir -p $TARGET_JNI_DIR
        # 复制主库和 FFmpeg 库
        cp $PREFIX_DIR/libmpv.so $TARGET_JNI_DIR/
        cp $PREFIX_DIR/libavcodec.so $TARGET_JNI_DIR/ 2>/dev/null || true
        cp $PREFIX_DIR/libavdevice.so $TARGET_JNI_DIR/ 2>/dev/null || true
        cp $PREFIX_DIR/libavfilter.so $TARGET_JNI_DIR/ 2>/dev/null || true
        cp $PREFIX_DIR/libavformat.so $TARGET_JNI_DIR/ 2>/dev/null || true
        cp $PREFIX_DIR/libavutil.so $TARGET_JNI_DIR/ 2>/dev/null || true
        cp $PREFIX_DIR/libswresample.so $TARGET_JNI_DIR/ 2>/dev/null || true
        cp $PREFIX_DIR/libswscale.so $TARGET_JNI_DIR/ 2>/dev/null || true
    else
        echo "⚠️ 警告: 未找到 $ARCH 的编译产物，跳过。"
    fi
done

# 6. 重新打包 AAR
echo "📝 正在重新封装 AAR..."
NEW_AAR="$PROJECT_ROOT/app/libs/mpv-android-lib-updated.aar"
cd $TEMP_DIR
zip -qr $NEW_AAR .

# 7. 替换原文件并清理
echo "✅ 更新完成！"
mv $NEW_AAR $AAR_PATH
rm -rf $TEMP_DIR

echo "✨ 现在你可以直接运行 './gradlew assembleDebug' 来构建包含最新核心的 APK 了。"
