# Inherit from GrapheneOS bluejay configuration
$(call inherit-product, device/google/bluejay/aosp_bluejay.mk)

# DollOS identity
PRODUCT_NAME := dollos_bluejay
PRODUCT_DEVICE := bluejay
PRODUCT_BRAND := DollOS
PRODUCT_MODEL := DollOS on Pixel 6a
PRODUCT_MANUFACTURER := DollOS

# DollOS version
DOLLOS_VERSION := 0.1.0
PRODUCT_PROPERTY_OVERRIDES += \
    ro.dollos.version=$(DOLLOS_VERSION) \
    ro.build.display.id=DollOS-$(DOLLOS_VERSION)

# DollOS packages
PRODUCT_PACKAGES += \
    DollOSService \
    DollOSSetupWizard

# Privapp permissions
PRODUCT_COPY_FILES += \
    packages/apps/DollOSService/privapp-permissions-dollos-service.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-dollos-service.xml \
    packages/apps/DollOSSetupWizard/privapp-permissions-dollos-setup.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-dollos-setup.xml

# DollOS branding overlay
PRODUCT_PACKAGE_OVERLAYS += vendor/dollos/branding/overlay
