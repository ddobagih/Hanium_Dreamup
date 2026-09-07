cmake_minimum_required(VERSION 3.22.1)

project(walksafe_ppk C)

set(RTKLIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vendor/rtklib/src")

add_library(
    walksafe_ppk
    SHARED
    walksafe_ppk_jni.c
    walksafe_ppk_engine.c
    "${RTKLIB_DIR}/rtkcmn.c"
    "${RTKLIB_DIR}/trace.c"
    "${RTKLIB_DIR}/rinex.c"
    "${RTKLIB_DIR}/rtkpos.c"
    "${RTKLIB_DIR}/postpos.c"
    "${RTKLIB_DIR}/solution.c"
    "${RTKLIB_DIR}/lambda.c"
    "${RTKLIB_DIR}/geoid.c"
    "${RTKLIB_DIR}/sbas.c"
    "${RTKLIB_DIR}/preceph.c"
    "${RTKLIB_DIR}/pntpos.c"
    "${RTKLIB_DIR}/ephemeris.c"
    "${RTKLIB_DIR}/options.c"
    "${RTKLIB_DIR}/ppp.c"
    "${RTKLIB_DIR}/ppp_ar.c"
    "${RTKLIB_DIR}/rtcm.c"
    "${RTKLIB_DIR}/rtcm2.c"
    "${RTKLIB_DIR}/rtcm3.c"
    "${RTKLIB_DIR}/rtcm3e.c"
    "${RTKLIB_DIR}/ionex.c"
    "${RTKLIB_DIR}/tides.c"
    "${RTKLIB_DIR}/sofa.c"
)

target_include_directories(walksafe_ppk PRIVATE "${RTKLIB_DIR}")
target_compile_features(walksafe_ppk PRIVATE c_std_99)
target_compile_definitions(
    walksafe_ppk
    PRIVATE
    TRACE
    ENAGLO
    ENAQZS
    ENAGAL
    ENACMP
    ENAIRN
    NFREQ=4
    NEXOBS=3
)
target_compile_options(
    walksafe_ppk
    PRIVATE
    -Wall
    -Wno-format-overflow
    -Wno-unused-but-set-variable
    -Wno-unused-result
    -Wno-unused-variable
)

find_library(LOG_LIBRARY log REQUIRED)
target_link_libraries(walksafe_ppk PRIVATE m "${LOG_LIBRARY}")
