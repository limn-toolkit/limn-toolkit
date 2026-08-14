package limn.icons.tabler;

/**
 * Tabler's <b>Math</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerMath implements TablerIcon {

    ABACUS("abacus"),
    ABACUS_OFF("abacus-off"),
    AMPERSAND("ampersand"),
    BACKSLASH("backslash"),
    BRACES("braces"),
    BRACES_OFF("braces-off"),
    BRACKETS("brackets"),
    BRACKETS_CONTAIN("brackets-contain"),
    BRACKETS_CONTAIN_END("brackets-contain-end"),
    BRACKETS_CONTAIN_START("brackets-contain-start"),
    BRACKETS_OFF("brackets-off"),
    DECIMAL("decimal"),
    DIVIDE("divide"),
    EQUAL("equal"),
    EQUAL_DOUBLE("equal-double"),
    EQUAL_NOT("equal-not"),
    FUNCTION("function"),
    FUNCTION_OFF("function-off"),
    INFINITY("infinity"),
    INFINITY_2("infinity-2"),
    INFINITY_OFF("infinity-off"),
    MATH("math"),
    MATH_1_DIVIDE_2("math-1-divide-2"),
    MATH_1_DIVIDE_3("math-1-divide-3"),
    MATH_AVG("math-avg"),
    MATH_COS("math-cos"),
    MATH_CTG("math-ctg"),
    MATH_EQUAL_GREATER("math-equal-greater"),
    MATH_EQUAL_LOWER("math-equal-lower"),
    MATH_FUNCTION("math-function"),
    MATH_FUNCTION_OFF("math-function-off"),
    MATH_FUNCTION_Y("math-function-y"),
    MATH_GREATER("math-greater"),
    MATH_INTEGRAL("math-integral"),
    MATH_INTEGRAL_X("math-integral-x"),
    MATH_INTEGRALS("math-integrals"),
    MATH_LOWER("math-lower"),
    MATH_MAX("math-max"),
    MATH_MAX_MIN("math-max-min"),
    MATH_MIN("math-min"),
    MATH_NOT("math-not"),
    MATH_OFF("math-off"),
    MATH_PI("math-pi"),
    MATH_PI_DIVIDE_2("math-pi-divide-2"),
    MATH_SEC("math-sec"),
    MATH_SIN("math-sin"),
    MATH_SYMBOLS("math-symbols"),
    MATH_TG("math-tg"),
    MATH_X_DIVIDE_2("math-x-divide-2"),
    MATH_X_DIVIDE_Y("math-x-divide-y"),
    MATH_X_DIVIDE_Y_2("math-x-divide-y-2"),
    MATH_X_FLOOR_DIVIDE_Y("math-x-floor-divide-y"),
    MATH_X_MINUS_X("math-x-minus-x"),
    MATH_X_MINUS_Y("math-x-minus-y"),
    MATH_X_PLUS_X("math-x-plus-x"),
    MATH_X_PLUS_Y("math-x-plus-y"),
    MATH_XY("math-xy"),
    MATH_Y_MINUS_Y("math-y-minus-y"),
    MATH_Y_PLUS_Y("math-y-plus-y"),
    MATRIX("matrix"),
    MINUS("minus"),
    MULTIPLIER_0_5X("multiplier-0-5x"),
    MULTIPLIER_1_5X("multiplier-1-5x"),
    MULTIPLIER_1X("multiplier-1x"),
    MULTIPLIER_2X("multiplier-2x"),
    OMEGA("omega"),
    PARENTHESES("parentheses"),
    PARENTHESES_OFF("parentheses-off"),
    PENDULUM("pendulum"),
    PERCENTAGE("percentage"),
    PLUS("plus"),
    PLUS_EQUAL("plus-equal"),
    PLUS_MINUS("plus-minus"),
    SLASH("slash"),
    SQUARE_ROOT("square-root"),
    SQUARE_ROOT_2("square-root-2"),
    SUM("sum"),
    SUM_OFF("sum-off"),
    TALLYMARK_1("tallymark-1"),
    TALLYMARK_2("tallymark-2"),
    TALLYMARK_3("tallymark-3"),
    TALLYMARK_4("tallymark-4"),
    TALLYMARKS("tallymarks"),
    VARIABLE("variable"),
    VARIABLE_MINUS("variable-minus"),
    VARIABLE_OFF("variable-off"),
    VARIABLE_PLUS("variable-plus"),
    X_POWER_Y("x-power-y");

    private final String iconName;

    TablerMath(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
