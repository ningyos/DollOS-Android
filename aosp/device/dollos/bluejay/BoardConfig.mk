# Inherit from GrapheneOS bluejay board config
include device/google/bluejay/BoardConfig.mk

# DollOS SELinux policy (device-specific additions, NOT system/sepolicy/private)
BOARD_SEPOLICY_DIRS += device/dollos/bluejay/sepolicy
