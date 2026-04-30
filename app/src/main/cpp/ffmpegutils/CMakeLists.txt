
# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.


set(PREBUILT_LIBS_DIR ${PROJECT_SOURCE_DIR}/../../../../external)



add_library(
        avutil
        SHARED
        IMPORTED
)

set_target_properties(
        avutil
        PROPERTIES IMPORTED_LOCATION
        # The change happens here: each of the architectures gets its own subdirectory.
        ${PREBUILT_LIBS_DIR}/ffmpeg/lib/${ANDROID_ABI}/libavutil.so
)


add_library(
        avcodec
        SHARED
        IMPORTED
)

set_target_properties(
        avcodec
        PROPERTIES IMPORTED_LOCATION
        # The change happens here: each of the architectures gets its own subdirectory.
        ${PREBUILT_LIBS_DIR}/ffmpeg/lib/${ANDROID_ABI}/libavcodec.so
)

add_library(
        swresample
        SHARED
        IMPORTED
)

set_target_properties(
        swresample
        PROPERTIES IMPORTED_LOCATION
        # The change happens here: each of the architectures gets its own subdirectory.
        ${PREBUILT_LIBS_DIR}/ffmpeg/lib/${ANDROID_ABI}/libswresample.so
)

add_library(
        swscale
        SHARED
        IMPORTED
)

set_target_properties(
        swscale
        PROPERTIES IMPORTED_LOCATION
        # The change happens here: each of the architectures gets its own subdirectory.
        ${PREBUILT_LIBS_DIR}/ffmpeg/lib/${ANDROID_ABI}/libswscale.so
)

add_library(
        avformat
        SHARED
        IMPORTED
)

set_target_properties(
        avformat
        PROPERTIES IMPORTED_LOCATION
        # The change happens here: each of the architectures gets its own subdirectory.
        ${PREBUILT_LIBS_DIR}/ffmpeg/lib/${ANDROID_ABI}/libavformat.so
)


add_library(
        avfilter
        SHARED
        IMPORTED
)

set_target_properties(
        avfilter
        PROPERTIES IMPORTED_LOCATION
        # The change happens here: each of the architectures gets its own subdirectory.
        ${PREBUILT_LIBS_DIR}/ffmpeg/lib/${ANDROID_ABI}/libavfilter.so
)



include_directories(${PREBUILT_LIBS_DIR}/ffmpeg/include)

add_library( # Sets the name of the library.
             firedown

             # Sets the library as a shared library.
             SHARED

             # Provides a relative path to your source file(s).
             utils.c
             queue.c
             helpers.c
             ffmpeg-jni.c
             downloader.c
             thumbnailer.c
             metadatareader.c
             encoder.c
        )

# Searches for a specified prebuilt library and stores the path as a
# variable. Because CMake includes system libraries in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.

find_library( # Sets the name of the path variable.
              log-lib

              # Specifies the name of the NDK library that
              # you want CMake to locate.

              log)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.


target_link_libraries( # Specifies the target library.

                       firedown

                       avcodec

                       avformat

                       avutil

                       swresample

                       swscale

                       avfilter

                       # Links the target library to the log library
                       # included in the NDK.
                       ${log-lib})
