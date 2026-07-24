LOCAL_PATH := $(call my-dir)

LOCAL_PATH_SRC_PLAYER := $(LOCAL_PATH)
include $(LOCAL_PATH_SRC_PLAYER)/ffmpeg/Android.mk
include $(LOCAL_PATH_SRC_PLAYER)/yuv/Android.mk
LOCAL_PATH := $(LOCAL_PATH_SRC_PLAYER)

include $(CLEAR_VARS)
LOCAL_MODULE := player
LOCAL_SRC_FILES := native.c player.c util.c
LOCAL_CFLAGS += -std=c99 -Wall -Wextra -Wpedantic -Wno-deprecated-declarations
ifeq ($(DASHCHAN_FFMPEG_FLAVOR),)
LOCAL_CFLAGS += -DDASHCHAN_FFMPEG_FLAVOR=\"ffmpeg\"
else
LOCAL_CFLAGS += -DDASHCHAN_FFMPEG_FLAVOR=\"$(DASHCHAN_FFMPEG_FLAVOR)\"
endif
LOCAL_LDFLAGS += -Wl,--build-id=none
LOCAL_LDLIBS += -landroid -lOpenSLES -llog
ifeq ($(NDK_DEBUG),1)
LOCAL_CFLAGS += -DDEBUG_VERBOSE
else
LOCAL_CFLAGS += -Werror
endif
LOCAL_SHARED_LIBRARIES := avcodec avformat avutil swresample swscale yuv
ifeq ($(DASHCHAN_FFMPEG_FLAVOR),ffmpeg8)
LOCAL_CFLAGS += -DDASHCHAN_HAS_ATEMPO=1 -DDASHCHAN_HAS_MEDIACODEC=1
LOCAL_SRC_FILES += tempo.c
LOCAL_SHARED_LIBRARIES += avfilter
ifneq ($(DASHCHAN_WEBM_SHARED_LIBRARIES),)
LOCAL_SHARED_LIBRARIES += dav1d
endif
endif
include $(BUILD_SHARED_LIBRARY)
