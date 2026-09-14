# Reads which NVDA is installed on the machine this runs on, and what form its code ships in.
#
# A reader's behaviour is read from its source, and source is only evidence for the version it
# was cut from: a finding "NVDA does X" checked against the wrong tag is a recollection with a
# citation. So the version is read off the installed binary rather than remembered from the
# installer, and the install is inventoried to say whether its Python ships as source or only
# compiled -- which decides whether the tree on this machine can be read at all, or whether the
# matching upstream tag has to be. Nothing is opened but directory listings and a zip's table of
# contents; no source file's contents are read, and the running NVDA is not touched.
#
# Run it on the Windows guest:
#   powershell -NoProfile -ExecutionPolicy Bypass -File dump-nvda-install.ps1

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'reading-environment.ps1')
Write-ReadingEnvironment

# Every place an installer puts it, and what the uninstall entry says -- two sources that should
# agree, printed side by side rather than trusted one over the other.
$candidates = @()
foreach ($root in @(${env:ProgramFiles(x86)}, $env:ProgramFiles)) {
    if ($root) { $candidates += (Join-Path $root 'NVDA\nvda.exe') }
}
Write-Output '// ---- nvda.exe candidates'
$exe = $null
foreach ($c in $candidates) {
    if (Test-Path $c) {
        $v = (Get-Item $c).VersionInfo
        Write-Output ("//   {0}: FileVersion '{1}' ProductVersion '{2}' ({3}.{4}.{5}.{6}) ProductName '{7}' CompanyName '{8}'" -f `
            $c, $v.FileVersion, $v.ProductVersion, $v.FileMajorPart, $v.FileMinorPart, $v.FileBuildPart, $v.FilePrivatePart,
            $v.ProductName, $v.CompanyName)
        if (-not $exe) { $exe = $c }
    } else {
        Write-Output "//   ${c}: absent"
    }
}
Write-Output ''

Write-Output '// ---- uninstall registry entries naming NVDA'
foreach ($key in @('HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\NVDA',
                   'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\NVDA')) {
    if (Test-Path $key) {
        $p = Get-ItemProperty $key
        Write-Output "//   ${key}: DisplayVersion '$($p.DisplayVersion)' InstallLocation '$($p.InstallLocation)' Publisher '$($p.Publisher)'"
    } else {
        Write-Output "//   ${key}: absent"
    }
}
Write-Output ''

# The running copy, by image path only: which install it was started from. Nothing is sent to it.
Write-Output '// ---- running nvda.exe processes (path only)'
$running = @(Get-CimInstance Win32_Process -Filter "Name = 'nvda.exe'")
if ($running.Count -eq 0) { Write-Output '//   none' }
foreach ($proc in $running) {
    Write-Output "//   pid $($proc.ProcessId) session $($proc.SessionId) path '$($proc.ExecutablePath)'"
}
Write-Output ''

if (-not $exe) {
    Write-Output '// NVDA is not installed in either Program Files; nothing more to read.'
    return
}
$dir = Split-Path -Parent $exe

# What the install ships its code as. Counts by extension over the whole tree, then the top level.
Write-Output "// ---- files under $dir, counted by extension"
$files = @(Get-ChildItem -Path $dir -Recurse -File -ErrorAction SilentlyContinue)
$files | Group-Object { $_.Extension.ToLowerInvariant() } | Sort-Object Count -Descending | ForEach-Object {
    Write-Output ("//   {0,-8} {1}" -f $(if ($_.Name) { $_.Name } else { '(none)' }), $_.Count)
}
Write-Output "//   total $($files.Count) files"
$py = @($files | Where-Object { $_.Extension -ieq '.py' })
Write-Output "//   .py files on disk: $($py.Count)"
foreach ($f in ($py | Select-Object -First 10)) { Write-Output "//     $($f.FullName.Substring($dir.Length + 1))" }
Write-Output ''

# Which architecture each image is built for. On an ARM64 machine a reader built for x86 runs
# emulated and reaches an ARM64 provider across that boundary, so it is a fact about the reader
# worth having next to its version. The Machine word is read straight from the PE header; its name
# is the CLR's ImageFileMachine where that enumeration has one.
function Get-ImageMachine($path) {
    $stream = [System.IO.File]::OpenRead($path)
    try {
        $reader = New-Object System.IO.BinaryReader($stream)
        $stream.Position = 0x3C
        $peOffset = $reader.ReadInt32()
        $stream.Position = $peOffset + 4
        $machine = [int]$reader.ReadUInt16()
        $name = if ([System.Enum]::IsDefined([System.Reflection.ImageFileMachine], $machine)) {
            [System.Enum]::GetName([System.Reflection.ImageFileMachine], $machine)
        } else { '(not in the CLR''s ImageFileMachine)' }
        return ('0x{0:X4} {1}' -f $machine, $name)
    } finally {
        $stream.Dispose()
    }
}
Write-Output '// ---- image architecture (PE Machine) of the executables and of every nvdaHelper / python DLL'
$images = @($files | Where-Object {
    ($_.DirectoryName -eq $dir -and $_.Extension -ieq '.exe') -or
    ($_.Name -match '^(nvdaHelper|python3)' -and $_.Extension -ieq '.dll')
} | Sort-Object FullName)
foreach ($img in $images) {
    Write-Output ("//   {0,-48} {1}" -f $img.FullName.Substring($dir.Length + 1), (Get-ImageMachine $img.FullName))
}
Write-Output ''

Write-Output "// ---- top level of $dir"
foreach ($item in (Get-ChildItem -Path $dir | Sort-Object Name)) {
    $kind = if ($item.PSIsContainer) { 'dir ' } else { 'file' }
    $size = if ($item.PSIsContainer) { '' } else { $item.Length }
    Write-Output ("//   {0} {1,-40} {2}" -f $kind, $item.Name, $size)
}
Write-Output ''

# library.zip's table of contents: names only, opened read-only in place and never extracted.
$zips = @($files | Where-Object { $_.Extension -ieq '.zip' })
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($zipFile in $zips) {
    Write-Output "// ---- table of contents of $($zipFile.FullName) ($($zipFile.Length) bytes)"
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipFile.FullName)
    try {
        $entries = @($zip.Entries)
        Write-Output "//   $($entries.Count) entries"
        $entries | Group-Object { [System.IO.Path]::GetExtension($_.FullName).ToLowerInvariant() } |
            Sort-Object Count -Descending | ForEach-Object {
                Write-Output ("//   {0,-8} {1}" -f $(if ($_.Name) { $_.Name } else { '(none)' }), $_.Count)
            }
        Write-Output '//   first 15 entries:'
        foreach ($e in ($entries | Select-Object -First 15)) { Write-Output "//     $($e.FullName)  $($e.Length)" }
        Write-Output '//   entries whose path mentions UIA:'
        foreach ($e in ($entries | Where-Object { $_.FullName -match 'UIA' } | Select-Object -First 40)) {
            Write-Output "//     $($e.FullName)  $($e.Length)"
        }
    } finally {
        $zip.Dispose()
    }
    Write-Output ''
}
if ($zips.Count -eq 0) { Write-Output '// (no .zip in the install)' }
