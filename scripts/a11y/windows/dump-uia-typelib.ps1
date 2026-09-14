# Reads the type library UIAutomationCore.dll carries inside itself, off the machine this runs on:
# its module constants, its enumerations, and the declared type of every parameter of the
# interfaces a width question hangs on.
#
# ADR 039 §12.3's rule, for what the managed interop assemblies cannot answer. Those assemblies
# stopped growing with the .NET Framework, and UI Automation did not: an identifier added since
# (a tree row's level) is in no managed identifiers type, and uiautomationclient.h is in an SDK
# this guest does not have. But the DLL embeds the type library that header was generated beside,
# and oleaut32 reads it without an SDK -- so the constant comes from the platform's own binary
# rather than from a page recalled.
#
# The widths matter as much as the numbers. A property getter declared BOOL writes four bytes; one
# declared VARIANT_BOOL writes two and leaves the other two as whatever was there. Neither fails,
# and the wrong one reads as "true" or "false" at random. The type library records which the
# interface was declared with (VT_I4 / VT_INT for BOOL, VT_BOOL for VARIANT_BOOL), so that is
# printed per parameter rather than assumed.
#
# Nothing is registered: the library is loaded with REGKIND_NONE, and every TypeLib registration
# naming UIAutomationCore.dll is listed before the first load and again at the end, so a
# registration would be a visible difference in the output.
#
# Run it on the Windows guest:
#   powershell -NoProfile -ExecutionPolicy Bypass -File dump-uia-typelib.ps1 [-AllMembers]
#
# -AllMembers prints every member of every interface; by default only the interfaces named below
# (and every *Provider interface, if the library has any) are printed member by member.

param([switch]$AllMembers)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'reading-environment.ps1')
Write-ReadingEnvironment

$dll = Join-Path $env:SystemRoot 'System32\UIAutomationCore.dll'
if (-not (Test-Path $dll)) { throw "$dll is not on this machine" }

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Runtime.InteropServices;
// Aliased, not imported: System.Runtime.InteropServices still carries the obsolete copies of
// these structures under the same names, and the compiler refuses to guess between them.
using CT = System.Runtime.InteropServices.ComTypes;
using System.Text;

public static class LimnTypeLibReader
{
    // REGKIND_NONE: load without registering. The value is oleauto.h's REGKIND enumeration and is
    // plumbing for this script, not a constant any bridge compiles; the registry check around the
    // call is what shows it did what it says.
    const int REGKIND_NONE = 2;

    [DllImport("oleaut32.dll", CharSet = CharSet.Unicode, PreserveSig = true)]
    static extern int LoadTypeLibEx(string szFile, int regkind,
                                    [MarshalAs(UnmanagedType.Interface)] out CT.ITypeLib pptlib);

    public static CT.ITypeLib Load(string path, out int hr)
    {
        CT.ITypeLib lib;
        hr = LoadTypeLibEx(path, REGKIND_NONE, out lib);
        return hr == 0 ? lib : null;
    }

    public static string Hex(int value)
    {
        return "0x" + value.ToString("X8", CultureInfo.InvariantCulture);
    }

    public static string LibraryHeader(CT.ITypeLib lib)
    {
        string name, doc, helpFile;
        int helpContext;
        lib.GetDocumentation(-1, out name, out doc, out helpContext, out helpFile);
        IntPtr p;
        lib.GetLibAttr(out p);
        try {
            CT.TYPELIBATTR a = (CT.TYPELIBATTR) Marshal.PtrToStructure(p, typeof(CT.TYPELIBATTR));
            return string.Format(CultureInfo.InvariantCulture,
                "library {0} {{{1}}} version {2}.{3} lcid {4} syskind {5} flags {6} -- \"{7}\"; {8} type infos",
                name, a.guid, a.wMajorVerNum, a.wMinorVerNum, a.lcid, a.syskind, a.wLibFlags, doc,
                lib.GetTypeInfoCount());
        } finally {
            lib.ReleaseTLibAttr(p);
        }
    }

    static string NameOf(CT.ITypeInfo info)
    {
        string name, doc, helpFile;
        int helpContext;
        info.GetDocumentation(-1, out name, out doc, out helpContext, out helpFile);
        return name;
    }

    static string MemberName(CT.ITypeInfo info, int memid)
    {
        string name, doc, helpFile;
        int helpContext;
        info.GetDocumentation(memid, out name, out doc, out helpContext, out helpFile);
        return name;
    }

    static CT.TYPEATTR Attr(CT.ITypeInfo info)
    {
        IntPtr p;
        info.GetTypeAttr(out p);
        try { return (CT.TYPEATTR) Marshal.PtrToStructure(p, typeof(CT.TYPEATTR)); }
        finally { info.ReleaseTypeAttr(p); }
    }

    // A TYPEDESC as the library declares it, followed through pointers, arrays and typedefs down
    // to the variant type that decides the width. The VT names are the CLR's own VarEnum, so no
    // variant-type number in this output was typed by hand.
    public static string Describe(CT.ITypeInfo owner, CT.TYPEDESC td)
    {
        VarEnum vt = (VarEnum) td.vt;
        string raw = "(vt " + td.vt.ToString(CultureInfo.InvariantCulture) + ")";
        if (vt == VarEnum.VT_PTR) {
            CT.TYPEDESC inner = (CT.TYPEDESC) Marshal.PtrToStructure(td.lpValue, typeof(CT.TYPEDESC));
            return Describe(owner, inner) + " *";
        }
        if (vt == VarEnum.VT_SAFEARRAY) {
            CT.TYPEDESC inner = (CT.TYPEDESC) Marshal.PtrToStructure(td.lpValue, typeof(CT.TYPEDESC));
            return "SAFEARRAY(" + Describe(owner, inner) + ")";
        }
        if (vt == VarEnum.VT_CARRAY) {
            // ARRAYDESC begins with the element's TYPEDESC.
            CT.TYPEDESC inner = (CT.TYPEDESC) Marshal.PtrToStructure(td.lpValue, typeof(CT.TYPEDESC));
            return "CARRAY(" + Describe(owner, inner) + ")";
        }
        if (vt == VarEnum.VT_USERDEFINED) {
            int href = unchecked((int) td.lpValue.ToInt64());
            CT.ITypeInfo target;
            owner.GetRefTypeInfo(href, out target);
            CT.TYPEATTR a = Attr(target);
            string name = NameOf(target);
            if (a.typekind == CT.TYPEKIND.TKIND_ALIAS) {
                return name + " (alias of " + Describe(target, a.tdescAlias) + ")";
            }
            return name + " (" + a.typekind + ", " + a.cbSizeInstance + " bytes)";
        }
        string vtName = Enum.IsDefined(typeof(VarEnum), vt) ? vt.ToString() : "?";
        return vtName + " " + raw;
    }

    // Every constant a module or an enumeration declares, with its variant type and value.
    public static List<string> Constants(CT.ITypeInfo info, string owner)
    {
        List<string> lines = new List<string>();
        CT.TYPEATTR a = Attr(info);
        for (int i = 0; i < a.cVars; i++) {
            IntPtr p;
            info.GetVarDesc(i, out p);
            try {
                CT.VARDESC v = (CT.VARDESC) Marshal.PtrToStructure(p, typeof(CT.VARDESC));
                string name = MemberName(info, v.memid);
                if (v.varkind != CT.VARKIND.VAR_CONST) {
                    lines.Add(string.Format("//   {0}.{1} : {2}, not a constant", owner, name, v.varkind));
                    continue;
                }
                object value = Marshal.GetObjectForNativeVariant(v.desc.lpvarValue);
                string shown;
                if (value is int) {
                    shown = ((int) value).ToString(CultureInfo.InvariantCulture) + " (" + Hex((int) value) + ")";
                } else if (value is double) {
                    shown = ((double) value).ToString("R", CultureInfo.InvariantCulture);
                } else if (value is IFormattable) {
                    shown = ((IFormattable) value).ToString(null, CultureInfo.InvariantCulture);
                } else {
                    shown = value == null ? "null" : value.ToString();
                }
                lines.Add(string.Format(CultureInfo.InvariantCulture, "    {0}.{1} = {2}   [{3}, CLR {4}]",
                    owner, name, shown, Describe(info, v.elemdescVar.tdesc),
                    value == null ? "null" : value.GetType().Name));
            } finally {
                info.ReleaseVarDesc(p);
            }
        }
        return lines;
    }

    // Every member of an interface: its vtable slot, how it is invoked, and each parameter with the
    // type the library declares and the direction flags.
    public static List<string> Members(CT.ITypeInfo info)
    {
        List<string> lines = new List<string>();
        CT.TYPEATTR a = Attr(info);
        for (int i = 0; i < a.cImplTypes; i++) {
            int href;
            info.GetRefTypeOfImplType(i, out href);
            CT.ITypeInfo bas;
            info.GetRefTypeInfo(href, out bas);
            lines.Add("//     inherits " + NameOf(bas) + " {" + Attr(bas).guid + "}");
        }
        int elemSize = Marshal.SizeOf(typeof(CT.ELEMDESC));
        for (int i = 0; i < a.cFuncs; i++) {
            IntPtr p;
            info.GetFuncDesc(i, out p);
            try {
                CT.FUNCDESC f = (CT.FUNCDESC) Marshal.PtrToStructure(p, typeof(CT.FUNCDESC));
                string[] names = new string[f.cParams + 1];
                int count;
                info.GetNames(f.memid, names, names.Length, out count);
                StringBuilder sb = new StringBuilder();
                sb.AppendFormat(CultureInfo.InvariantCulture,
                    "//     slot {0,2} (oVft {1}) {2} {3} memid {4} callconv {5} returns {6}",
                    f.oVft / IntPtr.Size, f.oVft, f.invkind, names[0], f.memid, f.callconv,
                    Describe(info, f.elemdescFunc.tdesc));
                lines.Add(sb.ToString());
                for (int k = 0; k < f.cParams; k++) {
                    CT.ELEMDESC e = (CT.ELEMDESC) Marshal.PtrToStructure(
                        new IntPtr(f.lprgelemdescParam.ToInt64() + k * elemSize), typeof(CT.ELEMDESC));
                    string pname = (k + 1 < count && names[k + 1] != null) ? names[k + 1] : "(unnamed)";
                    lines.Add(string.Format(CultureInfo.InvariantCulture,
                        "//         param {0} {1} : {2}  flags {3}",
                        k, pname, Describe(info, e.tdesc), e.desc.paramdesc.wParamFlags));
                }
            } finally {
                info.ReleaseFuncDesc(p);
            }
        }
        return lines;
    }

    static string Summary(CT.ITypeLib lib, int index, out CT.ITypeInfo info, out string name, out CT.TYPEKIND kind)
    {
        lib.GetTypeInfo(index, out info);
        lib.GetTypeInfoType(index, out kind);
        name = NameOf(info);
        CT.TYPEATTR a = Attr(info);
        return string.Format(CultureInfo.InvariantCulture,
            "// [{0,3}] {1,-15} {2} {{{3}}} funcs {4} vars {5} implTypes {6} cbSizeVft {7} flags {8}",
            index, kind, name, a.guid, a.cFuncs, a.cVars, a.cImplTypes, a.cbSizeVft, a.wTypeFlags);
    }

    static Guid LibGuid(CT.ITypeLib lib)
    {
        IntPtr p;
        lib.GetLibAttr(out p);
        try { return ((CT.TYPELIBATTR) Marshal.PtrToStructure(p, typeof(CT.TYPELIBATTR))).guid; }
        finally { lib.ReleaseTLibAttr(p); }
    }

    class Entry
    {
        public CT.ITypeInfo Info;
        public string Name;
        public CT.TYPEKIND Kind;
    }

    // The whole reading, kept on this side of the boundary: a COM interface handed to PowerShell
    // comes back as a bare __ComObject that no longer converts to ITypeLib.
    public static List<string> Run(string dll, string[] suffixes, string[] wanted, bool allMembers)
    {
        List<string> output = new List<string>();

        // Which TYPELIB resources the DLL answers to. The bare path is the first; "\N" names
        // resource N. A failure is printed with its HRESULT, and dump-uia-entry-points.ps1 lists
        // the resource table itself from the PE, so the two can be compared.
        output.Add("// ---- TYPELIB resources of " + dll + ", by index");
        List<KeyValuePair<string, CT.ITypeLib>> libs = new List<KeyValuePair<string, CT.ITypeLib>>();
        foreach (string suffix in suffixes) {
            int hr;
            CT.ITypeLib lib = Load(dll + suffix, out hr);
            if (lib != null) {
                output.Add("//   '" + suffix + "' : " + LibraryHeader(lib));
                libs.Add(new KeyValuePair<string, CT.ITypeLib>(suffix, lib));
            } else {
                output.Add("//   '" + suffix + "' : LoadTypeLibEx failed, HRESULT " + Hex(hr));
            }
        }
        output.Add("");

        // One section per distinct library: the bare path and "\1" are normally the same one.
        Dictionary<Guid, string> seen = new Dictionary<Guid, string>();
        foreach (KeyValuePair<string, CT.ITypeLib> pair in libs) {
            CT.ITypeLib lib = pair.Value;
            Guid guid = LibGuid(lib);
            if (seen.ContainsKey(guid)) {
                output.Add("// ('" + pair.Key + "' is the same library as '" + seen[guid] + "'; not printed twice)");
                output.Add("");
                continue;
            }
            seen[guid] = pair.Key;
            output.Add("// ======== " + LibraryHeader(lib));
            output.Add("");

            List<Entry> modules = new List<Entry>();
            List<Entry> enums = new List<Entry>();
            List<Entry> interfaces = new List<Entry>();
            output.Add("// ---- every type info");
            int count = lib.GetTypeInfoCount();
            for (int i = 0; i < count; i++) {
                Entry e = new Entry();
                output.Add(Summary(lib, i, out e.Info, out e.Name, out e.Kind));
                if (e.Kind == CT.TYPEKIND.TKIND_MODULE) modules.Add(e);
                else if (e.Kind == CT.TYPEKIND.TKIND_ENUM) enums.Add(e);
                else if (e.Kind == CT.TYPEKIND.TKIND_INTERFACE || e.Kind == CT.TYPEKIND.TKIND_DISPATCH) interfaces.Add(e);
            }
            output.Add("");

            output.Add("// ---- module constants (VAR_CONST)");
            foreach (Entry m in modules) {
                output.Add("//   module " + m.Name);
                output.AddRange(Constants(m.Info, m.Name));
            }
            output.Add("");

            output.Add("// ---- enumerations");
            foreach (Entry e in enums) {
                output.Add("//   enum " + e.Name);
                output.AddRange(Constants(e.Info, e.Name));
            }
            output.Add("");

            output.Add("// ---- interface members, with each parameter's declared type");
            foreach (string name in wanted) {
                bool found = false;
                foreach (Entry e in interfaces) { if (e.Name == name) found = true; }
                if (!found) output.Add("//   " + name + " : NOT IN THIS LIBRARY");
            }
            bool anyProvider = false;
            foreach (Entry e in interfaces) { if (e.Name.Contains("Provider")) anyProvider = true; }
            if (!anyProvider) output.Add("//   (no interface whose name contains Provider is in this library)");
            foreach (Entry e in interfaces) {
                bool print = allMembers || Array.IndexOf(wanted, e.Name) >= 0 || e.Name.Contains("Provider");
                if (!print) continue;
                output.Add("//   " + e.Kind + " " + e.Name);
                output.AddRange(Members(e.Info));
            }
            output.Add("");
        }
        return output;
    }
}
'@

# Every TypeLib registration that points at UIAutomationCore.dll, machine-wide and per user, in
# both registry views. Taken before the first load and again at the end: REGKIND_NONE promises
# the load registers nothing, and a difference between the two lists would be the promise broken.
function Find-UiaTypeLibRegistrations {
    $found = @()
    foreach ($root in @('HKLM:\SOFTWARE\Classes\TypeLib',
                        'HKLM:\SOFTWARE\WOW6432Node\Classes\TypeLib',
                        'HKCU:\Software\Classes\TypeLib')) {
        if (-not (Test-Path $root)) { continue }
        foreach ($key in (Get-ChildItem $root -Recurse -ErrorAction SilentlyContinue)) {
            $default = $key.GetValue('')
            if ("$default" -match 'UIAutomationCore') { $found += "$($key.Name) = $default" }
        }
    }
    return $found
}

$registeredBefore = @(Find-UiaTypeLibRegistrations)
Write-Output '// ---- TypeLib registrations naming UIAutomationCore, before loading anything'
if ($registeredBefore.Count -eq 0) { Write-Output '//   none' }
foreach ($line in $registeredBefore) { Write-Output "//   $line" }
Write-Output ''

# The client interfaces whose boolean getters a width question hangs on. Every *Provider interface
# the library carries is printed as well, since the provider side is the one a bridge implements.
$wanted = @(
    'IUIAutomationSelectionItemPattern',
    'IUIAutomationValuePattern',
    'IUIAutomationRangeValuePattern',
    'IUIAutomationSelectionPattern',
    'IUIAutomationScrollPattern',
    'IUIAutomationTogglePattern',
    'IUIAutomationExpandCollapsePattern'
)
$suffixes = @('', '\1', '\2', '\3', '\4', '\5', '\6', '\7', '\8')
foreach ($line in [LimnTypeLibReader]::Run($dll, $suffixes, $wanted, [bool]$AllMembers)) {
    Write-Output $line
}

$registeredAfter = @(Find-UiaTypeLibRegistrations)
Write-Output '// ---- TypeLib registrations naming UIAutomationCore, after reading'
if ($registeredAfter.Count -eq 0) { Write-Output '//   none' }
foreach ($line in $registeredAfter) { Write-Output "//   $line" }
$changed = @(Compare-Object -ReferenceObject $registeredBefore -DifferenceObject $registeredAfter)
if ($changed.Count -eq 0) {
    Write-Output '//   identical to the list taken before loading: nothing was registered'
} else {
    foreach ($c in $changed) { Write-Output "//   CHANGED $($c.SideIndicator) $($c.InputObject)" }
}
