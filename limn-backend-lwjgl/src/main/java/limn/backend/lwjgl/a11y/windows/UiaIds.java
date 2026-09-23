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
 * on the guest rather than trusting this table when a platform version moves, because a wrong
 * number here does not fail: a property id we guessed wrong is a property the client silently never
 * sees, and a control type we guessed wrong announces a slider as a menu item in a voice the person
 * relying on it cannot check against the screen. The first reading already caught one such trap —
 * {@code ServerSideProvider} is 2, and 1 is {@code ClientSideProvider}.
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
    /**
     * {@code UIA_LevelPropertyId}: how deep an item stands in an outline, one-based.
     *
     * <p>The id was read on the Windows 11 ARM64 guest (10.0.26200,
     * UIAutomationCore.dll 7.2.26100.9278) on 2026-09-13: 30154 as {@code UIA_LevelPropertyId} in
     * UIAutomationCore.dll's embedded {@code UIAutomationClient} type library, module
     * {@code UIA_PropertyIds} ({@code scripts/a11y/windows/dump-uia-typelib.ps1},
     * readings/windows-dump-uia-typelib.txt), and as the internal managed constant
     * {@code MS.Internal.Automation.AutomationIdentifierConstants+Properties.Level}
     * ({@code dump-uia-constants.ps1}, readings/windows-dump-uia-constants.txt); no public managed
     * {@code LevelProperty} exists. The base was read on the same guest
     * (UIAutomationCore.dll 7.2.26100.9457) on 2026-09-15 off native trees by
     * {@code read-native-tree-levels.ps1} (readings/windows-read-native-tree-levels.txt): a Win32
     * tree view answers 1 for its root items, 2 and 3 below; a WPF 4.8 tree answers 0 (nothing)
     * everywhere. So the model's one-based level passes through unchanged, and a zero is not
     * answered.
     */
    static final int LEVEL = 30154;
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

    /**
     * {@code ScrollAmount}, the two arguments of {@code IScrollProvider::Scroll}. Read on the
     * Windows 11 ARM64 guest (10.0.26200, UIAutomationCore.dll 7.2.26100.9278) on 2026-09-13 by
     * {@code scripts/a11y/windows/dump-uia-constants.ps1} (readings/windows-dump-uia-constants.txt:
     * {@code System.Windows.Automation.ScrollAmount} in UIAutomationTypes 4.8.9347) and by
     * {@code dump-uia-typelib.ps1} (readings/windows-dump-uia-typelib.txt: {@code ScrollAmount_*}
     * in the type libraries {@code UIAutomationClient} and {@code UIA}), which agree.
     */
    static final int SCROLL_AMOUNT_LARGE_DECREMENT = 0;
    /** @see #SCROLL_AMOUNT_LARGE_DECREMENT */
    static final int SCROLL_AMOUNT_SMALL_DECREMENT = 1;
    /** @see #SCROLL_AMOUNT_LARGE_DECREMENT */
    static final int SCROLL_AMOUNT_NO_AMOUNT = 2;
    /** @see #SCROLL_AMOUNT_LARGE_DECREMENT */
    static final int SCROLL_AMOUNT_LARGE_INCREMENT = 3;
    /** @see #SCROLL_AMOUNT_LARGE_DECREMENT */
    static final int SCROLL_AMOUNT_SMALL_INCREMENT = 4;

    /**
     * {@code UIA_ScrollPatternNoScroll}: the percent a scroll getter answers for an axis that
     * cannot scroll, and the percent {@code SetScrollPercent} is given for an axis to leave alone.
     * Read on the Windows 11 ARM64 guest on 2026-09-13: {@code -1} ({@code VT_R8}) in the
     * {@code UIA} type library's {@code UIA_OtherConstants} (readings/windows-dump-uia-typelib.txt)
     * and {@code ScrollPatternIdentifiers.NoScroll} = -1 in UIAutomationTypes
     * (readings/windows-dump-uia-constants.txt). How the platform's own provider uses it, both
     * ways, was read on 2026-09-15 by {@code dump-uia-provider-conventions.ps1}
     * (readings/windows-dump-uia-provider-conventions.txt §1, {@code ScrollViewerAutomationPeer}).
     */
    static final double SCROLL_NO_SCROLL = -1.0;

    /**
     * {@code StructureChangeType}, what {@code UiaRaiseStructureChangedEvent} says moved. Read on
     * the Windows 11 ARM64 guest (10.0.26200, UIAutomationCore.dll 7.2.26100.9278) on 2026-09-13 by
     * {@code dump-uia-constants.ps1} (readings/windows-dump-uia-constants.txt:
     * {@code System.Windows.Automation.StructureChangeType}, UIAutomationTypes 4.8.9347) and by
     * {@code dump-uia-typelib.ps1} (readings/windows-dump-uia-typelib.txt:
     * {@code StructureChangeType_*} in the UIAutomationClient, UIA, UIAutomationClientPriv and
     * UIAutomationBlockingCoreLib type libraries), which agree.
     */
    static final int STRUCTURE_CHANGE_CHILD_ADDED = 0;
    /** @see #STRUCTURE_CHANGE_CHILD_ADDED */
    static final int STRUCTURE_CHANGE_CHILD_REMOVED = 1;
    /** @see #STRUCTURE_CHANGE_CHILD_ADDED */
    static final int STRUCTURE_CHANGE_CHILDREN_INVALIDATED = 2;
    /** @see #STRUCTURE_CHANGE_CHILD_ADDED */
    static final int STRUCTURE_CHANGE_CHILDREN_BULK_ADDED = 3;
    /** @see #STRUCTURE_CHANGE_CHILD_ADDED */
    static final int STRUCTURE_CHANGE_CHILDREN_BULK_REMOVED = 4;
    /** @see #STRUCTURE_CHANGE_CHILD_ADDED */
    static final int STRUCTURE_CHANGE_CHILDREN_REORDERED = 5;

    /**
     * {@code NotificationKind}, the second argument of {@code UiaRaiseNotificationEvent}. Read on
     * the same guest on 2026-09-13: the managed {@code AutomationNotificationKind} in
     * UIAutomationTypes (readings/windows-dump-uia-constants.txt) and {@code NotificationKind_*} in
     * the UIAutomationClient type library (readings/windows-dump-uia-typelib.txt), which agree.
     */
    static final int NOTIFICATION_KIND_ITEM_ADDED = 0;
    /** @see #NOTIFICATION_KIND_ITEM_ADDED */
    static final int NOTIFICATION_KIND_ITEM_REMOVED = 1;
    /** @see #NOTIFICATION_KIND_ITEM_ADDED */
    static final int NOTIFICATION_KIND_ACTION_COMPLETED = 2;
    /** @see #NOTIFICATION_KIND_ITEM_ADDED */
    static final int NOTIFICATION_KIND_ACTION_ABORTED = 3;
    /** @see #NOTIFICATION_KIND_ITEM_ADDED */
    static final int NOTIFICATION_KIND_OTHER = 4;

    /**
     * {@code NotificationProcessing}, the third argument of {@code UiaRaiseNotificationEvent}. Read
     * on the same guest on 2026-09-13: the managed {@code AutomationNotificationProcessing}
     * carries 0 to 4 and the UIAutomationClient type library 0 to 5, adding
     * {@code ImportantCurrentThenMostRecent} (readings/windows-dump-uia-constants.txt,
     * -typelib.txt).
     */
    static final int NOTIFICATION_PROCESSING_IMPORTANT_ALL = 0;
    /** @see #NOTIFICATION_PROCESSING_IMPORTANT_ALL */
    static final int NOTIFICATION_PROCESSING_IMPORTANT_MOST_RECENT = 1;
    /** @see #NOTIFICATION_PROCESSING_IMPORTANT_ALL */
    static final int NOTIFICATION_PROCESSING_ALL = 2;
    /** @see #NOTIFICATION_PROCESSING_IMPORTANT_ALL */
    static final int NOTIFICATION_PROCESSING_MOST_RECENT = 3;
    /** @see #NOTIFICATION_PROCESSING_IMPORTANT_ALL */
    static final int NOTIFICATION_PROCESSING_CURRENT_THEN_MOST_RECENT = 4;
    /** @see #NOTIFICATION_PROCESSING_IMPORTANT_ALL */
    static final int NOTIFICATION_PROCESSING_IMPORTANT_CURRENT_THEN_MOST_RECENT = 5;

    // ---- read from the managed side, not from the interop assembly's identifier tables; each
    // names its reading. The header spellings (UiaAppendRuntimeId, UIA_E_ELEMENTNOTAVAILABLE,
    // UIA_E_INVALIDOPERATION) need a Windows SDK the guest does not carry and are NOT read.
    /**
     * The marker a runtime id begins with, so that UI Automation replaces it with the host
     * window's own runtime id and the result is unique across processes.
     *
     * <p>Read on the Windows 11 ARM64 guest (10.0.26200, UIAutomationCore.dll 7.2.26100.9278)
     * on 2026-09-13 by {@code scripts/a11y/windows/dump-uia-constants.ps1}
     * (readings/windows-dump-uia-constants.txt): the public const
     * {@code System.Windows.Automation.Provider.AutomationInteropProvider.AppendRuntimeId} is 3 in
     * UIAutomationProvider. The header spelling {@code UiaAppendRuntimeId} is not read. Its
     * <em>behaviour</em> was read before that, in the spike: UIA was handed an array beginning with
     * this value and it came back with the leading element replaced.
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

    /**
     * What a pattern getter declared {@code BOOL*} writes: four bytes, {@code 1} for true and
     * {@code 0} for false. Not a {@code VARIANT_BOOL}, which is two bytes with true {@code -1}
     * ({@link UiaVariant#TRUE}) and belongs inside a {@code VARIANT} only.
     *
     * <p>Read on the Windows 11 ARM64 guest (UIAutomationCore.dll 7.2.26100.9278, .NET
     * Framework 4.8 release 533509) on 2026-09-13 by
     * {@code scripts/a11y/windows/dump-uia-marshalling.ps1}
     * (readings/windows-dump-uia-marshalling.txt): every boolean getter of the provider interfaces
     * -- {@code ISelectionItemProvider.get_IsSelected},
     * {@code IValueProvider}/{@code IRangeValueProvider.get_IsReadOnly},
     * {@code ISelectionProvider.get_CanSelectMultiple}/{@code get_IsSelectionRequired},
     * {@code IScrollProvider.get_Horizontally}/{@code VerticallyScrollable} -- is
     * {@code MarshalAs(UnmanagedType.Bool)}, and the managed provider wrote {@code 01 00 00 00} for
     * true and {@code 00 00 00 00} for false into a buffer pre-filled with {@code AA}, through both
     * a delegate and {@code DispCallFunc}. Before 2026-09-15 this bridge wrote the two
     * {@code VARIANT_BOOL} bytes there, leaving the upper half to whatever the caller's slot held.
     */
    static final int BOOL_TRUE = 1;

    /** @see #BOOL_TRUE */
    static final int BOOL_FALSE = 0;

    /**
     * How many members may enter and leave a container's selection in one change before a
     * provider stops raising {@code ElementAddedToSelection}/{@code ElementRemovedFromSelection}
     * per member and raises one {@code Selection_Invalidated} on the container: more than this is
     * bulk, this many is still per member.
     *
     * <p>Read on the Windows 11 ARM64 guest (UIAutomationCore.dll 7.2.26100.9457) on 2026-09-15 by
     * {@code scripts/a11y/windows/dump-uia-invalidate-limits.ps1}
     * (readings/windows-dump-uia-invalidate-limits.txt):
     * {@code System.Windows.Automation.Provider.AutomationInteropProvider.InvalidateLimit} is the
     * public const 20 in UIAutomationProvider 4.8.9347; and the platform's own provider uses it so,
     * decoded from the IL of {@code SelectorAutomationPeer::RaiseSelectionEvents}
     * (PresentationFramework) the script prints:
     * {@code ldloc added; ldloc removed; add; ldc.i4.s 20; ble.s} to the per-member loops, else
     * {@code SelectionPatternOnInvalidated} on the container.
     */
    static final int INVALIDATE_LIMIT = 20;

    /**
     * The same threshold for a container of items: more than this many children entering and
     * leaving an items container is one bulk structure change.
     *
     * <p>Read on the Windows 11 ARM64 guest on 2026-09-13
     * ({@code System.Windows.Automation.Provider.AutomationInteropProvider.ItemsInvalidateLimit} =
     * 5, UIAutomationProvider; readings/windows-dump-uia-constants.txt) and 2026-09-15, how the
     * platform's own providers use the two, decoded as IL by
     * {@code dump-uia-provider-conventions.ps1} (readings/windows-dump-uia-provider-conventions.txt
     * §3): {@code AutomationPeer.UpdateChildren} passes 20 and
     * {@code ItemsControlAutomationPeer}/{@code TreeViewItemAutomationPeer.UpdateChildren} pass 5
     * to {@code UpdateChildrenInternal}, which raises one bulk change when added + removed is more
     * than the limit.
     */
    static final int ITEMS_INVALIDATE_LIMIT = 5;

    /**
     * {@code UIA_E_ELEMENTNOTAVAILABLE}: what a stale element answers until its refcount drops.
     *
     * <p>Read on the Windows 11 ARM64 guest (10.0.26200, UIAutomationCore.dll 7.2.26100.9278)
     * on 2026-09-13 by {@code scripts/a11y/windows/dump-uia-hresults.ps1}
     * (readings/windows-dump-uia-hresults.txt): the internal constants
     * {@code MS.Internal.Automation.UiaCoreTypesApi.UIA_E_ELEMENTNOTAVAILABLE},
     * {@code UiaCoreProviderApi}'s and {@code UiaCoreApi}'s of the same name, all 0x80040201 in
     * the 4.8.9347 assemblies; the HResult of {@code ElementNotAvailableException}; and, measured,
     * what a managed provider's COM wrapper returns when its verb throws that exception.
     */
    static final int E_ELEMENT_NOT_AVAILABLE = 0x80040201;

    /**
     * What a provider answers for a verb it cannot perform: the HRESULT of the managed
     * {@code System.InvalidOperationException}, which is what a managed provider returns when its
     * verb throws one.
     *
     * <p>Read on the Windows 11 ARM64 guest (10.0.26200, UIAutomationCore.dll 7.2.26100.9278)
     * on 2026-09-13 by {@code scripts/a11y/windows/dump-uia-hresults.ps1}
     * (readings/windows-dump-uia-hresults.txt): {@code new InvalidOperationException().HResult}
     * is 0x80131509, mscorlib (4.8.9345) names it {@code System.__HResults.COR_E_INVALIDOPERATION},
     * {@code Marshal.GetExceptionForHR} maps it back to {@code InvalidOperationException}, and a
     * managed {@code IInvokeProvider.Invoke} throwing that exception returned 0x80131509 through
     * its COM wrapper. <b>The header spelling {@code UIA_E_INVALIDOPERATION} is not read</b>: none
     * of the six assemblies searched carries a constant by that name, and no type library any
     * HRESULT (readings/windows-summary.md §2, §5), so the name is unverified and the number is the
     * managed one.
     */
    static final int E_INVALID_OPERATION = 0x80131509;

    /**
     * {@code UIA_E_ELEMENTNOTENABLED}: what a provider answers for a verb or a setter on an element
     * that is not enabled, before it looks at anything else.
     *
     * <p>Read on the Windows 11 ARM64 guest (10.0.26200, UIAutomationCore.dll 7.2.26100.9278)
     * on 2026-09-13 by {@code scripts/a11y/windows/dump-uia-hresults.ps1}
     * (readings/windows-dump-uia-hresults.txt): the internal constants
     * {@code MS.Internal.Automation.UiaCoreTypesApi.UIA_E_ELEMENTNOTENABLED} and
     * {@code UiaCoreApi.UIA_E_ELEMENTNOTENABLED}, both 0x80040200 in the 4.8.9347 assemblies; the
     * HResult of {@code ElementNotEnabledException}; and, measured, what a managed
     * {@code IInvokeProvider.Invoke} throwing that exception returned through its COM wrapper. The
     * header spelling is not read (no SDK on the guest); the name here is the managed constant's.
     * Where the platform's own provider answers it first, before any other refusal, was read
     * on 2026-09-15 (readings/windows-dump-uia-provider-conventions.txt §1:
     * {@code ScrollViewerAutomationPeer}'s {@code Scroll} and {@code SetScrollPercent} both begin
     * {@code call AutomationPeer::IsEnabled(); brtrue; newobj ElementNotEnabledException; throw}),
     * and for {@code SetFocus} and {@code ScrollIntoView} the same day, where only the Win32
     * controls' client-side proxies answer it first and WPF checks nothing
     * (readings/windows-dump-uia-focus-and-scroll-item.txt; {@link UiaPatternProviders#refusal}).
     */
    static final int E_ELEMENT_NOT_ENABLED = 0x80040200;

    /**
     * What a provider answers for an argument outside the range the call accepts: the HRESULT of
     * the managed {@code System.ArgumentOutOfRangeException}, {@code SetScrollPercent}'s answer to
     * a percent outside 0..100 that is not {@link #SCROLL_NO_SCROLL}.
     *
     * <p>Read on the Windows 11 ARM64 guest (10.0.26200, UIAutomationCore.dll 7.2.26100.9457)
     * on 2026-09-15 by {@code scripts/a11y/windows/dump-uia-provider-conventions.ps1}
     * (readings/windows-dump-uia-provider-conventions.txt §2:
     * {@code new ArgumentOutOfRangeException().HResult} = 0x80131502 from mscorlib; §1:
     * {@code ScrollViewerAutomationPeer.SetScrollPercent} tests {@code blt 0} and
     * {@code ble.un 100} and throws {@code ArgumentOutOfRangeException}). No UIA header name is
     * claimed for it: the number is the managed one a managed provider returns.
     */
    static final int E_ARGUMENT_OUT_OF_RANGE = 0x80131502;
}
