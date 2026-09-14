# Reads which native entry points UIAutomationCore.dll exports, and the parameter lists Microsoft's
# own managed code declares for them, off the machine this runs on.
#
# ADR 039 §12.3's rule, for the shape of a native call. A bridge that binds a function by name
# fails loudly if the name is not exported -- but only on the machine that lacks it, which is the
# one nobody tested on -- and binds a parameter list it cannot check at all: a StructureChangeType
# passed where a runtime-id length was expected is accepted and misread. So two things are read:
#
#   1. the export table, parsed straight out of the PE file (this guest has no dumpbin), which says
#      what this build of UIAutomationCore offers by name; and the TYPELIB resources beside it,
#      which dump-uia-typelib.ps1 loads by index, so the two readings can be compared;
#   2. every P/Invoke declaration naming UIAutomationCore in the .NET Framework's UI Automation
#      and WPF assemblies, with each parameter's managed type and marshalling descriptor -- the
#      signatures Microsoft compiled against the header this guest does not have.
#
# The export table read here is the image's primary one. On ARM64 Windows that DLL is the native
# ARM64 view; an x64-emulated process resolves names through the image's hybrid (ARM64X) view,
# which this script does not parse -- the Machine field below says which kind of image it is.
#
# Run it on the Windows guest:
#   powershell -NoProfile -ExecutionPolicy Bypass -File dump-uia-entry-points.ps1

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'reading-environment.ps1')
Write-ReadingEnvironment

$dll = Join-Path $env:SystemRoot 'System32\UIAutomationCore.dll'
if (-not (Test-Path $dll)) { throw "$dll is not on this machine" }

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;

public static class LimnPeReader
{
    // The PE layout offsets below are the file format's (IMAGE_DOS_HEADER.e_lfanew at 0x3C, the COFF
    // header after "PE\0\0", the data directories at the end of the optional header). They are
    // plumbing, and the output checks itself: a wrong offset prints garbage names, not plausible ones.
    class Section { public string Name; public uint VirtualAddress; public uint VirtualSize; public uint RawPointer; public uint RawSize; }

    static byte[] b;
    static List<Section> sections;

    static ushort U16(int o) { return BitConverter.ToUInt16(b, o); }
    static uint U32(int o) { return BitConverter.ToUInt32(b, o); }

    static int Offset(uint rva)
    {
        foreach (Section s in sections) {
            uint size = Math.Max(s.VirtualSize, s.RawSize);
            if (rva >= s.VirtualAddress && rva < s.VirtualAddress + size) {
                return (int) (rva - s.VirtualAddress + s.RawPointer);
            }
        }
        throw new InvalidDataException("RVA 0x" + rva.ToString("X") + " is in no section");
    }

    static string Ascii(int o)
    {
        int end = o;
        while (b[end] != 0) end++;
        return Encoding.ASCII.GetString(b, o, end - o);
    }

    public static List<string> Read(string path, string[] wanted)
    {
        List<string> output = new List<string>();
        b = File.ReadAllBytes(path);
        sections = new List<Section>();

        int peOffset = BitConverter.ToInt32(b, 0x3C);
        if (U32(peOffset) != 0x00004550) throw new InvalidDataException("no PE signature");
        int coff = peOffset + 4;
        ushort machine = U16(coff);
        ushort sectionCount = U16(coff + 2);
        uint stamp = U32(coff + 4);
        ushort optionalSize = U16(coff + 16);
        ushort characteristics = U16(coff + 18);
        int optional = coff + 20;
        ushort magic = U16(optional);
        bool pe32plus = magic == 0x20B;
        int directories = optional + (pe32plus ? 112 : 96);
        uint directoryCount = U32(optional + (pe32plus ? 108 : 92));

        string machineName = Enum.IsDefined(typeof(ImageFileMachine), (int) machine)
            ? ((ImageFileMachine) machine).ToString() : "(not in the CLR's ImageFileMachine)";
        output.Add(string.Format(CultureInfo.InvariantCulture,
            "// {0}: {1} bytes, Machine 0x{2:X4} {3}, optional header magic 0x{4:X3} ({5}), TimeDateStamp 0x{6:X8}, Characteristics 0x{7:X4}, {8} sections, {9} data directories",
            path, b.Length, machine, machineName, magic, pe32plus ? "PE32+" : "PE32", stamp, characteristics, sectionCount, directoryCount));

        int sectionTable = optional + optionalSize;
        for (int i = 0; i < sectionCount; i++) {
            int s = sectionTable + i * 40;
            Section sec = new Section();
            sec.Name = Encoding.ASCII.GetString(b, s, 8).TrimEnd('\0');
            sec.VirtualSize = U32(s + 8);
            sec.VirtualAddress = U32(s + 12);
            sec.RawSize = U32(s + 16);
            sec.RawPointer = U32(s + 20);
            sections.Add(sec);
        }

        // ---- exports: data directory 0
        uint exportRva = U32(directories);
        uint exportSize = U32(directories + 4);
        output.Add("");
        output.Add("// ---- export table");
        if (exportRva == 0) {
            output.Add("//   (no export directory)");
        } else {
            int e = Offset(exportRva);
            string dllName = Ascii(Offset(U32(e + 12)));
            uint ordinalBase = U32(e + 16);
            uint functionCount = U32(e + 20);
            uint nameCount = U32(e + 24);
            int functions = Offset(U32(e + 28));
            int names = Offset(U32(e + 32));
            int ordinals = Offset(U32(e + 36));
            output.Add(string.Format(CultureInfo.InvariantCulture,
                "//   name {0}, ordinal base {1}, {2} functions, {3} names",
                dllName, ordinalBase, functionCount, nameCount));

            Dictionary<string, string> found = new Dictionary<string, string>();
            for (int i = 0; i < nameCount; i++) {
                string name = Ascii(Offset(U32(names + i * 4)));
                ushort index = U16(ordinals + i * 2);
                uint rva = U32(functions + index * 4);
                string where;
                if (rva >= exportRva && rva < exportRva + exportSize) {
                    where = "forwarded to " + Ascii(Offset(rva));
                } else {
                    where = "RVA 0x" + rva.ToString("X8", CultureInfo.InvariantCulture);
                }
                string line = string.Format(CultureInfo.InvariantCulture, "ordinal {0,4}  {1}  {2}", ordinalBase + index, where, name);
                found[name] = line;
                output.Add("//     " + line);
            }
            output.Add("");
            output.Add("// ---- the entry points the audit names");
            foreach (string w in wanted) {
                output.Add(found.ContainsKey(w) ? "//   EXPORTED  " + found[w] : "//   ABSENT    " + w);
            }
        }

        // ---- resources: data directory 2, the TYPELIB entries only (and the type names for context)
        uint resourceRva = U32(directories + 16);
        output.Add("");
        output.Add("// ---- resource types, and every TYPELIB resource id");
        if (resourceRva == 0) {
            output.Add("//   (no resource directory)");
        } else {
            int root = Offset(resourceRva);
            int named = U16(root + 12);
            int ids = U16(root + 14);
            List<string> typeNames = new List<string>();
            for (int i = 0; i < named + ids; i++) {
                int entry = root + 16 + i * 8;
                uint nameOrId = U32(entry);
                uint data = U32(entry + 4);
                string typeName;
                if ((nameOrId & 0x80000000) != 0) {
                    int s = root + (int) (nameOrId & 0x7FFFFFFF);
                    typeName = Encoding.Unicode.GetString(b, s + 2, U16(s) * 2);
                } else {
                    typeName = "#" + nameOrId.ToString(CultureInfo.InvariantCulture);
                }
                typeNames.Add(typeName);
                if (typeName == "TYPELIB" && (data & 0x80000000) != 0) {
                    int sub = root + (int) (data & 0x7FFFFFFF);
                    int subCount = U16(sub + 12) + U16(sub + 14);
                    for (int k = 0; k < subCount; k++) {
                        int subEntry = sub + 16 + k * 8;
                        uint id = U32(subEntry);
                        string idText = (id & 0x80000000) != 0 ? "(named)" : id.ToString(CultureInfo.InvariantCulture);
                        output.Add("//   TYPELIB resource " + idText);
                    }
                }
            }
            output.Add("//   resource types: " + string.Join(", ", typeNames.ToArray()));
        }
        return output;
    }
}

public static class LimnPInvokeReader
{
    static string Marshalling(ParameterInfo p)
    {
        object[] attrs = p.GetCustomAttributes(typeof(MarshalAsAttribute), false);
        string ma = "no MarshalAs";
        if (attrs.Length > 0) {
            MarshalAsAttribute a = (MarshalAsAttribute) attrs[0];
            ma = "MarshalAs(" + a.Value;
            if (a.Value == UnmanagedType.LPArray || a.Value == UnmanagedType.ByValArray || a.Value == UnmanagedType.SafeArray) {
                ma += ", ArraySubType=" + a.ArraySubType + ", SizeParamIndex=" + a.SizeParamIndex + ", SizeConst=" + a.SizeConst
                    + ", SafeArraySubType=" + a.SafeArraySubType;
            }
            if (a.Value == UnmanagedType.CustomMarshaler) ma += ", MarshalType=" + a.MarshalType;
            ma += ")";
        }
        return ma + ", " + p.Attributes;
    }

    // An enum crosses the boundary as its underlying integer, so that is the width to print.
    static string TypeName(Type t)
    {
        Type element = t.HasElementType ? t.GetElementType() : t;
        if (element.IsEnum) return t.FullName + " (enum, underlying " + Enum.GetUnderlyingType(element).Name + ")";
        return t.FullName;
    }

    public static List<string> Read(Assembly[] assemblies)
    {
        List<string> output = new List<string>();
        foreach (Assembly asm in assemblies) {
            if (asm == null) continue;
            FileVersionInfo file = FileVersionInfo.GetVersionInfo(asm.Location);
            output.Add(string.Format("// ---- {0} {1} (file {2}) at {3}", asm.GetName().Name, asm.GetName().Version, file.FileVersion, asm.Location));
            Type[] types;
            try { types = asm.GetTypes(); }
            catch (ReflectionTypeLoadException e) {
                types = e.Types;
                output.Add("//   (some types could not be loaded; the rest were searched)");
            }
            int hits = 0;
            foreach (Type t in types) {
                if (t == null) continue;
                MethodInfo[] methods;
                try {
                    methods = t.GetMethods(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static | BindingFlags.Instance | BindingFlags.DeclaredOnly);
                } catch { continue; }
                foreach (MethodInfo m in methods) {
                    if ((m.Attributes & MethodAttributes.PinvokeImpl) == 0) continue;
                    object[] imports = m.GetCustomAttributes(typeof(DllImportAttribute), false);
                    if (imports.Length == 0) continue;
                    DllImportAttribute d = (DllImportAttribute) imports[0];
                    string entry = string.IsNullOrEmpty(d.EntryPoint) ? m.Name : d.EntryPoint;
                    bool uiaLibrary = d.Value != null && d.Value.IndexOf("UIAutomationCore", StringComparison.OrdinalIgnoreCase) >= 0;
                    bool uiaName = entry.StartsWith("Uia", StringComparison.Ordinal);
                    if (!uiaLibrary && !uiaName) continue;
                    hits++;
                    bool preserveSigFlag = (m.GetMethodImplementationFlags() & MethodImplAttributes.PreserveSig) != 0;
                    output.Add(string.Format(CultureInfo.InvariantCulture,
                        "//   {0}.{1}  -> \"{2}\"!{3}  CharSet={4} PreserveSig={5} (impl flag {6}) SetLastError={7} CallingConvention={8} ExactSpelling={9}",
                        t.FullName, m.Name, d.Value, entry, d.CharSet, d.PreserveSig, preserveSigFlag, d.SetLastError, d.CallingConvention, d.ExactSpelling));
                    output.Add("//       returns " + TypeName(m.ReturnType) + ": " + Marshalling(m.ReturnParameter));
                    foreach (ParameterInfo p in m.GetParameters()) {
                        output.Add(string.Format(CultureInfo.InvariantCulture, "//       param {0} {1} : {2}: {3}",
                            p.Position, p.Name, TypeName(p.ParameterType), Marshalling(p)));
                    }
                }
            }
            if (hits == 0) output.Add("//   (no P/Invoke into UIAutomationCore, and none named Uia*)");
            output.Add("");
        }
        return output;
    }
}
'@

$wanted = @(
    'UiaRaiseNotificationEvent',
    'UiaRaiseStructureChangedEvent',
    'UiaRaiseAutomationEvent',
    'UiaRaiseAutomationPropertyChangedEvent',
    'UiaHostProviderFromHwnd',
    'UiaReturnRawElementProvider'
)
foreach ($line in [LimnPeReader]::Read($dll, $wanted)) { Write-Output $line }
Write-Output ''

Write-Output '// ======== P/Invoke declarations naming UIAutomationCore (or an entry point named Uia*)'
$assemblies = @('UIAutomationProvider', 'UIAutomationTypes', 'UIAutomationClient', 'UIAutomationClientSideProviders',
                'PresentationCore', 'PresentationFramework', 'WindowsBase') |
    ForEach-Object {
        $a = [System.Reflection.Assembly]::LoadWithPartialName($_)
        # Write-Host: an Output line here would be filtered away with the nulls.
        if (-not $a) { Write-Host "// $_ : not installed" }
        $a
    } | Where-Object { $_ -is [System.Reflection.Assembly] }
foreach ($line in [LimnPInvokeReader]::Read([System.Reflection.Assembly[]]$assemblies)) { Write-Output $line }
