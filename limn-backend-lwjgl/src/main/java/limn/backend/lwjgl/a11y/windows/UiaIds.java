package limn.backend.lwjgl.a11y.windows;

/**
 * UI Automation's own identifiers, as this platform numbers them.
 *
 * <p><b>Every number here was read off a machine and none was written from memory.</b> They come
 * from the interop assemblies installed on the guest, which are the same identifiers
 * {@code uiautomationcore} hands a client:
 *
 * <pre>
 *   Read from C:\WINDOWS\Microsoft.Net\assembly\GAC_MSIL\UIAutomationTypes\v4.0_4.0.0.0__31bf3856ad364e35\UIAutomationTypes.dll
 *   and from C:\WINDOWS\Microsoft.Net\assembly\GAC_MSIL\UIAutomationProvider\v4.0_4.0.0.0__31bf3856ad364e35\UIAutomationProvider.dll
 *   on Microsoft Windows NT 10.0.26200.0, ARM64
 * </pre>
 *
 * <p>The script that produced them is {@code scripts/a11y/windows/dump-uia-constants.ps1}; run it
 * on the guest rather than trusting this table when a platform version moves. ADR 039 §12.3 asks
 * for that, and the reason is that a wrong number here does not fail: a property id we guessed
 * wrong is a property the client silently never sees, and a control type we guessed wrong
 * announces a slider as a menu item in a voice the person relying on it cannot check against the
 * screen. The first reading already caught one such trap — {@code ServerSideProvider} is 2, and
 * 1 is {@code ClientSideProvider}.
 *
 * <p>The whole set is kept, not the subset in use today, because the cost of the extra lines is
 * nothing and the cost of a second trip to a guest that has since moved on is a re-reading nobody
 * can compare against the first.
 */
final class UiaIds {

    private UiaIds() {
    }

    // ---- property ids
    static final int RUNTIME_ID = 30000;
    static final int BOUNDING_RECTANGLE = 30001;
    static final int PROCESS_ID = 30002;
    static final int CONTROL_TYPE = 30003;
    static final int LOCALIZED_CONTROL_TYPE = 30004;
    static final int NAME = 30005;
    static final int ACCELERATOR_KEY = 30006;
    static final int ACCESS_KEY = 30007;
    static final int HAS_KEYBOARD_FOCUS = 30008;
    static final int IS_KEYBOARD_FOCUSABLE = 30009;
    static final int IS_ENABLED = 30010;
    static final int AUTOMATION_ID = 30011;
    static final int CLASS_NAME = 30012;
    static final int HELP_TEXT = 30013;
    static final int CLICKABLE_POINT = 30014;
    static final int CULTURE = 30015;
    static final int IS_CONTROL_ELEMENT = 30016;
    static final int IS_CONTENT_ELEMENT = 30017;
    static final int LABELED_BY = 30018;
    static final int IS_PASSWORD = 30019;
    static final int NATIVE_WINDOW_HANDLE = 30020;
    static final int ITEM_TYPE = 30021;
    static final int IS_OFFSCREEN = 30022;
    static final int ORIENTATION = 30023;
    static final int FRAMEWORK_ID = 30024;
    static final int IS_REQUIRED_FOR_FORM = 30025;
    static final int ITEM_STATUS = 30026;
    static final int IS_DOCK_PATTERN_AVAILABLE = 30027;
    static final int IS_EXPAND_COLLAPSE_PATTERN_AVAILABLE = 30028;
    static final int IS_GRID_ITEM_PATTERN_AVAILABLE = 30029;
    static final int IS_GRID_PATTERN_AVAILABLE = 30030;
    static final int IS_INVOKE_PATTERN_AVAILABLE = 30031;
    static final int IS_MULTIPLE_VIEW_PATTERN_AVAILABLE = 30032;
    static final int IS_RANGE_VALUE_PATTERN_AVAILABLE = 30033;
    static final int IS_SCROLL_PATTERN_AVAILABLE = 30034;
    static final int IS_SCROLL_ITEM_PATTERN_AVAILABLE = 30035;
    static final int IS_SELECTION_ITEM_PATTERN_AVAILABLE = 30036;
    static final int IS_SELECTION_PATTERN_AVAILABLE = 30037;
    static final int IS_TABLE_PATTERN_AVAILABLE = 30038;
    static final int IS_TABLE_ITEM_PATTERN_AVAILABLE = 30039;
    static final int IS_TEXT_PATTERN_AVAILABLE = 30040;
    static final int IS_TOGGLE_PATTERN_AVAILABLE = 30041;
    static final int IS_TRANSFORM_PATTERN_AVAILABLE = 30042;
    static final int IS_VALUE_PATTERN_AVAILABLE = 30043;
    static final int IS_WINDOW_PATTERN_AVAILABLE = 30044;
    static final int VALUE_VALUE = 30045;
    static final int VALUE_IS_READ_ONLY = 30046;
    static final int RANGE_VALUE_VALUE = 30047;
    static final int RANGE_VALUE_IS_READ_ONLY = 30048;
    static final int RANGE_VALUE_MINIMUM = 30049;
    static final int RANGE_VALUE_MAXIMUM = 30050;
    static final int RANGE_VALUE_LARGE_CHANGE = 30051;
    static final int RANGE_VALUE_SMALL_CHANGE = 30052;
    static final int SCROLL_HORIZONTAL_SCROLL_PERCENT = 30053;
    static final int SCROLL_HORIZONTAL_VIEW_SIZE = 30054;
    static final int SCROLL_VERTICAL_SCROLL_PERCENT = 30055;
    static final int SCROLL_VERTICAL_VIEW_SIZE = 30056;
    static final int SCROLL_HORIZONTALLY_SCROLLABLE = 30057;
    static final int SCROLL_VERTICALLY_SCROLLABLE = 30058;
    static final int SELECTION_SELECTION = 30059;
    static final int SELECTION_CAN_SELECT_MULTIPLE = 30060;
    static final int SELECTION_IS_SELECTION_REQUIRED = 30061;
    static final int GRID_ROW_COUNT = 30062;
    static final int GRID_COLUMN_COUNT = 30063;
    static final int GRID_ITEM_ROW = 30064;
    static final int GRID_ITEM_COLUMN = 30065;
    static final int GRID_ITEM_ROW_SPAN = 30066;
    static final int GRID_ITEM_COLUMN_SPAN = 30067;
    static final int GRID_ITEM_CONTAINING_GRID = 30068;
    static final int DOCK_POSITION = 30069;
    static final int EXPAND_COLLAPSE_EXPAND_COLLAPSE_STATE = 30070;
    static final int MULTIPLE_VIEW_CURRENT_VIEW = 30071;
    static final int MULTIPLE_VIEW_SUPPORTED_VIEWS = 30072;
    static final int WINDOW_CAN_MAXIMIZE = 30073;
    static final int WINDOW_CAN_MINIMIZE = 30074;
    static final int WINDOW_VISUAL_STATE = 30075;
    static final int WINDOW_INTERACTION_STATE = 30076;
    static final int WINDOW_IS_MODAL = 30077;
    static final int WINDOW_IS_TOPMOST = 30078;
    static final int SELECTION_ITEM_IS_SELECTED = 30079;
    static final int SELECTION_ITEM_SELECTION_CONTAINER = 30080;
    static final int TABLE_ROW_HEADERS = 30081;
    static final int TABLE_COLUMN_HEADERS = 30082;
    static final int TABLE_ROW_OR_COLUMN_MAJOR = 30083;
    /**
     * {@code RowOrColumnMajor.RowMajor}, what {@code ITableProvider::get_RowOrColumnMajor}
     * answers for a table whose rows are the records. Read off the Windows 11 ARM64 guest on
     * 2026-09-09 from {@code System.Windows.Automation.RowOrColumnMajor}: RowMajor 0,
     * ColumnMajor 1, Indeterminate 2.
     */
    static final int ROW_OR_COLUMN_MAJOR_ROW_MAJOR = 0;
    static final int TABLE_ITEM_ROW_HEADER_ITEMS = 30084;
    static final int TABLE_ITEM_COLUMN_HEADER_ITEMS = 30085;
    static final int TOGGLE_STATE = 30086;
    static final int TRANSFORM_CAN_MOVE = 30087;
    static final int TRANSFORM_CAN_RESIZE = 30088;
    static final int TRANSFORM_CAN_ROTATE = 30089;
    static final int CONTROLLER_FOR = 30104;
    /**
     * The one identifier here that the guest's interop assemblies could not supply: the managed
     * {@code AutomationElementIdentifiers} has no {@code DescribedBy}, so the dump script never
     * sees it. Read 2026-09-09 from the platform's own header as Microsoft documents it,
     * {@code UIAutomationClient.h} on learn.microsoft.com (uiauto-automation-element-propids),
     * which lists {@code UIA_DescribedByPropertyId} beside {@code UIA_ControllerForPropertyId}
     * at 30104 and {@code UIA_FlowsToPropertyId} at 30106 -- both of which the assemblies do
     * carry and agree with. The value's variant type for a provider is
     * {@code VT_UNKNOWN | VT_ARRAY}, the same as ControllerFor.
     */
    static final int DESCRIBED_BY = 30105;
    static final int IS_ITEM_CONTAINER_PATTERN_AVAILABLE = 30108;
    static final int IS_VIRTUALIZED_ITEM_PATTERN_AVAILABLE = 30109;
    static final int IS_SYNCHRONIZED_INPUT_PATTERN_AVAILABLE = 30110;
    static final int LIVE_SETTING = 30135;
    static final int POSITION_IN_SET = 30152;
    static final int SIZE_OF_SET = 30153;
    static final int HEADING_LEVEL = 30173;
    static final int IS_DIALOG = 30174;

    // ---- event ids
    static final int TOOL_TIP_OPENED = 20000;
    static final int TOOL_TIP_CLOSED = 20001;
    static final int STRUCTURE_CHANGED = 20002;
    static final int MENU_OPENED = 20003;
    static final int AUTOMATION_PROPERTY_CHANGED = 20004;
    static final int AUTOMATION_FOCUS_CHANGED = 20005;
    static final int ASYNC_CONTENT_LOADED = 20006;
    static final int MENU_CLOSED = 20007;
    static final int LAYOUT_INVALIDATED = 20008;
    static final int INVOKE_INVOKED = 20009;
    static final int SELECTION_ITEM_ELEMENT_ADDED_TO_SELECTION = 20010;
    static final int SELECTION_ITEM_ELEMENT_REMOVED_FROM_SELECTION = 20011;
    static final int SELECTION_ITEM_ELEMENT_SELECTED = 20012;
    static final int SELECTION_INVALIDATED = 20013;
    static final int TEXT_SELECTION_CHANGED = 20014;
    static final int TEXT_CHANGED = 20015;
    static final int WINDOW_OPENED = 20016;
    static final int WINDOW_CLOSED = 20017;
    static final int SYNCHRONIZED_INPUT_INPUT_REACHED_TARGET = 20020;
    static final int SYNCHRONIZED_INPUT_INPUT_REACHED_OTHER_ELEMENT = 20021;
    static final int SYNCHRONIZED_INPUT_INPUT_DISCARDED = 20022;
    static final int LIVE_REGION_CHANGED = 20024;
    static final int NOTIFICATION = 20035;
    static final int ACTIVE_TEXT_POSITION_CHANGED = 20036;

    // ---- pattern ids
    static final int INVOKE_PATTERN = 10000;
    static final int SELECTION_PATTERN = 10001;
    static final int VALUE_PATTERN = 10002;
    static final int RANGE_VALUE_PATTERN = 10003;
    static final int SCROLL_PATTERN = 10004;
    static final int EXPAND_COLLAPSE_PATTERN = 10005;
    static final int GRID_PATTERN = 10006;
    static final int GRID_ITEM_PATTERN = 10007;
    static final int MULTIPLE_VIEW_PATTERN = 10008;
    static final int WINDOW_PATTERN = 10009;
    static final int SELECTION_ITEM_PATTERN = 10010;
    static final int DOCK_PATTERN = 10011;
    static final int TABLE_PATTERN = 10012;
    static final int TABLE_ITEM_PATTERN = 10013;
    static final int TEXT_PATTERN = 10014;
    static final int TOGGLE_PATTERN = 10015;
    static final int TRANSFORM_PATTERN = 10016;
    static final int SCROLL_ITEM_PATTERN = 10017;
    static final int ITEM_CONTAINER_PATTERN = 10019;
    static final int VIRTUALIZED_ITEM_PATTERN = 10020;
    static final int SYNCHRONIZED_INPUT_PATTERN = 10021;

    // ---- control type ids
    static final int CONTROL_BUTTON = 50000;
    static final int CONTROL_CALENDAR = 50001;
    static final int CONTROL_CHECK_BOX = 50002;
    static final int CONTROL_COMBO_BOX = 50003;
    static final int CONTROL_EDIT = 50004;
    static final int CONTROL_HYPERLINK = 50005;
    static final int CONTROL_IMAGE = 50006;
    static final int CONTROL_LIST_ITEM = 50007;
    static final int CONTROL_LIST = 50008;
    static final int CONTROL_MENU = 50009;
    static final int CONTROL_MENU_BAR = 50010;
    static final int CONTROL_MENU_ITEM = 50011;
    static final int CONTROL_PROGRESS_BAR = 50012;
    static final int CONTROL_RADIO_BUTTON = 50013;
    static final int CONTROL_SCROLL_BAR = 50014;
    static final int CONTROL_SLIDER = 50015;
    static final int CONTROL_SPINNER = 50016;
    static final int CONTROL_STATUS_BAR = 50017;
    static final int CONTROL_TAB = 50018;
    static final int CONTROL_TAB_ITEM = 50019;
    static final int CONTROL_TEXT = 50020;
    static final int CONTROL_TOOL_BAR = 50021;
    static final int CONTROL_TOOL_TIP = 50022;
    static final int CONTROL_TREE = 50023;
    static final int CONTROL_TREE_ITEM = 50024;
    static final int CONTROL_CUSTOM = 50025;
    static final int CONTROL_GROUP = 50026;
    static final int CONTROL_THUMB = 50027;
    static final int CONTROL_DATA_GRID = 50028;
    static final int CONTROL_DATA_ITEM = 50029;
    static final int CONTROL_DOCUMENT = 50030;
    static final int CONTROL_SPLIT_BUTTON = 50031;
    static final int CONTROL_WINDOW = 50032;
    static final int CONTROL_PANE = 50033;
    static final int CONTROL_HEADER = 50034;
    static final int CONTROL_HEADER_ITEM = 50035;
    static final int CONTROL_TABLE = 50036;
    static final int CONTROL_TITLE_BAR = 50037;
    static final int CONTROL_SEPARATOR = 50038;

    // ---- provider-side enumerations
    static final int PROVIDER_OPTIONS_CLIENT_SIDE_PROVIDER = 1;
    static final int PROVIDER_OPTIONS_SERVER_SIDE_PROVIDER = 2;
    static final int PROVIDER_OPTIONS_NON_CLIENT_AREA_PROVIDER = 4;
    static final int PROVIDER_OPTIONS_OVERRIDE_PROVIDER = 8;
    static final int PROVIDER_OPTIONS_PROVIDER_OWNS_SET_FOCUS = 16;
    static final int PROVIDER_OPTIONS_USE_COM_THREADING = 32;
    static final int NAVIGATE_DIRECTION_PARENT = 0;
    static final int NAVIGATE_DIRECTION_NEXT_SIBLING = 1;
    static final int NAVIGATE_DIRECTION_PREVIOUS_SIBLING = 2;
    static final int NAVIGATE_DIRECTION_FIRST_CHILD = 3;
    static final int NAVIGATE_DIRECTION_LAST_CHILD = 4;

    // ---- NOT read here (no Windows SDK on this guest): UiaAppendRuntimeId,

    // ---- not readable from the interop assembly, and each named with what sources it
    /**
     * The marker a runtime id begins with, so that UI Automation replaces it with the host
     * window's own runtime id and the result is unique across processes.
     *
     * <p>From {@code uiautomationcoreapi.h}, which needs a Windows SDK the guest does not carry.
     * What was read on the guest is its <em>behaviour</em>: the spike handed UIA an array
     * beginning with this value and watched it come back with the leading element replaced, which
     * is the contract. A wrong value here would be visible as the fragment failing to be found
     * from its own runtime id.
     */
    static final int APPEND_RUNTIME_ID = 3;

    /**
     * {@code S_OK}, and {@code E_NOINTERFACE} for a {@code QueryInterface} an object cannot answer.
     *
     * <p>Read behaviourally rather than recalled: the guest was asked to query a real COM object
     * for an identifier nobody serves, and it answered {@code 0x80004002} with the out parameter
     * left at zero — which is the second half of the contract and the half a provider forgets.
     */
    static final int S_OK = 0;

    /** @see #S_OK */
    static final int E_NO_INTERFACE = 0x80004002;

    /** {@code UIA_E_ELEMENTNOTAVAILABLE}: what a stale element answers until its refcount drops. */
    static final int E_ELEMENT_NOT_AVAILABLE = 0x80040201;

    /** {@code UIA_E_INVALIDOPERATION}: what a provider answers for a verb it cannot perform. */
    static final int E_INVALID_OPERATION = 0x80131509;
}
