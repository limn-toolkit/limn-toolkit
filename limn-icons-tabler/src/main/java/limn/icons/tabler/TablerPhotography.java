package limn.icons.tabler;

/**
 * Tabler's <b>Photography</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerPhotography implements TablerIcon {

    APERTURE("aperture"),
    APERTURE_OFF("aperture-off"),
    BRIGHTNESS("brightness"),
    BRIGHTNESS_2("brightness-2"),
    BRIGHTNESS_AUTO("brightness-auto"),
    BRIGHTNESS_DOWN("brightness-down"),
    BRIGHTNESS_HALF("brightness-half"),
    BRIGHTNESS_OFF("brightness-off"),
    BRIGHTNESS_UP("brightness-up"),
    CAMERA_ROTATE("camera-rotate"),
    CAMERA_SELFIE("camera-selfie"),
    CONTRAST("contrast"),
    CONTRAST_2("contrast-2"),
    CONTRAST_2_OFF("contrast-2-off"),
    CONTRAST_OFF("contrast-off"),
    EXPOSURE("exposure"),
    EXPOSURE_0("exposure-0"),
    EXPOSURE_MINUS_1("exposure-minus-1"),
    EXPOSURE_MINUS_2("exposure-minus-2"),
    EXPOSURE_OFF("exposure-off"),
    EXPOSURE_PLUS_1("exposure-plus-1"),
    EXPOSURE_PLUS_2("exposure-plus-2"),
    FOCUS("focus"),
    FOCUS_2("focus-2"),
    FOCUS_AUTO("focus-auto"),
    HDR("hdr"),
    IMAGE_IN_PICTURE("image-in-picture"),
    LIVE_PHOTO("live-photo"),
    LIVE_PHOTO_OFF("live-photo-off"),
    MACRO("macro"),
    MACRO_OFF("macro-off"),
    PANORAMA_HORIZONTAL("panorama-horizontal"),
    PANORAMA_HORIZONTAL_OFF("panorama-horizontal-off"),
    PANORAMA_VERTICAL("panorama-vertical"),
    PANORAMA_VERTICAL_OFF("panorama-vertical-off"),
    PHOTO_BITCOIN("photo-bitcoin"),
    PHOTO_CIRCLE("photo-circle"),
    PHOTO_HEXAGON("photo-hexagon"),
    PHOTO_PENTAGON("photo-pentagon"),
    PHOTO_SENSOR("photo-sensor"),
    PHOTO_SENSOR_2("photo-sensor-2"),
    PHOTO_SENSOR_3("photo-sensor-3"),
    PHOTO_SQUARE_ROUNDED("photo-square-rounded"),
    PHOTO_VIDEO("photo-video"),
    POLAROID("polaroid"),
    SCREENSHOT("screenshot"),
    SHADOW("shadow"),
    SHADOW_OFF("shadow-off"),
    TILT_SHIFT("tilt-shift"),
    TILT_SHIFT_OFF("tilt-shift-off");

    private final String iconName;

    TablerPhotography(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
