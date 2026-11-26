# NB: This is vcpkg's own libpsl port at a newer version than vcpkg has.
vcpkg_from_github(
    OUT_SOURCE_PATH SOURCE_PATH
    REPO rockdaboot/libpsl
    REF "${VERSION}"
    SHA512 "b9e360fd7eb5f219594de33b705090a68fdf3568b2e017cf6df2d15774bf5165c0976a441648646a5b9a10cb94e292b9bf14e9d0abc1df61697d6d1a9e901ca5"
    HEAD_REF master
)

set(list_ref bfd12e081959c7ae143c05eb99c95513686f90f4)
string(SUBSTRING "${list_ref}" 0 6 short_hash)
vcpkg_download_distfile(
    PUBLIC_SUFFIX_LIST_DAT
    URLS https://raw.githubusercontent.com/publicsuffix/list/${list_ref}/public_suffix_list.dat
    FILENAME "libpsl-public_suffix_list-${short_hash}.dat"
    SHA512 5db12194e4a25bd02b80add28dbdcaa5da7adfda7ff9933098dfbabb815d8058cef6608fa5337fd9af037e28205d9edb4c00b7abce3975316f095efd5d256866
)

vcpkg_list(SET RUNTIME_OPTIONS)
if(libidn2 IN_LIST FEATURES)
    list(APPEND RUNTIME_OPTIONS -Druntime=libidn2)
endif()
if(libicu IN_LIST FEATURES)
    list(APPEND RUNTIME_OPTIONS -Druntime=libicu)
endif()
if(RUNTIME_OPTIONS STREQUAL "")
    message(FATAL_ERROR "At least one of libidn2 and libicu should be selected.")
endif()

# libpsl compiles and runs a small program on the build machine to stamp its copyright date. A cross build (such as
# Android) otherwise gets vcpkg's native file, which leaves the build machine without a C compiler.
if(VCPKG_CROSSCOMPILING)
    find_program(BUILD_MACHINE_CC NAMES cc gcc clang REQUIRED)
    find_program(BUILD_MACHINE_AR NAMES ar llvm-ar REQUIRED)
    set(VCPKG_MESON_NATIVE_FILE "${CURRENT_BUILDTREES_DIR}/meson-native-${TARGET_TRIPLET}.txt")
    file(WRITE "${VCPKG_MESON_NATIVE_FILE}" "[binaries]\nc = ['${BUILD_MACHINE_CC}']\nar = ['${BUILD_MACHINE_AR}']\n")
endif()

vcpkg_configure_meson(
    SOURCE_PATH "${SOURCE_PATH}"
    OPTIONS
        ${RUNTIME_OPTIONS}
        "-Dpsl_file=${PUBLIC_SUFFIX_LIST_DAT}"
        -Ddocs=false
        -Dtests=false
)

vcpkg_install_meson()
vcpkg_fixup_pkgconfig()

if(VCPKG_LIBRARY_LINKAGE STREQUAL "static")
    vcpkg_replace_string("${CURRENT_PACKAGES_DIR}/include/libpsl.h" "defined PSL_STATIC" "1")
endif()

file(MAKE_DIRECTORY "${CURRENT_PACKAGES_DIR}/tools/${PORT}")
file(RENAME "${CURRENT_PACKAGES_DIR}/bin/psl-make-dafsa" "${CURRENT_PACKAGES_DIR}/tools/${PORT}/psl-make-dafsa")
file(REMOVE "${CURRENT_PACKAGES_DIR}/debug/bin/psl-make-dafsa")
vcpkg_copy_tools(TOOL_NAMES psl AUTO_CLEAN)

file(REMOVE_RECURSE "${CURRENT_PACKAGES_DIR}/debug/share")

vcpkg_install_copyright(FILE_LIST "${SOURCE_PATH}/LICENSE")
