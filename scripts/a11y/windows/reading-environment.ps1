# The machine a reading was taken on, printed at the head of the reading so the two cannot part.
#
# ADR 039 §12.3 keeps a reading's output, and an output with no machine attached is a number
# nobody can compare against the next one: a width or an id that moves between Windows builds is
# only a finding if both dumps say which build they came from. Dot-source this from a dump script
# and call Write-ReadingEnvironment before anything else. Every line is a `//` comment, so a dump
# whose body is pasted into Java stays pasteable.
#
# The host's clock is not visible from here; whoever runs the reading records it beside the file.

function Write-ReadingEnvironment {
    $ndpKey = 'HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full'
    $ndp = $null
    try { $ndp = Get-ItemProperty -Path $ndpKey -ErrorAction Stop } catch { }

    # The process's own architecture as well as the machine's: on ARM64 Windows an emulated x64
    # PowerShell reads the same System32 files but is a different ABI, and a width reading from it
    # would be a reading of the emulator's view.
    $processArch = '(not available)'
    try { $processArch = [System.Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture } catch { }

    $core = Join-Path $env:SystemRoot 'System32\UIAutomationCore.dll'
    $coreLine = '(not present)'
    if (Test-Path $core) {
        $v = (Get-Item $core).VersionInfo
        $coreLine = "{0}.{1}.{2}.{3} (string resource '{4}')" -f `
            $v.FileMajorPart, $v.FileMinorPart, $v.FileBuildPart, $v.FilePrivatePart, $v.FileVersion
    }

    Write-Output "// Environment.OSVersion   $([System.Environment]::OSVersion.VersionString)"
    Write-Output "// PROCESSOR_ARCHITECTURE  $env:PROCESSOR_ARCHITECTURE (process: $processArch, Is64BitProcess=$([System.Environment]::Is64BitProcess))"
    Write-Output "// CLR                     $([System.Environment]::Version) under PowerShell $($PSVersionTable.PSVersion)"
    if ($ndp) {
        Write-Output "// .NET Framework          Release=$($ndp.Release) Version=$($ndp.Version)  ($ndpKey)"
    } else {
        Write-Output "// .NET Framework          (could not read $ndpKey)"
    }
    Write-Output "// UIAutomationCore.dll    $core $coreLine"
    Write-Output "// Guest UTC               $([System.DateTime]::UtcNow.ToString('yyyy-MM-dd HH:mm:ss'))Z"
    Write-Output ''
}

# Every type an assembly will give up. PresentationCore in particular has types whose
# dependencies are not all loadable in a bare PowerShell, and GetTypes() then throws having
# loaded the rest: keep the rest, and say how many were lost, rather than lose the assembly.
function Get-LoadableTypes($assembly) {
    try {
        return $assembly.GetTypes()
    } catch {
        $e = $_.Exception
        while ($e -and -not ($e -is [System.Reflection.ReflectionTypeLoadException])) { $e = $e.InnerException }
        if (-not $e) { throw }
        $lost = @($e.Types | Where-Object { $_ -eq $null }).Count
        # Write-Host, not Write-Output: anything written to the output stream here would become
        # part of the returned list of types.
        Write-Host "// (note: $lost types of $($assembly.GetName().Name) could not be loaded and were skipped)"
        return @($e.Types | Where-Object { $_ -ne $null })
    }
}

# Where an assembly came from, in the one line a reader of the dump needs.
function Describe-Assembly($assembly) {
    $file = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($assembly.Location)
    return "{0} {1} (file {2}) at {3}" -f $assembly.GetName().Name, $assembly.GetName().Version, $file.FileVersion, $assembly.Location
}
