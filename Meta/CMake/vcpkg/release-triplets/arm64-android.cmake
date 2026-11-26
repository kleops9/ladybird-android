include(${CMAKE_CURRENT_LIST_DIR}/../base-triplets/arm64-android.cmake)
# The dependencies are linked statically into our shared libraries, so only the build type comes from release.cmake.
set(VCPKG_BUILD_TYPE release)
