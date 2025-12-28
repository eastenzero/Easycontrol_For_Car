cmake_minimum_required(VERSION 3.22.1)

project("easycontrol")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(native-lib SHARED
        native-lib.cpp)

find_package(oboe REQUIRED CONFIG)

target_link_libraries(native-lib
        log
        oboe::oboe)
