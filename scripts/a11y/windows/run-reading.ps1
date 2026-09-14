# Runs one dump script and keeps its whole output -- every stream, errors included -- as UTF-8.
#
# A reading is only kept if it survives the trip back. The guest's console is cp850 and SSH
# carries its bytes as they are, so anything non-ASCII (the Portuguese messages this guest throws
# with, for one) arrives mangled; and a script that stops half way must leave what it did print,
# plus why it stopped, in the same file rather than on a console nobody saved.
#
# Run it on the Windows guest, beside the dump scripts:
#   powershell -NoProfile -ExecutionPolicy Bypass -File run-reading.ps1 -Script dump-uia-typelib.ps1 -Out C:\...\windows-dump-uia-typelib.txt [-AllMembers]

param([Parameter(Mandatory = $true)][string]$Script,
      [Parameter(Mandatory = $true)][string]$Out,
      [switch]$AllMembers)

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$path = Join-Path $here $Script
try {
    if ($AllMembers) {
        & $path -AllMembers *>&1 | Out-File -FilePath $Out -Encoding utf8 -Width 4096
    } else {
        & $path *>&1 | Out-File -FilePath $Out -Encoding utf8 -Width 4096
    }
    Write-Output "ok $Script -> $Out"
} catch {
    "// SCRIPT FAILED: $($_.Exception.GetType().FullName): $($_.Exception.Message)" | Out-File -FilePath $Out -Encoding utf8 -Width 4096 -Append
    "// at: $($_.ScriptStackTrace)" | Out-File -FilePath $Out -Encoding utf8 -Width 4096 -Append
    "// invocation: $($_.InvocationInfo.PositionMessage)" | Out-File -FilePath $Out -Encoding utf8 -Width 4096 -Append
    Write-Output "FAILED $Script -> $Out : $($_.Exception.Message)"
    exit 1
}
