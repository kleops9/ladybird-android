set(HUB_CLIENT_DEFAULTS_HEADER "${CMAKE_CURRENT_BINARY_DIR}/HubClientDefaults.h")
add_custom_command(
    OUTPUT "${HUB_CLIENT_DEFAULTS_HEADER}"
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/generate_hub_client_defaults.py"
        "${CMAKE_BINARY_DIR}/Services/Hub/HubClientEndpoint.h" "${HUB_CLIENT_DEFAULTS_HEADER}"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/generate_hub_client_defaults.py" hubipc
    VERBATIM
)
add_custom_target(generate_hub_client_defaults DEPENDS "${HUB_CLIENT_DEFAULTS_HEADER}")

add_library(ladybird SHARED
    src/main/cpp/ALooperEventLoopImplementation.cpp
    src/main/cpp/HubConnection.cpp
    src/main/cpp/JNIHelpers.cpp
    src/main/cpp/LadybirdActivity.cpp
    src/main/cpp/RemoteViewAndroid.cpp
    src/main/cpp/TimerExecutorService.cpp
    src/main/cpp/WebViewImplementationNativeJNI.cpp
)
add_dependencies(ladybird generate_hub_client_defaults)
target_include_directories(ladybird PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
target_link_libraries(ladybird PRIVATE LibFileSystem jnigraphics android)

include(../cmake/AndroidExtras.cmake)
create_ladybird_bundle(ladybird)
