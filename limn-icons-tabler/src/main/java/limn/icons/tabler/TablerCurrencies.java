package limn.icons.tabler;

/**
 * Tabler's <b>Currencies</b> icons, one constant per name.
 *
 * <p>Generated. The set is split across one enum per upstream category because a single
 * enum cannot hold it: a class initialiser is capped at 64KB of bytecode and every constant
 * costs roughly twenty of them, so an enum of all 5130
 * names does not compile at all. The categories are upstream's own, and the largest of them
 * is comfortably inside the ceiling.
 */
public enum TablerCurrencies implements TablerIcon {

    CURRENCY("currency"),
    CURRENCY_AFGHANI("currency-afghani"),
    CURRENCY_BAHRAINI("currency-bahraini"),
    CURRENCY_BAHT("currency-baht"),
    CURRENCY_BITCOIN("currency-bitcoin"),
    CURRENCY_CENT("currency-cent"),
    CURRENCY_DINAR("currency-dinar"),
    CURRENCY_DIRHAM("currency-dirham"),
    CURRENCY_DOGECOIN("currency-dogecoin"),
    CURRENCY_DOLLAR("currency-dollar"),
    CURRENCY_DOLLAR_AUSTRALIAN("currency-dollar-australian"),
    CURRENCY_DOLLAR_BRUNEI("currency-dollar-brunei"),
    CURRENCY_DOLLAR_CANADIAN("currency-dollar-canadian"),
    CURRENCY_DOLLAR_GUYANESE("currency-dollar-guyanese"),
    CURRENCY_DOLLAR_OFF("currency-dollar-off"),
    CURRENCY_DOLLAR_SINGAPORE("currency-dollar-singapore"),
    CURRENCY_DOLLAR_ZIMBABWEAN("currency-dollar-zimbabwean"),
    CURRENCY_DONG("currency-dong"),
    CURRENCY_DRAM("currency-dram"),
    CURRENCY_ETHEREUM("currency-ethereum"),
    CURRENCY_EURO("currency-euro"),
    CURRENCY_EURO_OFF("currency-euro-off"),
    CURRENCY_FLORIN("currency-florin"),
    CURRENCY_FORINT("currency-forint"),
    CURRENCY_FRANK("currency-frank"),
    CURRENCY_GUARANI("currency-guarani"),
    CURRENCY_HRYVNIA("currency-hryvnia"),
    CURRENCY_HUSD("currency-husd"),
    CURRENCY_IRANIAN_RIAL("currency-iranian-rial"),
    CURRENCY_KIP("currency-kip"),
    CURRENCY_KRONE_CZECH("currency-krone-czech"),
    CURRENCY_KRONE_DANISH("currency-krone-danish"),
    CURRENCY_KRONE_SWEDISH("currency-krone-swedish"),
    CURRENCY_LARI("currency-lari"),
    CURRENCY_LEU("currency-leu"),
    CURRENCY_LIRA("currency-lira"),
    CURRENCY_LITECOIN("currency-litecoin"),
    CURRENCY_LYD("currency-lyd"),
    CURRENCY_MANAT("currency-manat"),
    CURRENCY_MONERO("currency-monero"),
    CURRENCY_NAIRA("currency-naira"),
    CURRENCY_NANO("currency-nano"),
    CURRENCY_OFF("currency-off"),
    CURRENCY_PAANGA("currency-paanga"),
    CURRENCY_PESO("currency-peso"),
    CURRENCY_POUND("currency-pound"),
    CURRENCY_POUND_OFF("currency-pound-off"),
    CURRENCY_QUETZAL("currency-quetzal"),
    CURRENCY_REAL("currency-real"),
    CURRENCY_RENMINBI("currency-renminbi"),
    CURRENCY_RIPPLE("currency-ripple"),
    CURRENCY_RIYAL("currency-riyal"),
    CURRENCY_RUBLE("currency-ruble"),
    CURRENCY_RUFIYAA("currency-rufiyaa"),
    CURRENCY_RUPEE("currency-rupee"),
    CURRENCY_RUPEE_NEPALESE("currency-rupee-nepalese"),
    CURRENCY_SHEKEL("currency-shekel"),
    CURRENCY_SOLANA("currency-solana"),
    CURRENCY_SOM("currency-som"),
    CURRENCY_TAKA("currency-taka"),
    CURRENCY_TENGE("currency-tenge"),
    CURRENCY_TETHER("currency-tether"),
    CURRENCY_TUGRIK("currency-tugrik"),
    CURRENCY_WON("currency-won"),
    CURRENCY_XRP("currency-xrp"),
    CURRENCY_YEN("currency-yen"),
    CURRENCY_YEN_OFF("currency-yen-off"),
    CURRENCY_YUAN("currency-yuan"),
    CURRENCY_ZCASH("currency-zcash"),
    CURRENCY_ZLOTY("currency-zloty"),
    TAIWAN_DOLLAR("taiwan-dollar"),
    TAX("tax"),
    TAX_EURO("tax-euro"),
    TAX_POUND("tax-pound"),
    TRANSACTION_BITCOIN("transaction-bitcoin"),
    TRANSACTION_RUPEE("transaction-rupee");

    private final String iconName;

    TablerCurrencies(String iconName) {
        this.iconName = iconName;
    }

    @Override
    public String iconName() {
        return iconName;
    }
}
