# 0.5.58: each Space frame's own display present time, from EGL_ANDROID_get_frame_timestamps.
cmake_minimum_required(VERSION 3.22.1)
project(flipspaces_frametimes CXX)

add_library(flipspaces_frametimes SHARED frame_timestamps.cpp)
target_link_libraries(flipspaces_frametimes EGL log)
