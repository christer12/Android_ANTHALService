#
# Copyright 2014 Dynastream Innovations
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

ifneq ($(BOARD_ANT_WIRELESS_DEVICE),)
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

#
# ANT java system service
#

LOCAL_SRC_FILES := \
    $(call all-java-files-under, src) \
    src/com/dsi/ant/server/IAntHal.aidl \
    src/com/dsi/ant/server/IAntHalCallback.aidl

LOCAL_REQUIRED_MODULES := ant-wireless.conf
LOCAL_PROGUARD_FLAG_FILES := proguard.flags
LOCAL_CERTIFICATE := platform
LOCAL_MODULE_TAGS := optional
LOCAL_PACKAGE_NAME := BluedroidANTService

# Make sure proguard doesn't trim away the framer classes, since they are only accessed through
# reflection.
LOCAL_PROGUARD_FLAGS += -keep 'class * extends com.dsi.ant.framers.IAntHciFramer'

include $(BUILD_PACKAGE)

# Configuration file for the service
include $(CLEAR_VARS)
LOCAL_MODULE := ant-wireless.conf
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_PATH := $(TARGET_OUT_ETC)
LOCAL_MODULE_TAGS := optional

ifeq ($(BOARD_ANT_WIRELESS_DEVICE), "bcm433x")
LOCAL_SRC_FILES = conf/BCM4330/ant-wireless.conf
else ifeq ($(BOARD_ANT_WIRELESS_DEVICE), "wl12xx")
LOCAL_SRC_FILES = conf/WL12XX/ant-wireless.conf
else ifeq ($(BOARD_ANT_WIRELESS_DEVICE), "wl18xx")
LOCAL_SRC_FILES = conf/TI_ST/ant-wireless.conf
else
# no HCI
LOCAL_SRC_FILES = conf/default/ant-wireless.conf
endif

include $(BUILD_PREBUILT)
endif # BOARD_ANT_WIRELESS_DEVICE defined
