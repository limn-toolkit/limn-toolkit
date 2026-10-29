# windows-icon.ps1: the Limn mark as a Windows icon resource for the demo's executable.
#
# Writes limn-demo.ico from the mark's rasterised sizes in site/src/brand, the light variant (dark
# ink, for Windows' light default), and a limn-demo.res that build.cmd hands to the linker. The
# resource is named GLFW_ICON because GLFW gives every window it makes the icon of that name in
# the executable, so Explorer, the taskbar and the window's title bar all show the one mark.
# Each size goes in as the PNG it already is, which every Windows from Vista on reads.
param([Parameter(Mandatory)][string]$Out)
$ErrorActionPreference = 'Stop'
$brand = Join-Path $PSScriptRoot '..\..\site\src\brand'
$sizes = 16, 24, 32, 48, 64, 128, 256
$pngs = $sizes | ForEach-Object { , [IO.File]::ReadAllBytes((Join-Path $brand "limn-symbol-light-$_.png")) }

$ico = Join-Path $Out 'limn-demo.ico'
$stream = [IO.File]::Create($ico)
$writer = New-Object IO.BinaryWriter $stream
$writer.Write([uint16]0)              # reserved
$writer.Write([uint16]1)              # an icon, not a cursor
$writer.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $side = [byte]($sizes[$i] % 256)  # 256 is written as 0
    $writer.Write($side); $writer.Write($side)
    $writer.Write([byte]0); $writer.Write([byte]0)  # no palette, reserved
    $writer.Write([uint16]1); $writer.Write([uint16]32)
    $writer.Write([uint32]$pngs[$i].Length); $writer.Write([uint32]$offset)
    $offset += $pngs[$i].Length
}
foreach ($png in $pngs) { $writer.Write($png) }
$writer.Close()

$rc = Join-Path $Out 'limn-demo.rc'
Set-Content -Path $rc -Encoding ascii -Value ('GLFW_ICON ICON "' + ((Resolve-Path $ico).Path -replace '\\', '\\') + '"')
& rc.exe /nologo /fo (Join-Path $Out 'limn-demo.res') $rc
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
