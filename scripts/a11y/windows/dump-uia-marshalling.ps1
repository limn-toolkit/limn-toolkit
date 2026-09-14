# Reads how Microsoft's own managed interop declares the width of every boolean a UI Automation
# provider answers with, off the machine this runs on -- and then measures the bytes that
# declaration actually writes.
#
# ADR 039 §12.3's rule, for a width rather than a number. A provider's IsSelected, IsReadOnly,
# CanSelectMultiple, IsSelectionRequired and HorizontallyScrollable / VerticallyScrollable are
# written through an out pointer, and the pointer's width is a declaration nobody can see from the
# call: a four-byte BOOL written where two bytes were declared is harmless, two bytes written where
# a client reads four leave half the answer as whatever the stack held. dump-uia-typelib.ps1 reads
# the platform's own declaration; this reads the managed one, which is what the .NET Framework's
# providers were built against, in three ways that do not depend on each other:
#
#   1. the MarshalAs descriptor on the getter's return parameter, as reflection reports it
#      (UnmanagedType.Bool is the four-byte BOOL, VariantBool the two-byte VARIANT_BOOL, and no
#      descriptor at all means the interop default for the interface's kind);
#   2. the HasFieldMarshal bit in the parameter's metadata, which is whether ANY descriptor was
#      compiled in -- a second reading of (1) that does not go through the pseudo-attribute;
#   3. a measurement: a managed object implementing the interface is handed out as a COM
#      interface, its getter slot is called from native code with an eight-byte buffer filled with
#      0xAA, and the bytes the marshaller wrote are printed. That is behaviour, of the managed
#      COM-callable wrapper and not of UIAutomationCore, and it settles what "the default" is.
#
# Run it on the Windows guest:
#   powershell -NoProfile -ExecutionPolicy Bypass -File dump-uia-marshalling.ps1

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'reading-environment.ps1')
Write-ReadingEnvironment

$types = [System.Reflection.Assembly]::LoadWithPartialName('UIAutomationTypes')
$provider = [System.Reflection.Assembly]::LoadWithPartialName('UIAutomationProvider')
if (-not $types -or -not $provider) { throw 'the UI Automation interop assemblies are not installed' }

Write-Output "// Read from $(Describe-Assembly $provider)"
Write-Output "// and from  $(Describe-Assembly $types)"
Write-Output ''

function Describe-Marshal($parameter) {
    $hasFieldMarshal = ($parameter.Attributes -band [System.Reflection.ParameterAttributes]::HasFieldMarshal) -ne 0
    $attrs = @($parameter.GetCustomAttributes([System.Runtime.InteropServices.MarshalAsAttribute], $false))
    $described = if ($attrs.Count -gt 0) { "MarshalAs($($attrs[0].Value))" } else { 'no MarshalAs' }
    return "$described, HasFieldMarshal=$hasFieldMarshal, ParameterAttributes=$($parameter.Attributes)"
}

# Every interface in the provider assembly: its COM kind, and every member that carries a bool
# anywhere in its signature -- the six the audit names are among them, and the rest cost a line.
Write-Output '// ---- every provider interface, and every boolean in its members'
$wantedGetters = @(
    'ISelectionItemProvider.IsSelected',
    'IValueProvider.IsReadOnly',
    'IRangeValueProvider.IsReadOnly',
    'ISelectionProvider.CanSelectMultiple',
    'ISelectionProvider.IsSelectionRequired',
    'IScrollProvider.HorizontallyScrollable',
    'IScrollProvider.VerticallyScrollable'
)
$interfaces = @((Get-LoadableTypes $provider) + (Get-LoadableTypes $types) |
    Where-Object { $_.IsInterface -and $_.Namespace -like 'System.Windows.Automation*' } |
    Sort-Object FullName)
foreach ($iface in $interfaces) {
    $kindAttr = @($iface.GetCustomAttributes([System.Runtime.InteropServices.InterfaceTypeAttribute], $false))
    $kind = if ($kindAttr.Count -gt 0) { "$($kindAttr[0].Value)" } else { '(no InterfaceTypeAttribute: the default, InterfaceIsDual)' }
    $isImport = ($iface.Attributes -band [System.Reflection.TypeAttributes]::Import) -ne 0
    Write-Output ("//   {0} {{{1}}} ComInterfaceType={2} ComImport={3} from {4}" -f `
        $iface.FullName, $iface.GUID, $kind, $isImport, $iface.Assembly.GetName().Name)
    foreach ($m in $iface.GetMethods()) {
        $slot = -1
        try { $slot = [System.Runtime.InteropServices.Marshal]::GetComSlotForMethodInfo($m) } catch { }
        $preserveSig = ($m.GetMethodImplementationFlags() -band [System.Reflection.MethodImplAttributes]::PreserveSig) -ne 0
        if ($m.ReturnType -eq [bool]) {
            $name = "$($iface.Name).$($m.Name -replace '^get_', '')"
            $mark = if ($wantedGetters -contains $name) { '  <-- audit' } else { '' }
            Write-Output ("//     slot {0,2} {1} returns Boolean: {2}, PreserveSig={3}{4}" -f `
                $slot, $m.Name, (Describe-Marshal $m.ReturnParameter), $preserveSig, $mark)
        }
        foreach ($p in $m.GetParameters()) {
            $elem = if ($p.ParameterType.HasElementType) { $p.ParameterType.GetElementType() } else { $p.ParameterType }
            if ($elem -eq [bool]) {
                Write-Output ("//     slot {0,2} {1} parameter {2} {3}: {4}" -f `
                    $slot, $m.Name, $p.ParameterType.Name, $p.Name, (Describe-Marshal $p))
            }
        }
    }
}
Write-Output ''

foreach ($name in $wantedGetters) {
    $ifaceName, $prop = $name.Split('.')
    if (-not ($interfaces | Where-Object { $_.Name -eq $ifaceName })) {
        Write-Output "//   $ifaceName : NOT FOUND in either assembly"
    }
}

# The measurement. Each class answers true (or false) and nothing else; the interop assemblies are
# referenced by the paths this machine loaded them from, so the interfaces are the installed ones.
$source = @'
using System;
using System.Globalization;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Automation;
using System.Windows.Automation.Provider;
using CT = System.Runtime.InteropServices.ComTypes;

public class LimnSelectionItem : ISelectionItemProvider
{
    public bool Answer;
    public void Select() { }
    public void AddToSelection() { }
    public void RemoveFromSelection() { }
    public bool IsSelected { get { return Answer; } }
    public IRawElementProviderSimple SelectionContainer { get { return null; } }
}

public class LimnValue : IValueProvider
{
    public bool Answer;
    public void SetValue(string value) { }
    public string Value { get { return ""; } }
    public bool IsReadOnly { get { return Answer; } }
}

public class LimnRangeValue : IRangeValueProvider
{
    public bool Answer;
    public void SetValue(double value) { }
    public double Value { get { return 0; } }
    public bool IsReadOnly { get { return Answer; } }
    public double Maximum { get { return 0; } }
    public double Minimum { get { return 0; } }
    public double LargeChange { get { return 0; } }
    public double SmallChange { get { return 0; } }
}

public class LimnSelection : ISelectionProvider
{
    public bool Answer;
    public IRawElementProviderSimple[] GetSelection() { return new IRawElementProviderSimple[0]; }
    public bool CanSelectMultiple { get { return Answer; } }
    public bool IsSelectionRequired { get { return Answer; } }
}

public class LimnScroll : IScrollProvider
{
    public bool Answer;
    public void Scroll(ScrollAmount horizontalAmount, ScrollAmount verticalAmount) { }
    public void SetScrollPercent(double horizontalPercent, double verticalPercent) { }
    public double HorizontalScrollPercent { get { return 0; } }
    public double VerticalScrollPercent { get { return 0; } }
    public double HorizontalViewSize { get { return 0; } }
    public double VerticalViewSize { get { return 0; } }
    public bool HorizontallyScrollable { get { return Answer; } }
    public bool VerticallyScrollable { get { return Answer; } }
}

public static class LimnWidthProbe
{
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    delegate int Getter(IntPtr self, IntPtr pRetVal);

    // oleaut32's own vtable caller: a second native path into the same slot, which does not go
    // through a managed delegate at all.
    [DllImport("oleaut32.dll", PreserveSig = true)]
    static extern int DispCallFunc(IntPtr pvInstance, IntPtr oVft, CT.CALLCONV cc, short vtReturn,
                                   int cActuals, short[] prgvt, IntPtr[] prgpvarg, IntPtr pvargResult);

    const int BufferBytes = 8;
    const byte Fill = 0xAA;

    static string Bytes(IntPtr buffer)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < BufferBytes; i++) {
            if (i > 0) sb.Append(' ');
            sb.Append(Marshal.ReadByte(buffer, i).ToString("X2", CultureInfo.InvariantCulture));
        }
        return sb.ToString();
    }

    static int Written(IntPtr buffer)
    {
        int n = 0;
        for (int i = 0; i < BufferBytes; i++) if (Marshal.ReadByte(buffer, i) != Fill) n = i + 1;
        return n;
    }

    static void Refill(IntPtr buffer)
    {
        for (int i = 0; i < BufferBytes; i++) Marshal.WriteByte(buffer, i, Fill);
    }

    public static string Probe(object impl, Type iface, string property, bool answer)
    {
        impl.GetType().GetField("Answer").SetValue(impl, answer);
        MethodInfo getter = iface.GetProperty(property).GetGetMethod();
        int slot = Marshal.GetComSlotForMethodInfo(getter);
        IntPtr itf = Marshal.GetComInterfaceForObject(impl, iface);
        IntPtr buffer = Marshal.AllocHGlobal(BufferBytes);
        StringBuilder line = new StringBuilder();
        line.AppendFormat(CultureInfo.InvariantCulture, "{0}.{1} slot {2} answering {3}:", iface.Name, property, slot, answer);
        try {
            IntPtr vtbl = Marshal.ReadIntPtr(itf);
            IntPtr fn = Marshal.ReadIntPtr(vtbl, slot * IntPtr.Size);

            Refill(buffer);
            try {
                Getter call = (Getter) Marshal.GetDelegateForFunctionPointer(fn, typeof(Getter));
                int hr = call(itf, buffer);
                line.AppendFormat(CultureInfo.InvariantCulture, " via delegate hr=0x{0:X8} bytes [{1}] wrote {2};",
                    hr, Bytes(buffer), Written(buffer));
            } catch (Exception e) {
                line.Append(" via delegate FAILED " + e.GetType().Name + ": " + e.Message + ";");
            }

            Refill(buffer);
            IntPtr arg = Marshal.AllocHGlobal(24);
            IntPtr result = Marshal.AllocHGlobal(24);
            try {
                Marshal.GetNativeVariantForObject(buffer.ToInt64(), arg);
                int dhr = DispCallFunc(itf, new IntPtr(slot * IntPtr.Size), CT.CALLCONV.CC_STDCALL,
                    (short) VarEnum.VT_ERROR, 1, new short[] { (short) VarEnum.VT_I8 }, new IntPtr[] { arg }, result);
                object returned = dhr == 0 ? Marshal.GetObjectForNativeVariant(result) : null;
                line.AppendFormat(CultureInfo.InvariantCulture, " via DispCallFunc dhr=0x{0:X8} returned {1} bytes [{2}] wrote {3}",
                    dhr, returned == null ? "null" : string.Format(CultureInfo.InvariantCulture, "0x{0:X8}", returned),
                    Bytes(buffer), Written(buffer));
            } catch (Exception e) {
                line.Append(" via DispCallFunc FAILED " + e.GetType().Name + ": " + e.Message);
            } finally {
                Marshal.FreeHGlobal(arg);
                Marshal.FreeHGlobal(result);
            }
        } finally {
            Marshal.FreeHGlobal(buffer);
            Marshal.Release(itf);
        }
        return line.ToString();
    }
}
'@

Add-Type -TypeDefinition $source -ReferencedAssemblies @($provider.Location, $types.Location)

Write-Output '// ---- measured: the bytes the managed COM-callable wrapper writes through each getter''s out pointer'
Write-Output '//      (buffer pre-filled with AA; "wrote N" is how many leading bytes changed)'
$probes = @(
    @([LimnSelectionItem], [System.Windows.Automation.Provider.ISelectionItemProvider], 'IsSelected'),
    @([LimnValue], [System.Windows.Automation.Provider.IValueProvider], 'IsReadOnly'),
    @([LimnRangeValue], [System.Windows.Automation.Provider.IRangeValueProvider], 'IsReadOnly'),
    @([LimnSelection], [System.Windows.Automation.Provider.ISelectionProvider], 'CanSelectMultiple'),
    @([LimnSelection], [System.Windows.Automation.Provider.ISelectionProvider], 'IsSelectionRequired'),
    @([LimnScroll], [System.Windows.Automation.Provider.IScrollProvider], 'HorizontallyScrollable'),
    @([LimnScroll], [System.Windows.Automation.Provider.IScrollProvider], 'VerticallyScrollable')
)
foreach ($probe in $probes) {
    foreach ($answer in @($true, $false)) {
        $impl = New-Object $probe[0]
        Write-Output ('//   ' + [LimnWidthProbe]::Probe($impl, $probe[1], $probe[2], $answer))
    }
}
