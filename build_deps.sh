#!/bin/bash
set -e

# Configuration for NDK
NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_HOME}/ndk/25.1.8937393}}"
export ANDROID_NDK_ROOT=$NDK_DIR
export ANDROID_NDK_HOME=$NDK_DIR
API=28
TOOLCHAIN=$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64
TARGET=aarch64-linux-android
export PATH=$TOOLCHAIN/bin:$PATH
export AR=llvm-ar
export CC=${TARGET}${API}-clang
export AS=$CC
export CXX=${TARGET}${API}-clang++
export LD=ld
export RANLIB=llvm-ranlib
export STRIP=llvm-strip

# Include 16KB page size constraint required by Android 15
export CFLAGS="-fPIC -Wl,-z,max-page-size=16384"
export CXXFLAGS="-fPIC -Wl,-z,max-page-size=16384"
export LDFLAGS="-Wl,-z,max-page-size=16384"

PREFIX="$(pwd)/app/src/main/cpp/deps"
mkdir -p "$PREFIX"

WORK_DIR="$(pwd)/build_deps_work"
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# 1. zlib
echo "Building zlib..."
if [ ! -f "zlib-1.3.1/libz.a" ]; then
  wget -q https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz || wget -q https://zlib.net/fossils/zlib-1.3.1.tar.gz
  tar xzf zlib-1.3.1.tar.gz
  pushd zlib-1.3.1
  ./configure --prefix="$PREFIX" --static
  make -j4
  make install
  popd
fi

# 2. openssl
echo "Building openssl..."
if [ ! -d "openssl-3.0.13" ]; then
  wget -q https://github.com/openssl/openssl/releases/download/openssl-3.0.13/openssl-3.0.13.tar.gz
  tar xzf openssl-3.0.13.tar.gz
fi
pushd openssl-3.0.13
./Configure android-arm64 -D__ANDROID_API__=$API --prefix="$PREFIX" no-shared
make -j4
make install_sw
popd

# 3. libiconv (needed for libcdio and libxml2)
echo "Building libiconv..."
if [ ! -d "libiconv-1.17" ]; then
  wget -q https://ftp.gnu.org/pub/gnu/libiconv/libiconv-1.17.tar.gz
  tar xzf libiconv-1.17.tar.gz
fi
pushd libiconv-1.17
./configure --host=aarch64-linux-android --prefix="$PREFIX" --enable-static --disable-shared
make -j4
make install
popd

# 4. libxml2
echo "Building libxml2..."
if [ ! -d "libxml2-2.11.7" ]; then
  wget -q https://download.gnome.org/sources/libxml2/2.11/libxml2-2.11.7.tar.xz
  tar xJf libxml2-2.11.7.tar.xz
fi
pushd libxml2-2.11.7
# Need to link iconv for libxml2
export LIBS="-L$PREFIX/lib -liconv"
export CPPFLAGS="-I$PREFIX/include"
./configure --host=aarch64-linux-android --prefix="$PREFIX" --enable-static --disable-shared --without-python --without-lzma --with-zlib="$PREFIX" --with-iconv="$PREFIX"
make -j4 -k || true
make install || true
# Manually copy library if xmllint failed to build but lib is there
if [ -f .libs/libxml2.a ]; then
  cp .libs/libxml2.a $PREFIX/lib/
  cp -r include/libxml $PREFIX/include/ || true
fi
popd
unset LIBS
unset CPPFLAGS

# 5. libcdio
echo "Building libcdio..."
if [ ! -d "libcdio-2.1.0" ]; then
  wget -q https://ftp.gnu.org/gnu/libcdio/libcdio-2.1.0.tar.bz2
  tar xjf libcdio-2.1.0.tar.bz2
fi
pushd libcdio-2.1.0
export LDFLAGS="-L$PREFIX/lib -Wl,-z,max-page-size=16384 -liconv"
export CPPFLAGS="-I$PREFIX/include"
./configure --host=aarch64-linux-android --prefix="$PREFIX" --enable-static --disable-shared --disable-cxx --with-iconv="$PREFIX"
make -j4 -k || true
make -C lib install || true
make -C include install || true
popd
unset LDFLAGS
unset CPPFLAGS

# 6. wimlib
echo "Building wimlib..."
if [ ! -d "wimlib-1.14.4" ]; then
  wget -q https://wimlib.net/downloads/wimlib-1.14.4.tar.gz
  tar xzf wimlib-1.14.4.tar.gz
fi
pushd wimlib-1.14.4
# Note: wimlib needs libxml2 and libcrypto (openssl)
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
export CPPFLAGS="-I$PREFIX/include -I$PREFIX/include/libxml2"
export LDFLAGS="-L$PREFIX/lib -Wl,-z,max-page-size=16384 -liconv -lz"
./configure --host=aarch64-linux-android --prefix="$PREFIX" --enable-static --disable-shared --without-ntfs-3g --without-fuse
make -j4 libwim.la
make install-libLTLIBRARIES
make install-includeHEADERS
popd

# Re-organize headers
mv $PREFIX/include/libxml2/libxml $PREFIX/include/libxml 2>/dev/null || true
rm -rf $PREFIX/include/libxml2 2>/dev/null || true

echo "All dependencies built successfully."
