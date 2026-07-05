cmake_minimum_required(VERSION 3.22.1)

project("usbbootwriter")

# Android 15를 위해 16KB 페이지 사이즈 정렬 준수 링커 플래그 추가
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_STATIC_LINKER_FLAGS "${CMAKE_STATIC_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

set(DEPS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/deps)

include_directories(${DEPS_DIR}/include)

add_library(z STATIC IMPORTED)
set_target_properties(z PROPERTIES IMPORTED_LOCATION ${DEPS_DIR}/lib/libz.a)

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${DEPS_DIR}/lib/libcrypto.a)

add_library(ssl STATIC IMPORTED)
set_target_properties(ssl PROPERTIES IMPORTED_LOCATION ${DEPS_DIR}/lib/libssl.a)

add_library(iconv STATIC IMPORTED)
set_target_properties(iconv PROPERTIES IMPORTED_LOCATION ${DEPS_DIR}/lib/libiconv.a)

add_library(charset STATIC IMPORTED)
set_target_properties(charset PROPERTIES IMPORTED_LOCATION ${DEPS_DIR}/lib/libcharset.a)

add_library(xml2 STATIC IMPORTED)
set_target_properties(xml2 PROPERTIES IMPORTED_LOCATION ${DEPS_DIR}/lib/libxml2.a)

add_library(cdio STATIC IMPORTED)
set_target_properties(cdio PROPERTIES IMPORTED_LOCATION ${DEPS_DIR}/lib/libcdio.a)

add_library(iso9660 STATIC IMPORTED)
set_target_properties(iso9660 PROPERTIES IMPORTED_LOCATION ${DEPS_DIR}/lib/libiso9660.a)

add_library(udf STATIC IMPORTED)
set_target_properties(udf PROPERTIES IMPORTED_LOCATION ${DEPS_DIR}/lib/libudf.a)

add_library(wim STATIC IMPORTED)
set_target_properties(wim PROPERTIES IMPORTED_LOCATION ${DEPS_DIR}/lib/libwim.a)

# JNI 브릿지 라이브러리 정의
add_library(
    usbbootwriter
    SHARED
    native-lib.cpp
)

# 기본 로그 라이브러리 링크
find_library(
    log-lib
    log
)

target_link_libraries(
    usbbootwriter
    wim
    xml2
    cdio
    iso9660
    udf
    ssl
    crypto
    iconv
    charset
    z
    ${log-lib}
)
