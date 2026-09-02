cmake_minimum_required(VERSION 3.22.1)

project(r2h_native VERSION 0.1.0 LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(R2H_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")

# ─── llama.cpp integration ────────────────────────────────────────────────────
#
# Strategy A (recommended for offline / CI builds):
#   Add llama.cpp as a git submodule:
#     git submodule add https://github.com/ggerganov/llama.cpp \
#         engine-native/src/main/cpp/llama.cpp
#   Then re-sync the project. CMake picks it up automatically (path check below).
#
# Strategy B (default — internet required at first build):
#   FetchContent downloads llama.cpp during CMake configure phase.
#   Subsequent builds use the CMake cache; no repeated download.
#
# ─────────────────────────────────────────────────────────────────────────────

# Suppress llama.cpp's own tests, examples, and server to minimise build time.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   ON CACHE BOOL "" FORCE)
# Keep as static library — r2h_native.so is the only deliverable .so in the APK.
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
# LLAMA_NATIVE=OFF is critical for cross-compilation: do not use host CPU features.
set(LLAMA_NATIVE         OFF CACHE BOOL "" FORCE)

if(EXISTS "${R2H_REPO_ROOT}/third_party/runtimes/llama.cpp/CMakeLists.txt")
    message(STATUS "r2h_native: using local third_party llama.cpp")
    add_subdirectory("${R2H_REPO_ROOT}/third_party/runtimes/llama.cpp" llama.cpp EXCLUDE_FROM_ALL)
elseif(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    message(STATUS "r2h_native: using llama.cpp git submodule")
    add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)
else()
    message(FATAL_ERROR "r2h_native: llama.cpp source missing; downloads are disabled for this build")
endif()

# ─── NCNN shared runtime ─────────────────────────────────────────────────────

set(NCNN_ROOT "${R2H_REPO_ROOT}/third_party/runtimes/ncnn/extracted/ncnn-20260526-android-shared/arm64-v8a")
add_library(ncnn SHARED IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION "${NCNN_ROOT}/lib/libncnn.so"
    INTERFACE_INCLUDE_DIRECTORIES "${NCNN_ROOT}/include"
)

# ─── whisper.cpp runtime ─────────────────────────────────────────────────────

set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL           OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)
set(WHISPER_COREML         OFF CACHE BOOL "" FORCE)
set(WHISPER_OPENVINO       OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
if(EXISTS "${R2H_REPO_ROOT}/third_party/runtimes/whisper.cpp/CMakeLists.txt")
    add_subdirectory("${R2H_REPO_ROOT}/third_party/runtimes/whisper.cpp" whisper.cpp EXCLUDE_FROM_ALL)
else()
    message(FATAL_ERROR "r2h_native: whisper.cpp source missing")
endif()

# ─── llama.cpp mtmd multimodal runtime ───────────────────────────────────────

set(MTMD_ROOT "${R2H_REPO_ROOT}/third_party/runtimes/llama.cpp/tools/mtmd")
if(EXISTS "${MTMD_ROOT}/mtmd.cpp")
    add_library(mtmd STATIC
        "${MTMD_ROOT}/mtmd.cpp"
        "${MTMD_ROOT}/mtmd-audio.cpp"
        "${MTMD_ROOT}/mtmd-helper.cpp"
        "${MTMD_ROOT}/mtmd-image.cpp"
        "${MTMD_ROOT}/clip.cpp"
        "${MTMD_ROOT}/models/cogvlm.cpp"
        "${MTMD_ROOT}/models/conformer.cpp"
        "${MTMD_ROOT}/models/dotsocr.cpp"
        "${MTMD_ROOT}/models/exaone4_5.cpp"
        "${MTMD_ROOT}/models/gemma4a.cpp"
        "${MTMD_ROOT}/models/gemma4v.cpp"
        "${MTMD_ROOT}/models/gemma4ua.cpp"
        "${MTMD_ROOT}/models/gemma4uv.cpp"
        "${MTMD_ROOT}/models/glm4v.cpp"
        "${MTMD_ROOT}/models/granite-speech.cpp"
        "${MTMD_ROOT}/models/granite4-vision.cpp"
        "${MTMD_ROOT}/models/hunyuanvl.cpp"
        "${MTMD_ROOT}/models/internvl.cpp"
        "${MTMD_ROOT}/models/kimivl.cpp"
        "${MTMD_ROOT}/models/kimik25.cpp"
        "${MTMD_ROOT}/models/nemotron-v2-vl.cpp"
        "${MTMD_ROOT}/models/llama4.cpp"
        "${MTMD_ROOT}/models/llava.cpp"
        "${MTMD_ROOT}/models/minicpmv.cpp"
        "${MTMD_ROOT}/models/paddleocr.cpp"
        "${MTMD_ROOT}/models/pixtral.cpp"
        "${MTMD_ROOT}/models/qwen2vl.cpp"
        "${MTMD_ROOT}/models/qwen3vl.cpp"
        "${MTMD_ROOT}/models/mimovl.cpp"
        "${MTMD_ROOT}/models/qwen3a.cpp"
        "${MTMD_ROOT}/models/step3vl.cpp"
        "${MTMD_ROOT}/models/siglip.cpp"
        "${MTMD_ROOT}/models/whisper-enc.cpp"
        "${MTMD_ROOT}/models/deepseekocr.cpp"
        "${MTMD_ROOT}/models/deepseekocr2.cpp"
        "${MTMD_ROOT}/models/mobilenetv5.cpp"
        "${MTMD_ROOT}/models/youtuvl.cpp"
        "${MTMD_ROOT}/models/yasa2.cpp"
    )
    target_include_directories(mtmd PRIVATE
        "${MTMD_ROOT}"
        "${R2H_REPO_ROOT}/third_party/runtimes/llama.cpp"
        "${R2H_REPO_ROOT}/third_party/runtimes/llama.cpp/include"
        "${R2H_REPO_ROOT}/third_party/runtimes/llama.cpp/ggml/include"
        "${R2H_REPO_ROOT}/third_party/runtimes/llama.cpp/ggml/src"
        "${R2H_REPO_ROOT}/third_party/runtimes/llama.cpp/vendor"
    )
    target_link_libraries(mtmd PRIVATE llama)
endif()

# ─── r2h_native shared library ───────────────────────────────────────────────

add_library(r2h_native SHARED r2h_native.cpp)

target_include_directories(r2h_native PRIVATE
    "${MTMD_ROOT}"
    "${R2H_REPO_ROOT}/third_party/runtimes/llama.cpp/common"
    "${R2H_REPO_ROOT}/third_party/runtimes/llama.cpp/vendor"
)

target_compile_options(r2h_native PRIVATE
    -Wall
    -Wextra
    -Werror
    -Wno-unused-parameter
    -Wno-unused-function
)

# Android log library for __android_log_print.
find_library(log-lib log)

# Linking against the 'llama' target automatically pulls in ggml and any
# other transitive dependencies declared in llama.cpp's CMakeLists.txt.
target_link_libraries(
    r2h_native
    llama
    llama-common
    ncnn
    whisper
    mtmd
    ${log-lib}
)

# Android 15+ devices can run in 16 KB page-size mode; the dynamic loader
# refuses shared objects whose LOAD segments are only 4 KB-aligned.
# Target-scoped (r2h_native only) so vendored dependency targets keep their
# own link configuration.
target_link_options(r2h_native PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
