package limn.icons.tabler;

/**
 * Tabler's <b>Health</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerHealth implements TablerIcon {

    ACCESSIBLE("accessible"),
    ACCESSIBLE_OFF("accessible-off"),
    ACTIVITY("activity"),
    ACTIVITY_HEARTBEAT("activity-heartbeat"),
    BABY_BOTTLE("baby-bottle"),
    BABY_CARRIAGE("baby-carriage"),
    BANDAGE("bandage"),
    BANDAGE_OFF("bandage-off"),
    BLIND("blind"),
    BRAILLE("braille"),
    BRAIN("brain"),
    CANE("cane"),
    CHECKUP_LIST("checkup-list"),
    CRUTCHES("crutches"),
    CRUTCHES_OFF("crutches-off"),
    DEAF("deaf"),
    DENTAL("dental"),
    DENTAL_OFF("dental-off"),
    DISABLED("disabled"),
    DISABLED_2("disabled-2"),
    DISABLED_OFF("disabled-off"),
    DNA("dna"),
    DNA_2("dna-2"),
    DNA_2_OFF("dna-2-off"),
    DNA_OFF("dna-off"),
    EAR("ear"),
    EAR_OFF("ear-off"),
    EMERGENCY_BED("emergency-bed"),
    EMPATHIZE("empathize"),
    EMPATHIZE_OFF("empathize-off"),
    EYE_BITCOIN("eye-bitcoin"),
    EYE_DISCOUNT("eye-discount"),
    EYE_DOTTED("eye-dotted"),
    EYE_TABLE("eye-table"),
    EYEGLASS("eyeglass"),
    EYEGLASS_2("eyeglass-2"),
    EYEGLASS_OFF("eyeglass-off"),
    FACE_MASK("face-mask"),
    FACE_MASK_OFF("face-mask-off"),
    FALL("fall"),
    FINGERPRINT_SCAN("fingerprint-scan"),
    FIRST_AID_KIT("first-aid-kit"),
    FIRST_AID_KIT_OFF("first-aid-kit-off"),
    FLASK("flask"),
    FLASK_2("flask-2"),
    FLASK_2_OFF("flask-2-off"),
    FLASK_OFF("flask-off"),
    FOOTSTEPS("footsteps"),
    HAND_SANITIZER("hand-sanitizer"),
    HEART_BITCOIN("heart-bitcoin"),
    HEART_BROKEN("heart-broken"),
    HEART_DISCOUNT("heart-discount"),
    HEART_HANDSHAKE("heart-handshake"),
    HEART_RATE_MONITOR("heart-rate-monitor"),
    HEARTBEAT("heartbeat"),
    HOSPITAL("hospital"),
    HOSPITAL_CIRCLE("hospital-circle"),
    LUNGS("lungs"),
    LUNGS_OFF("lungs-off"),
    MASSAGE("massage"),
    MEDICINE_SYRUP("medicine-syrup"),
    MICROSCOPE("microscope"),
    MICROSCOPE_OFF("microscope-off"),
    NURSE("nurse"),
    OLD("old"),
    PHYSIOTHERAPIST("physiotherapist"),
    PILL("pill"),
    PILL_OFF("pill-off"),
    PILLS("pills"),
    PLUNGER("plunger"),
    PRESCRIPTION("prescription"),
    RAZOR("razor"),
    RAZOR_ELECTRIC("razor-electric"),
    SKULL("skull"),
    SMOKING("smoking"),
    SMOKING_NO("smoking-no"),
    STETHOSCOPE("stethoscope"),
    STETHOSCOPE_OFF("stethoscope-off"),
    SUNGLASSES("sunglasses"),
    THERMOMETER("thermometer"),
    VACCINE("vaccine"),
    VACCINE_BOTTLE("vaccine-bottle"),
    VACCINE_BOTTLE_OFF("vaccine-bottle-off"),
    VACCINE_OFF("vaccine-off"),
    VIRUS("virus"),
    VIRUS_OFF("virus-off"),
    VIRUS_SEARCH("virus-search");

    private final String iconName;

    TablerHealth(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
