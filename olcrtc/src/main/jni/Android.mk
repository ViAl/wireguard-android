ROOT_PATH := $(call my-dir)

# Prebuilt hev-socks5-tunnel library
include $(CLEAR_VARS)
LOCAL_PATH := $(ROOT_PATH)
LOCAL_MODULE := hev-socks5-tunnel-prebuilt
LOCAL_SRC_FILES := ../jniLibs/$(TARGET_ARCH_ABI)/libhev-socks5-tunnel.so
LOCAL_EXPORT_C_INCLUDES := $(ROOT_PATH)/../include
include $(PREBUILT_SHARED_LIBRARY)

# JNI bridge
include $(CLEAR_VARS)
LOCAL_MODULE := olcrtc_tun2socks
LOCAL_SRC_FILES := olcrtc_tun2socks_jni.c
LOCAL_C_INCLUDES := $(ROOT_PATH)/../include
LOCAL_SHARED_LIBRARIES := hev-socks5-tunnel-prebuilt
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
