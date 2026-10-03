# Redirect: the DIC engine lives in the repo-root engine/ package.
# Prefer Gradle path: ../engine/CMakeLists.txt with -DSEMPER_ANDROID=ON
cmake_minimum_required(VERSION 3.22)
set(SEMPER_ANDROID ON CACHE BOOL "" FORCE)
add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native
    ${CMAKE_BINARY_DIR}/semper
)
