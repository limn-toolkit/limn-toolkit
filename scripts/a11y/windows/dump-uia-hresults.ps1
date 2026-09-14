# Reads the HRESULTs a UI Automation provider answers with when it cannot do what it was asked,
# off the machine this runs on.
#
# ADR 039 §12.3's rule, for an error code. UIA_E_INVALIDOPERATION and UIA_E_ELEMENTNOTAVAILABLE are
# #defines in uiautomationcoreapi.h, which needs a Windows SDK this guest does not carry, so no
# type library has them either (dump-uia-typelib.ps1 prints every constant it has). What the
# machine does carry is the .NET Framework's side of the same contract, and that is read three ways:
#
#   1. the HResult each exception a managed provider throws is constructed with -- a managed
#      provider reports "invalid operation" by throwing InvalidOperationException, and the COM
#      wrapper hands its HResult to the client;
#   2. every integer constant in the UI Automation and WPF assemblies (and mscorlib's own table)
#      whose name says it is one of these codes, or whose value is one of the codes (1) produced;
#   3. a measurement: a managed provider's verb that throws is called through its COM slot from
#      native code, and the HRESULT the wrapper returns is printed -- what a client actually sees.
#
# Run it on the Windows guest:
#   powershell -NoProfile -ExecutionPolicy Bypass -File dump-uia-hresults.ps1

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'reading-environment.ps1')
Write-ReadingEnvironment

$types = [System.Reflection.Assembly]::LoadWithPartialName('UIAutomationTypes')
$provider = [System.Reflection.Assembly]::LoadWithPartialName('UIAutomationProvider')
$client = [System.Reflection.Assembly]::LoadWithPartialName('UIAutomationClient')
$presentationCore = [System.Reflection.Assembly]::LoadWithPartialName('PresentationCore')
$windowsBase = [System.Reflection.Assembly]::LoadWithPartialName('WindowsBase')
$mscorlib = [System.Object].Assembly
if (-not $types -or -not $provider) { throw 'the UI Automation interop assemblies are not installed' }
$searched = @($types, $provider, $client, $presentationCore, $windowsBase, $mscorlib) | Where-Object { $_ }

Write-Output '// ---- searched'
foreach ($asm in $searched) { Write-Output "//   $(Describe-Assembly $asm)" }
Write-Output ''

function Hex($value) { return '0x{0:X8}' -f $value }

# ---- 1. the exceptions' own HResults
Write-Output '// ---- 1. HResult of each exception, constructed with no arguments'
$produced = @{}
$exceptionTypes = @([System.InvalidOperationException], [System.NotSupportedException],
                    [System.ArgumentException], [System.NotImplementedException])
foreach ($asm in @($types, $provider, $client)) {
    if (-not $asm) { continue }
    foreach ($t in (Get-LoadableTypes $asm)) {
        if ($t.IsPublic -and -not $t.IsAbstract -and [System.Exception].IsAssignableFrom($t)) { $exceptionTypes += $t }
    }
}
foreach ($t in $exceptionTypes) {
    try {
        $e = [System.Activator]::CreateInstance($t)
        $hr = $e.HResult
        $produced[$hr] = $t.FullName
        Write-Output ("//   {0} = {1} ({2})  from {3}" -f $t.FullName, (Hex $hr), $hr, $t.Assembly.GetName().Name)
    } catch {
        Write-Output ("//   {0} : could not construct ({1})" -f $t.FullName, $_.Exception.GetType().Name)
    }
}
Write-Output ''

# What the CLR itself maps each of those codes back to, the direction a managed client goes.
Write-Output '// ---- the CLR''s own mapping back: Marshal.GetExceptionForHR(code)'
foreach ($hr in ($produced.Keys | Sort-Object)) {
    $back = [System.Runtime.InteropServices.Marshal]::GetExceptionForHR([int]$hr)
    $backName = if ($back) { $back.GetType().FullName } else { '(null)' }
    Write-Output ("//   {0} -> {1}" -f (Hex $hr), $backName)
}
Write-Output ''

# ---- 2. named constants
Write-Output '// ---- 2. integer constants whose name is one of these codes, or whose value is one (1) produced'
$namePattern = 'INVALIDOPERATION|INVALID_OPERATION|ELEMENTNOTAVAILABLE|ELEMENT_NOT_AVAILABLE|ELEMENTNOTENABLED|NOCLICKABLEPOINT|PROXYASSEMBLYNOTLOADED|NOTSUPPORTED|NOT_SUPPORTED|^UIA_E_'
$flags = [System.Reflection.BindingFlags]'Public,NonPublic,Static,DeclaredOnly'
foreach ($asm in $searched) {
    $hits = 0
    foreach ($t in (Get-LoadableTypes $asm)) {
        $fields = @()
        try { $fields = $t.GetFields($flags) } catch { continue }
        foreach ($f in $fields) {
            if ($f.FieldType -ne [int] -and $f.FieldType -ne [uint32]) { continue }
            $value = $null
            try {
                if ($f.IsLiteral) { $value = $f.GetRawConstantValue() }
                elseif ($f.IsInitOnly) { $value = $f.GetValue($null) }
                else { continue }
            } catch { continue }
            $asInt = [int][System.BitConverter]::ToInt32([System.BitConverter]::GetBytes($value), 0)
            $byName = $f.Name -match $namePattern
            $byValue = $produced.ContainsKey($asInt) -and $f.Name -match '^(E_|COR_E_|UIA_E_|HRESULT|.*_E_)'
            if (-not ($byName -or $byValue)) { continue }
            $hits++
            $why = if ($byName) { 'name' } else { "value of $($produced[$asInt])" }
            Write-Output ("//   {0}.{1} = {2}  ({3}, matched by {4})" -f $t.FullName, $f.Name, (Hex $asInt), $(if ($f.IsLiteral) { 'const' } else { 'static readonly' }), $why)
        }
    }
    if ($hits -eq 0) { Write-Output "//   (none in $($asm.GetName().Name))" }
}
Write-Output ''

# ---- 3. what a client sees
$source = @'
using System;
using System.Globalization;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Windows.Automation;
using System.Windows.Automation.Provider;

public class LimnThrowingInvoke : IInvokeProvider
{
    public Exception Thrown;
    public void Invoke() { throw Thrown; }
}

public static class LimnHResultProbe
{
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    delegate int Verb(IntPtr self);

    public static string Probe(Exception thrown)
    {
        LimnThrowingInvoke impl = new LimnThrowingInvoke();
        impl.Thrown = thrown;
        MethodInfo invoke = typeof(IInvokeProvider).GetMethod("Invoke");
        int slot = Marshal.GetComSlotForMethodInfo(invoke);
        IntPtr itf = Marshal.GetComInterfaceForObject(impl, typeof(IInvokeProvider));
        try {
            IntPtr fn = Marshal.ReadIntPtr(Marshal.ReadIntPtr(itf), slot * IntPtr.Size);
            Verb call = (Verb) Marshal.GetDelegateForFunctionPointer(fn, typeof(Verb));
            int hr = call(itf);
            return string.Format(CultureInfo.InvariantCulture,
                "IInvokeProvider.Invoke (slot {0}) throwing {1} (HResult 0x{2:X8}) returns hr 0x{3:X8}",
                slot, thrown.GetType().FullName, thrown.HResult, hr);
        } catch (Exception e) {
            return "IInvokeProvider.Invoke throwing " + thrown.GetType().FullName + ": the call FAILED " + e.GetType().Name + ": " + e.Message;
        } finally {
            Marshal.Release(itf);
        }
    }
}
'@
Add-Type -TypeDefinition $source -ReferencedAssemblies @($provider.Location, $types.Location)

Write-Output '// ---- 3. measured: the HRESULT a managed provider''s COM wrapper returns when the verb throws'
foreach ($t in @([System.InvalidOperationException], [System.Windows.Automation.ElementNotAvailableException],
                 [System.Windows.Automation.ElementNotEnabledException], [System.NotSupportedException])) {
    $e = [System.Activator]::CreateInstance($t)
    Write-Output ('//   ' + [LimnHResultProbe]::Probe($e))
}
