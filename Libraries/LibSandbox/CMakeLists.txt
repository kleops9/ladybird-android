set(SOURCES
    ConnectBroker.cpp
    Sandbox.cpp
)

if (LINUX OR ANDROID)
    list(APPEND SOURCES
        FileBroker.cpp
        GpuDriverPaths.cpp
        Seccomp.cpp
    )
endif()

ladybird_lib(LibSandbox sandbox)

if (LINUX OR APPLE OR ANDROID)
    target_link_libraries(LibSandbox PRIVATE LibCore)
endif()
