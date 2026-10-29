# SPDX-License-Identifier: BSD-2-Clause
# Copyright (c) 2026 VeyTerm contributors. See LICENSE.guard.
# Separate executable module. Never use LOCAL_STATIC/SHARED_LIBRARIES to link
# this or UnRAR source to libveyterm_archive or any GPL code.
LOCAL_PATH := $(call my-dir)
ifeq ($(wildcard $(LOCAL_PATH)/build/source/.prepared),)
$(error Run python3 app/src/main/cpp/veyterm-unrar/prepare.py first)
endif
include $(CLEAR_VARS)
LOCAL_MODULE := veyterm_unrar
LOCAL_MODULE_FILENAME := libveyterm_unrar.so
LOCAL_SRC_FILES := $(addprefix build/source/,$(shell cat $(LOCAL_PATH)/sources.txt)) command_main.cpp
LOCAL_CPPFLAGS := -std=c++11 -O2 -DUNRAR -Dmain=unrar_main -D_FILE_OFFSET_BITS=64 -D_LARGEFILE_SOURCE -fPIE -fstack-protector-strong -ffunction-sections -fdata-sections
LOCAL_CPPFLAGS += -include$(LOCAL_PATH)/android_compat.hpp
# command_main.cpp undefines the upstream entry point rename below.
LOCAL_LDFLAGS := -pie -Wl,--gc-sections -Wl,-z,relro,-z,now -Wl,-z,max-page-size=16384
include $(BUILD_EXECUTABLE)
