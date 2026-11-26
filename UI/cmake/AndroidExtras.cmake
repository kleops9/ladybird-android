# Copyright (c) 2022, Andrew Kaster <akaster@serenityos.org>
#
# SPDX-License-Identifier: BSD-2-Clause
#

# The resources (share/Lagom in the build directory) go into the APK's assets as a zip file, which the app extracts
# into its data directory on first launch.
set(LADYBIRD_ANDROID_ASSETS_DIR "${LADYBIRD_SOURCE_DIR}/UI/Android/src/main/assets")
set(LADYBIRD_ANDROID_RESOURCE_ROOT "${CMAKE_BINARY_DIR}/${IN_BUILD_PREFIX}${CMAKE_INSTALL_DATADIR}/Lagom")
add_custom_target(archive-assets
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${LADYBIRD_ANDROID_ASSETS_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E chdir "${LADYBIRD_ANDROID_RESOURCE_ROOT}"
        "${CMAKE_COMMAND}" -E tar cf "${CMAKE_BINARY_DIR}/ladybird-assets.zip" --format=zip .
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different "${CMAKE_BINARY_DIR}/ladybird-assets.zip" "${LADYBIRD_ANDROID_ASSETS_DIR}/"
    VERBATIM
)
add_dependencies(archive-assets ladybird_build_resource_files)
add_dependencies(ladybird archive-assets)

# Android only lets an app run programs from its native library directory, where only files named lib*.so are
# installed. So the helper processes are built under such names, into a directory that Gradle packages as jniLibs.
# This is called from UI/CMakeLists.txt, which can't see variables set in this directory, so it works the path out itself.
function(set_android_helper_process_properties)
    set(helpers_dir "${LADYBIRD_SOURCE_DIR}/UI/Android/build/helpers/${ANDROID_ABI}")
    foreach (helper IN LISTS ARGN)
        set_target_properties(${helper} PROPERTIES
            OUTPUT_NAME "lib${helper}"
            SUFFIX ".so"
            RUNTIME_OUTPUT_DIRECTORY "${helpers_dir}"
        )
    endforeach()
endfunction()
