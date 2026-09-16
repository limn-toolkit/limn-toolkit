# Reads how the platform's own column headers tell a UI Automation client which way a column is
# sorted, off the machine this runs on: is it a property of the header item (ItemStatus, HelpText,
# FullDescription, AriaProperties, ...), a suffix of its name, or nothing at all?
#
# Decision 36 of the 2026-09-13 pass: a sortable table's header cells publish their sort direction,
# and how each platform carries a direction is read off a native control before a bridge carries
# it. UI Automation has no SortDirection property id in UIAutomationCore.dll's type library (read
# 2026-09-13), so the answer is whatever the platform's own headers do, and this reads it two ways:
#
#   1. As the providers are written: every declared method of the managed column-header automation
#      peers, client-side header proxies and WinForms header accessible objects whose IL mentions a
#      sort, plus their name/status/help/property getters, printed as IL by il-listing.ps1.
#   2. As a client is told: four native headers are built or opened with one column sorted
#      ascending, one descending and one not sorted -- a WPF DataGrid (SortDirection), a WinForms
#      DataGridView (SortGlyphDirection), a Win32 list view in report view (the header item's format
#      flags) and a File Explorer details view (its SortColumns, set through Shell.Application) --
#      and a second process walks each through the COM client in UIAutomationCore.dll (CUIAutomation,
#      its interop converted at run time from the DLL's own type library) and prints, for every
#      header, header item and child of a header (whatever its control type: a DataGridView's column
#      headers are Headers, and File Explorer's may be neither), every property id from 30000 to
#      30200 the element answers with something other than an empty variant or an empty string.
#      Explorer is walked twice, sorted each way, so a carrier shows as the one line that changes.
#
# It needs a desktop: run it in an interactive session (a task with /IT), not over SSH, which is
# session 0. It starts no screen reader. Every window it opens is closed at the end, and the folder
# Explorer shows is a scratch folder under %TEMP% that it removes.
#   powershell -NoProfile -ExecutionPolicy Bypass -File read-native-sort-direction.ps1

param([string]$ClientOf = '')

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'reading-environment.ps1')
if (-not $ClientOf) {
    . (Join-Path $PSScriptRoot 'il-listing.ps1')
    Write-ReadingEnvironment
}

$references = @()
foreach ($name in @('System.Windows.Forms', 'System.Drawing', 'PresentationFramework', 'PresentationCore',
                    'WindowsBase', 'System.Xaml', 'UIAutomationClient', 'UIAutomationTypes')) {
    $a = [System.Reflection.Assembly]::LoadWithPartialName($name)
    if (-not $ClientOf) { Write-Output "// Read with $(Describe-Assembly $a)" }
    $references += $a.Location
}

if (-not $ClientOf) {
    Write-Output "// Session: $([System.Diagnostics.Process]::GetCurrentProcess().SessionId), user interactive: $([System.Environment]::UserInteractive)"
    Write-Output ''

    # ---- 1. the providers, as IL
    Write-Output '// ==== 1. column-header providers: every method that mentions a sort, and the getters a client reads'
    $flags = [System.Reflection.BindingFlags]'Public,NonPublic,Static,Instance,DeclaredOnly'
    $getters = '^(GetNameCore|GetItemStatusCore|GetHelpTextCore|GetPropertyValue|GetElementProperty|GetPatternProvider|GetPattern|get_Name|get_Description|get_Help|get_Value|get_State|get_Role|GetAccessibleObjectForChild)$'
    foreach ($name in @('PresentationFramework', 'UIAutomationClientSideProviders', 'System.Windows.Forms')) {
        $a = $null
        try { $a = [System.Reflection.Assembly]::LoadWithPartialName($name) } catch { }
        if (-not $a) { Write-Output "// $name : not loadable on this machine"; continue }
        Write-Output "// Read from $(Describe-Assembly $a)"
        foreach ($t in @(Get-LoadableTypes $a)) {
            if ($t.FullName -notmatch 'ColumnHeader|SysHeader|HeaderItem') { continue }
            if ($t.FullName -notmatch 'AutomationPeer|AutomationProxies|AccessibleObject') { continue }
            Write-Output "//   == $($t.FullName) : $($t.BaseType)"
            $methods = @()
            try { $methods = @($t.GetMethods($flags)) } catch { continue }
            foreach ($m in $methods) {
                $listing = @(Write-ILListing $m)
                $mentions = ($m.Name -match 'Sort') -or (@($listing | Where-Object { $_ -match 'Sort' }).Count -gt 0)
                if (-not $mentions -and $m.Name -notmatch $getters) { continue }
                Write-Output ("//   {0}::{1}({2}) -> {3}   [{4}]" -f $m.DeclaringType.FullName, $m.Name,
                    ((@($m.GetParameters()) | ForEach-Object { "$($_.ParameterType.Name) $($_.Name)" }) -join ', '),
                    $m.ReturnType.Name, $(if ($mentions) { 'mentions a sort' } else { 'a getter a client reads' }))
                $listing
                Write-Output ''
            }
        }
    }
    Write-Output ''
}

Add-Type -ReferencedAssemblies $references -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;
using System.Threading;

public static class LimnNativeSortDirection {

    [DllImport("oleaut32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
    static extern void LoadTypeLibEx(string file, int regKind, out ITypeLib typeLib);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern IntPtr SendMessage(IntPtr hwnd, int message, IntPtr wParam, ref HeaderItem item);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern IntPtr SendMessage(IntPtr hwnd, int message, IntPtr wParam, IntPtr lParam);

    // HDITEMW, as the Win32 header control takes it. Only mask and fmt are written or read here.
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct HeaderItem {
        public uint mask; public int cxy; public IntPtr pszText; public IntPtr hbm; public int cchTextMax;
        public int fmt; public IntPtr lParam; public int iImage; public int iOrder; public uint type;
        public IntPtr pvFilter; public uint state;
    }

    // The fixture's own settings, from the Win32 common controls' public header (not read on the
    // machine; each is echoed back through HDM_GETITEM below so the reading shows what it set).
    const int LVM_GETHEADER = 0x1000 + 31, HDM_GETITEMW = 0x1200 + 11, HDM_SETITEMW = 0x1200 + 12;
    const uint HDI_FORMAT = 0x0004;
    const int HDF_SORTUP = 0x0400, HDF_SORTDOWN = 0x0200;

    class Sink : ITypeLibImporterNotifySink {
        public void ReportEvent(ImporterEventKind kind, int code, string message) { }
        public Assembly ResolveRef(object typeLib) { return null; }
    }

    public class Row {
        public string Name { get; set; }
        public string Size { get; set; }
        public string Kind { get; set; }
    }

    static readonly StringBuilder Out = new StringBuilder();
    static void Say(string line) { lock (Out) { Out.AppendLine("// " + line); } }

    // ---- the fixtures, in the first process
    public static string Run(string scriptPath) {
        IntPtr wpfWindow = IntPtr.Zero, gridForm = IntPtr.Zero, listForm = IntPtr.Zero;
        System.Windows.Forms.Form grid = null, list = null;
        System.Windows.Window window = null;
        ManualResetEvent wpfUp = new ManualResetEvent(false), gridUp = new ManualResetEvent(false),
                listUp = new ManualResetEvent(false);

        Thread wpfThread = new Thread(() => {
            window = new System.Windows.Window();
            window.Title = "Limn reading: WPF DataGrid";
            window.ShowInTaskbar = false;
            window.Left = 0; window.Top = 0; window.Width = 360; window.Height = 200;
            var dataGrid = new System.Windows.Controls.DataGrid { AutoGenerateColumns = false };
            foreach (string column in new[] { "Name", "Size", "Kind" }) {
                dataGrid.Columns.Add(new System.Windows.Controls.DataGridTextColumn {
                    Header = column, Binding = new System.Windows.Data.Binding(column) });
            }
            dataGrid.Columns[0].SortDirection = System.ComponentModel.ListSortDirection.Ascending;
            dataGrid.Columns[1].SortDirection = System.ComponentModel.ListSortDirection.Descending;
            dataGrid.ItemsSource = new List<Row> {
                new Row { Name = "a", Size = "3", Kind = "x" }, new Row { Name = "b", Size = "2", Kind = "y" } };
            window.Content = dataGrid;
            window.ContentRendered += (s, e) => {
                wpfWindow = new System.Windows.Interop.WindowInteropHelper(window).Handle;
                Say("WPF DataGrid: Name SortDirection=" + dataGrid.Columns[0].SortDirection
                    + ", Size SortDirection=" + dataGrid.Columns[1].SortDirection
                    + ", Kind SortDirection=" + (dataGrid.Columns[2].SortDirection == null ? "null" : dataGrid.Columns[2].SortDirection.ToString()));
                wpfUp.Set();
            };
            window.Show();
            System.Windows.Threading.Dispatcher.Run();
        });
        wpfThread.SetApartmentState(ApartmentState.STA);
        wpfThread.Start();

        Thread gridThread = new Thread(() => {
            grid = new System.Windows.Forms.Form();
            grid.Text = "Limn reading: WinForms DataGridView";
            grid.ShowInTaskbar = false;
            grid.StartPosition = System.Windows.Forms.FormStartPosition.Manual;
            grid.Location = new System.Drawing.Point(380, 0);
            grid.Size = new System.Drawing.Size(360, 200);
            var view = new System.Windows.Forms.DataGridView { Dock = System.Windows.Forms.DockStyle.Fill };
            foreach (string column in new[] { "Name", "Size", "Kind" }) {
                int at = view.Columns.Add(column, column);
                view.Columns[at].SortMode = System.Windows.Forms.DataGridViewColumnSortMode.Programmatic;
            }
            view.Rows.Add("a", "3", "x");
            view.Rows.Add("b", "2", "y");
            grid.Controls.Add(view);
            grid.Shown += (s, e) => {
                view.Columns[0].HeaderCell.SortGlyphDirection = System.Windows.Forms.SortOrder.Ascending;
                view.Columns[1].HeaderCell.SortGlyphDirection = System.Windows.Forms.SortOrder.Descending;
                Say("WinForms DataGridView: Name SortGlyphDirection=" + view.Columns[0].HeaderCell.SortGlyphDirection
                    + ", Size SortGlyphDirection=" + view.Columns[1].HeaderCell.SortGlyphDirection
                    + ", Kind SortGlyphDirection=" + view.Columns[2].HeaderCell.SortGlyphDirection);
                gridForm = grid.Handle;
                gridUp.Set();
            };
            System.Windows.Forms.Application.Run(grid);
        });
        gridThread.SetApartmentState(ApartmentState.STA);
        gridThread.Start();

        Thread listThread = new Thread(() => {
            list = new System.Windows.Forms.Form();
            list.Text = "Limn reading: Win32 list view";
            list.ShowInTaskbar = false;
            list.StartPosition = System.Windows.Forms.FormStartPosition.Manual;
            list.Location = new System.Drawing.Point(760, 0);
            list.Size = new System.Drawing.Size(360, 200);
            var view = new System.Windows.Forms.ListView {
                Dock = System.Windows.Forms.DockStyle.Fill, View = System.Windows.Forms.View.Details };
            foreach (string column in new[] { "Name", "Size", "Kind" }) view.Columns.Add(column, 100);
            view.Items.Add(new System.Windows.Forms.ListViewItem(new[] { "a", "3", "x" }));
            view.Items.Add(new System.Windows.Forms.ListViewItem(new[] { "b", "2", "y" }));
            list.Controls.Add(view);
            list.Shown += (s, e) => {
                IntPtr header = SendMessage(view.Handle, LVM_GETHEADER, IntPtr.Zero, IntPtr.Zero);
                int[] flags = { HDF_SORTUP, HDF_SORTDOWN, 0 };
                var said = new StringBuilder("Win32 list view header 0x" + header.ToInt64().ToString("X") + ":");
                for (int i = 0; i < 3; i++) {
                    var item = new HeaderItem { mask = HDI_FORMAT };
                    SendMessage(header, HDM_GETITEMW, new IntPtr(i), ref item);
                    int before = item.fmt;
                    item.fmt = (item.fmt & ~(HDF_SORTUP | HDF_SORTDOWN)) | flags[i];
                    item.mask = HDI_FORMAT;
                    SendMessage(header, HDM_SETITEMW, new IntPtr(i), ref item);
                    var back = new HeaderItem { mask = HDI_FORMAT };
                    SendMessage(header, HDM_GETITEMW, new IntPtr(i), ref back);
                    said.Append(string.Format(" column {0} fmt 0x{1:X4} -> 0x{2:X4};", i, before, back.fmt));
                }
                Say(said.ToString());
                listForm = list.Handle;
                listUp.Set();
            };
            System.Windows.Forms.Application.Run(list);
        });
        listThread.SetApartmentState(ApartmentState.STA);
        listThread.Start();

        if (!wpfUp.WaitOne(30000)) Say("the WPF window never rendered");
        if (!gridUp.WaitOne(30000)) Say("the DataGridView never showed");
        if (!listUp.WaitOne(30000)) Say("the list view never showed");
        Thread.Sleep(1500);

        var start = new System.Diagnostics.ProcessStartInfo("powershell.exe",
            "-NoProfile -ExecutionPolicy Bypass -File \"" + scriptPath + "\" -ClientOf "
            + wpfWindow.ToInt64().ToString("X") + "," + gridForm.ToInt64().ToString("X") + ","
            + listForm.ToInt64().ToString("X"));
        start.UseShellExecute = false;
        start.RedirectStandardOutput = true;
        start.RedirectStandardError = true;
        using (var child = System.Diagnostics.Process.Start(start)) {
            string output = child.StandardOutput.ReadToEnd();
            string errors = child.StandardError.ReadToEnd();
            child.WaitForExit();
            Say("client process " + child.Id + " exited " + child.ExitCode);
            Out.Append(output);
            if (errors.Length > 0) Say("client stderr: " + errors.Replace("\r\n", " | "));
        }

        if (window != null) window.Dispatcher.BeginInvoke((Action) (() => {
            window.Close();
            System.Windows.Threading.Dispatcher.CurrentDispatcher.BeginInvokeShutdown(
                System.Windows.Threading.DispatcherPriority.Background);
        }));
        if (grid != null) grid.BeginInvoke((Action) (() => grid.Close()));
        if (list != null) list.BeginInvoke((Action) (() => list.Close()));
        wpfThread.Join(10000);
        gridThread.Join(10000);
        listThread.Join(10000);
        Say("windows closed: WPF " + !wpfThread.IsAlive + ", DataGridView " + !gridThread.IsAlive
            + ", list view " + !listThread.IsAlive);
        return Out.ToString();
    }

    // ---- the client, in the second process
    public static string Clients(string handles) {
        string[] parts = handles.Split(',');
        Say("client process " + System.Diagnostics.Process.GetCurrentProcess().Id);
        Thread client = new Thread(() => {
            ComClient com = null;
            try {
                com = new ComClient();
                string[] what = { "WPF DataGrid window", "WinForms DataGridView form", "Win32 list view form" };
                for (int i = 0; i < 3; i++) {
                    IntPtr hwnd = new IntPtr(Convert.ToInt64(parts[i], 16));
                    if (hwnd != IntPtr.Zero) com.Walk(what[i], hwnd);
                }
            } catch (Exception e) { Say("COM client failed: " + e); }
            try { if (com != null) Explorer(com); } catch (Exception e) { Say("Explorer walk failed: " + e); }
        });
        client.SetApartmentState(ApartmentState.STA);
        client.Start();
        client.Join();
        return Out.ToString();
    }

    static void Explorer(ComClient com) {
        string folder = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "limn-sort-reading");
        System.IO.Directory.CreateDirectory(folder);
        foreach (string file in new[] { "a.txt", "b.txt", "c.txt" }) {
            System.IO.File.WriteAllText(System.IO.Path.Combine(folder, file), file);
        }
        Type shellType = Type.GetTypeFromProgID("Shell.Application");
        object shell = Activator.CreateInstance(shellType);
        System.Diagnostics.Process.Start("explorer.exe", "\"" + folder + "\"");
        object found = null;
        DateTime deadline = DateTime.UtcNow.AddSeconds(30);
        while (found == null && DateTime.UtcNow < deadline) {
            Thread.Sleep(500);
            object windows = Invoke(shell, "Windows");
            int count = (int) Get(windows, "Count");
            for (int i = 0; i < count; i++) {
                object w = Invoke(windows, "Item", i);
                if (w == null) continue;
                string url = Get(w, "LocationURL") as string ?? "";
                if (url.EndsWith("limn-sort-reading", StringComparison.OrdinalIgnoreCase)) { found = w; break; }
            }
        }
        if (found == null) { Say("-- File Explorer: no window showed the scratch folder in 30 s"); return; }
        try {
            object document = Get(found, "Document");
            Set(document, "CurrentViewMode", 4);
            IntPtr hwnd = new IntPtr(Convert.ToInt64(Get(found, "HWND")));
            Say("-- File Explorer " + Get(found, "FullName") + ", folder view mode " + Get(document, "CurrentViewMode"));
            foreach (string sort in new[] { "prop:System.ItemNameDisplay;", "prop:-System.ItemNameDisplay;" }) {
                Set(document, "SortColumns", sort);
                Thread.Sleep(1500);
                Say("---- SortColumns set to '" + sort + "', read back '" + Get(document, "SortColumns") + "'");
                com.Walk("File Explorer window", hwnd);
            }
        } finally {
            try { Invoke(found, "Quit"); } catch (Exception e) { Say("Explorer Quit failed: " + e.Message); }
            Thread.Sleep(1000);
            try { System.IO.Directory.Delete(folder, true); Say("scratch folder removed"); }
            catch (Exception e) { Say("scratch folder not removed: " + e.Message); }
        }
    }

    static object Invoke(object target, string name, params object[] args) {
        return target.GetType().InvokeMember(name, BindingFlags.InvokeMethod, null, target, args);
    }
    static object Get(object target, string name) {
        return target.GetType().InvokeMember(name, BindingFlags.GetProperty, null, target, null);
    }
    static void Set(object target, string name, object value) {
        target.GetType().InvokeMember(name, BindingFlags.SetProperty, null, target, new[] { value });
    }

    class ComClient {
        readonly object automation;
        readonly Type iAutomation, iElement, iWalker;
        public ComClient() {
            string dll = Environment.ExpandEnvironmentVariables(@"%SystemRoot%\System32\UIAutomationCore.dll");
            ITypeLib typeLib;
            LoadTypeLibEx(dll, 2 /* REGKIND_NONE */, out typeLib);
            Assembly built = (Assembly) new TypeLibConverter().ConvertTypeLibToAssembly(
                typeLib, "LimnUiaClientInterop.dll", TypeLibImporterFlags.None, new Sink(), null, null, null, null);
            iAutomation = TypeNamed(built, "IUIAutomation");
            iElement = TypeNamed(built, "IUIAutomationElement");
            iWalker = TypeNamed(built, "IUIAutomationTreeWalker");
            automation = Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("ff48dba4-60ef-4201-aa87-54103eef594e")));
            Say("interop from " + dll + ", library " + built.GetName().Name);
        }
        static Type TypeNamed(Assembly built, string name) {
            foreach (Type t in built.GetTypes()) if (t.Name == name) return t;
            throw new InvalidOperationException("the converted library has no " + name);
        }
        object Call(Type iface, object target, string name, params object[] args) {
            return iface.GetMethod(name).Invoke(target, args);
        }
        object Property(object element, int id) {
            return Call(iElement, element, "GetCurrentPropertyValue", id);
        }
        static string Show(object v) {
            if (v == null) return "VT_EMPTY";
            Array array = v as Array;
            if (array != null) {
                var parts = new List<string>();
                foreach (object o in array) parts.Add(o == null ? "null" : o.ToString());
                return v.GetType().Name + " [" + string.Join(", ", parts) + "]";
            }
            return v.GetType().Name + " '" + v + "'";
        }
        public void Walk(string what, IntPtr hwnd) {
            Say("-- " + what + " 0x" + hwnd.ToInt64().ToString("X"));
            object root = Call(iAutomation, automation, "ElementFromHandle", hwnd);
            object walker = Call(iAutomation, automation, "get_RawViewWalker");
            Visit(root, walker, 0, false);
        }
        void Visit(object element, object walker, int depth, bool underHeader) {
            if (depth > 40) return;
            int type = (int) Property(element, 30003);
            // A Header (50034), a HeaderItem (50035), and whatever a Header holds whatever its
            // control type (a DataGridView's column headers are Headers; Explorer's are the
            // children of its Header): every property id the element answers.
            if (type == 50034 || type == 50035 || underHeader) {
                string pad = new string(' ', 2 * Math.Min(depth, 10));
                Say(string.Format("{0}element (ControlType {1}) '{2}' class '{3}'{4}", pad, type,
                    Property(element, 30005), Property(element, 30012), underHeader ? " under a Header" : ""));
                for (int id = 30000; id <= 30200; id++) {
                    object v;
                    try { v = Property(element, id); } catch (Exception) { continue; }
                    if (v == null) continue;
                    string s = v as string;
                    if (s != null && s.Length == 0) continue;
                    Say(string.Format("{0}    {1} = {2}", pad, id, Show(v)));
                }
            }
            object child = Call(iWalker, walker, "GetFirstChildElement", element);
            while (child != null) {
                Visit(child, walker, depth + 1, type == 50034);
                child = Call(iWalker, walker, "GetNextSiblingElement", child);
            }
        }
    }
}
"@

if ($ClientOf) {
    Write-Output ([LimnNativeSortDirection]::Clients($ClientOf))
} else {
    Write-Output ([LimnNativeSortDirection]::Run($PSCommandPath))
}
