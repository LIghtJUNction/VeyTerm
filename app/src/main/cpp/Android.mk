LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
bootstrap_arch_arm64-v8a := aarch64
bootstrap_arch_armeabi-v7a := arm
bootstrap_arch_x86 := i686
bootstrap_arch_x86_64 := x86_64
bootstrap_archive := $(LOCAL_PATH)/bootstrap-$(bootstrap_arch_$(TARGET_ARCH_ABI)).zip
include $(BUILD_SHARED_LIBRARY)

# .incbin inputs are not emitted in clang's assembly dependency file.
$(LOCAL_OBJS_DIR)/termux-bootstrap-zip.o: $(bootstrap_archive)

veyterm_app_cpp_path := $(LOCAL_PATH)
include $(veyterm_app_cpp_path)/veyterm-archive/Android.mk
LOCAL_PATH := $(veyterm_app_cpp_path)
