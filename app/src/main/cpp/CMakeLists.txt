cmake_minimum_required(VERSION 3.22.1)
project("mp3tomidiaudio")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Provided by the com.google.oboe:oboe Gradle dependency via Prefab (buildFeatures.prefab = true
# in app/build.gradle.kts). If this fails to resolve, double-check that dependency is present
# and that "Prefab" support is enabled for this AGP/Gradle version.
find_package(oboe REQUIRED CONFIG)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native_sound_engine.cpp
)

target_link_libraries(${CMAKE_PROJECT_NAME}
        oboe::oboe
        android
        log
)
