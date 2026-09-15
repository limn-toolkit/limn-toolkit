# Reads what a UI Automation client is told about the tree items of the platform's own tree
# controls: each item's Level, PositionInSet and SizeOfSet, beside how many TreeItem ancestors the
# item has in the raw view, off the machine this runs on.
#
# ADR 039 §12.3 and semantics 6 of the 2026-09-13 pass: a bridge converts the model's one-based
# level to the base the platform uses, and that base is read off native controls before code. The
# id (UIA_LevelPropertyId, 30154) was read from UIAutomationCore.dll's type library on 2026-09-13;
# what a native tree answers for it, and whether its items nest in the raw view the way NVDA
# 2024.4.2 counts levels (TreeItem ancestors), is what this reads.
#
# Two native trees are built in this process, each on its own user-interface thread: a Win32
# tree view (System.Windows.Forms.TreeView, a SysTreeView32) and a WPF TreeView, both three levels
# deep with every branch expanded. A client thread then walks each through two clients: the COM
# client in UIAutomationCore.dll (CUIAutomation, its interop built at run time from the DLL's own
# embedded type library, since the machine need carry no SDK), which is what a screen reader uses,
# and the managed System.Windows.Automation client, for comparison. The clients run in a second
# process (this script again, with -ClientOf naming the two window handles), because a client in the
# process that owns the windows reached the Win32 tree's items as elements answering the Tree's own
# control type and no name; a screen reader is always another process. Both windows close at the end.
#
# It needs a desktop: run it in an interactive session (a task with /IT), not over SSH, which is
# session 0 and has no window station a window can be shown on. It starts no screen reader.
#   powershell -NoProfile -ExecutionPolicy Bypass -File read-native-tree-levels.ps1

param([string]$ClientOf = '')

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'reading-environment.ps1')
if (-not $ClientOf) { Write-ReadingEnvironment }

$references = @()
foreach ($name in @('System.Windows.Forms', 'System.Drawing', 'PresentationFramework', 'PresentationCore',
                    'WindowsBase', 'System.Xaml', 'UIAutomationClient', 'UIAutomationTypes')) {
    $a = [System.Reflection.Assembly]::LoadWithPartialName($name)
    Write-Output "// Read with $(Describe-Assembly $a)"
    $references += $a.Location
}
if (-not $ClientOf) {
    Write-Output "// Session: $([System.Diagnostics.Process]::GetCurrentProcess().SessionId), user interactive: $([System.Environment]::UserInteractive)"
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

public static class LimnNativeTreeLevels {

    [DllImport("oleaut32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
    static extern void LoadTypeLibEx(string file, int regKind, out ITypeLib typeLib);

    class Sink : ITypeLibImporterNotifySink {
        public void ReportEvent(ImporterEventKind kind, int code, string message) { }
        public Assembly ResolveRef(object typeLib) { return null; }
    }

    static readonly StringBuilder Out = new StringBuilder();
    static void Say(string line) { Out.AppendLine("// " + line); }

    public static string Clients(string handles) {
        string[] parts = handles.Split(',');
        IntPtr formTree = new IntPtr(Convert.ToInt64(parts[0], 16));
        IntPtr wpfWindow = new IntPtr(Convert.ToInt64(parts[1], 16));
        Say("client process " + System.Diagnostics.Process.GetCurrentProcess().Id);
        Thread client = new Thread(() => {
            try {
                Say("==== COM client (CUIAutomation, interop built from UIAutomationCore.dll's type library)");
                ComClient com = new ComClient();
                if (formTree != IntPtr.Zero) com.Walk("Win32 tree view (SysTreeView32 handle)", formTree);
                if (wpfWindow != IntPtr.Zero) com.Walk("WPF window", wpfWindow);
            } catch (Exception e) { Say("COM client failed: " + e); }
            try {
                Say("==== managed client (System.Windows.Automation)");
                if (formTree != IntPtr.Zero) ManagedWalk("Win32 tree view (SysTreeView32 handle)", formTree);
                if (wpfWindow != IntPtr.Zero) ManagedWalk("WPF window", wpfWindow);
            } catch (Exception e) { Say("managed client failed: " + e); }
        });
        client.SetApartmentState(ApartmentState.MTA);
        client.Start();
        client.Join();
        return Out.ToString();
    }

    public static string Run(string scriptPath) {
        IntPtr formTree = IntPtr.Zero, wpfWindow = IntPtr.Zero;
        System.Windows.Forms.Form form = null;
        System.Windows.Window window = null;
        ManualResetEvent formUp = new ManualResetEvent(false), wpfUp = new ManualResetEvent(false);

        Thread formThread = new Thread(() => {
            form = new System.Windows.Forms.Form();
            form.Text = "Limn reading: Win32 tree view";
            form.ShowInTaskbar = false;
            form.StartPosition = System.Windows.Forms.FormStartPosition.Manual;
            form.Location = new System.Drawing.Point(0, 0);
            form.Size = new System.Drawing.Size(300, 300);
            var tree = new System.Windows.Forms.TreeView();
            tree.Dock = System.Windows.Forms.DockStyle.Fill;
            var alpha = tree.Nodes.Add("Alpha");
            var alpha1 = alpha.Nodes.Add("Alpha 1");
            alpha1.Nodes.Add("Alpha 1 a");
            alpha1.Nodes.Add("Alpha 1 b");
            alpha.Nodes.Add("Alpha 2");
            var beta = tree.Nodes.Add("Beta");
            beta.Nodes.Add("Beta 1");
            form.Controls.Add(tree);
            form.Shown += (s, e) => { tree.ExpandAll(); formTree = tree.Handle; formUp.Set(); };
            System.Windows.Forms.Application.Run(form);
        });
        formThread.SetApartmentState(ApartmentState.STA);
        formThread.Start();

        Thread wpfThread = new Thread(() => {
            window = new System.Windows.Window();
            window.Title = "Limn reading: WPF tree view";
            window.ShowInTaskbar = false;
            window.Left = 320; window.Top = 0; window.Width = 300; window.Height = 300;
            var tree = new System.Windows.Controls.TreeView();
            var alpha = new System.Windows.Controls.TreeViewItem { Header = "Alpha", IsExpanded = true };
            var alpha1 = new System.Windows.Controls.TreeViewItem { Header = "Alpha 1", IsExpanded = true };
            alpha1.Items.Add(new System.Windows.Controls.TreeViewItem { Header = "Alpha 1 a" });
            alpha1.Items.Add(new System.Windows.Controls.TreeViewItem { Header = "Alpha 1 b" });
            alpha.Items.Add(alpha1);
            alpha.Items.Add(new System.Windows.Controls.TreeViewItem { Header = "Alpha 2" });
            var beta = new System.Windows.Controls.TreeViewItem { Header = "Beta", IsExpanded = true };
            beta.Items.Add(new System.Windows.Controls.TreeViewItem { Header = "Beta 1" });
            tree.Items.Add(alpha);
            tree.Items.Add(beta);
            window.Content = tree;
            window.ContentRendered += (s, e) => {
                wpfWindow = new System.Windows.Interop.WindowInteropHelper(window).Handle;
                wpfUp.Set();
            };
            window.Show();
            System.Windows.Threading.Dispatcher.Run();
        });
        wpfThread.SetApartmentState(ApartmentState.STA);
        wpfThread.Start();

        if (!formUp.WaitOne(30000)) Say("the Win32 tree view never showed");
        if (!wpfUp.WaitOne(30000)) Say("the WPF window never rendered");
        Thread.Sleep(1500);

        // The clients, in another process: this script again, told the two handles.
        var start = new System.Diagnostics.ProcessStartInfo("powershell.exe",
            "-NoProfile -ExecutionPolicy Bypass -File \"" + scriptPath + "\" -ClientOf "
            + formTree.ToInt64().ToString("X") + "," + wpfWindow.ToInt64().ToString("X"));
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

        if (form != null) form.BeginInvoke((Action) (() => form.Close()));
        if (window != null) window.Dispatcher.BeginInvoke((Action) (() => {
            window.Close();
            System.Windows.Threading.Dispatcher.CurrentDispatcher.BeginInvokeShutdown(
                System.Windows.Threading.DispatcherPriority.Background);
        }));
        formThread.Join(10000);
        wpfThread.Join(10000);
        Say("windows closed: Win32 " + !formThread.IsAlive + ", WPF " + !wpfThread.IsAlive);
        return Out.ToString();
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
            // The converter names the namespace after the library, whatever the file is called.
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
        static string Show(object v) { return v == null ? "VT_EMPTY" : v.GetType().Name + " " + v; }
        public void Walk(string what, IntPtr hwnd) {
            Say("-- " + what + " 0x" + hwnd.ToInt64().ToString("X"));
            object root = Call(iAutomation, automation, "ElementFromHandle", hwnd);
            object walker = Call(iAutomation, automation, "get_RawViewWalker");
            Visit(root, walker, 0, 0);
        }
        void Visit(object element, object walker, int depth, int treeItemAncestors) {
            if (depth > 12) return;
            int type = (int) Property(element, 30003);
            // Both through GetCurrentPropertyValue, the call whose answers are being read.
            object nameValue = Property(element, 30005);
            string name = nameValue as string ?? "";
            bool isItem = type == 50024;
            if (isItem || type == 50023) {
                Say(string.Format("{0}{1} (ControlType {7}) '{2}' TreeItem ancestors in the raw view={3} Level(30154)={4} PositionInSet(30152)={5} SizeOfSet(30153)={6}",
                    new string(' ', 2 * depth), isItem ? "TreeItem" : "Tree", name, treeItemAncestors,
                    Show(Property(element, 30154)), Show(Property(element, 30152)), Show(Property(element, 30153)), type));
            }
            object child = Call(iWalker, walker, "GetFirstChildElement", element);
            while (child != null) {
                Visit(child, walker, depth + 1, treeItemAncestors + (isItem ? 1 : 0));
                child = Call(iWalker, walker, "GetNextSiblingElement", child);
            }
        }
    }

    static void ManagedWalk(string what, IntPtr hwnd) {
        Say("-- " + what + " 0x" + hwnd.ToInt64().ToString("X"));
        var level = System.Windows.Automation.AutomationProperty.LookupById(30154);
        var position = System.Windows.Automation.AutomationProperty.LookupById(30152);
        var size = System.Windows.Automation.AutomationProperty.LookupById(30153);
        Say("AutomationProperty.LookupById(30154) = " + (level == null ? "null (the managed client has no Level)" : level.ProgrammaticName));
        var root = System.Windows.Automation.AutomationElement.FromHandle(hwnd);
        ManagedVisit(root, 0, 0, level, position, size);
    }

    static void ManagedVisit(System.Windows.Automation.AutomationElement e, int depth, int ancestors,
                             System.Windows.Automation.AutomationProperty level,
                             System.Windows.Automation.AutomationProperty position,
                             System.Windows.Automation.AutomationProperty size) {
        if (depth > 12) return;
        var type = e.Current.ControlType;
        bool isItem = type == System.Windows.Automation.ControlType.TreeItem;
        if (isItem || type == System.Windows.Automation.ControlType.Tree) {
            Say(string.Format("{0}{1} '{2}' TreeItem ancestors in the raw view={3} Level={4} PositionInSet={5} SizeOfSet={6}",
                new string(' ', 2 * depth), isItem ? "TreeItem" : "Tree", e.Current.Name, ancestors,
                level == null ? "(no id)" : ManagedShow(e.GetCurrentPropertyValue(level)),
                position == null ? "(no id)" : ManagedShow(e.GetCurrentPropertyValue(position)),
                size == null ? "(no id)" : ManagedShow(e.GetCurrentPropertyValue(size))));
        }
        var walker = System.Windows.Automation.TreeWalker.RawViewWalker;
        var child = walker.GetFirstChild(e);
        while (child != null) {
            ManagedVisit(child, depth + 1, ancestors + (isItem ? 1 : 0), level, position, size);
            child = walker.GetNextSibling(child);
        }
    }

    static string ManagedShow(object v) {
        if (v == System.Windows.Automation.AutomationElement.NotSupported) return "NotSupported";
        return v == null ? "null" : v.GetType().Name + " " + v;
    }
}
"@

if ($ClientOf) {
    Write-Output ([LimnNativeTreeLevels]::Clients($ClientOf))
} else {
    Write-Output ([LimnNativeTreeLevels]::Run($PSCommandPath))
}
