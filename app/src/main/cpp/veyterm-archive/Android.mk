# Included explicitly by app/src/main/cpp/Android.mk after prepare.py.
LOCAL_PATH := $(call my-dir)
ifeq ($(wildcard $(LOCAL_PATH)/build/source/.prepared),)
$(error Run python3 app/src/main/cpp/veyterm-archive/prepare.py before ndk-build)
endif
include $(CLEAR_VARS)
LOCAL_MODULE := veyterm_rar_reader
LOCAL_SRC_FILES := $(addprefix build/source/,$(shell cat $(LOCAL_PATH)/sources.txt))
LOCAL_C_INCLUDES := $(LOCAL_PATH) $(LOCAL_PATH)/build/source
LOCAL_CFLAGS := -std=c11 -D_GNU_SOURCE -DHAVE_CONFIG_H -DVEYTERM_ARCHIVE_BUDGET -fvisibility=hidden -ffunction-sections -fdata-sections -fstack-protector-strong
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := veyterm_archive
LOCAL_SRC_FILES := rar_jni.c rar_alloc.c
LOCAL_C_INCLUDES := $(LOCAL_PATH) $(LOCAL_PATH)/build/source
LOCAL_CFLAGS := -std=c11 -D_GNU_SOURCE -fvisibility=hidden -ffunction-sections -fdata-sections -fstack-protector-strong
LOCAL_STATIC_LIBRARIES := veyterm_rar_reader
LOCAL_LDLIBS := -lz
LOCAL_LDFLAGS := -Wl,--gc-sections -Wl,--version-script=$(LOCAL_PATH)/exports.map
include $(BUILD_SHARED_LIBRARY)
