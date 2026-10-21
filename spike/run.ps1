# One measured run: its output goes to results\<name>.log, its exit code and duration to
# results\runs.txt, and a run that hangs is killed after the timeout. The step always succeeds,
# because a run that fails is a result here and not a broken workflow.
#
#   run.ps1 <name> <mesa dir or ""> <timeout seconds> -- <command line...>
param(
    [Parameter(Mandatory)][string]$Name,
    [string]$MesaDir = "",
    [int]$TimeoutSec = 240,
    [Parameter(ValueFromRemainingArguments)][string[]]$Rest
)

$log = Join-Path $PWD "results\$Name.log"
$command = ($Rest -join " ")
if ($command -notlike "java *") {
    $defines = @("-Dlimn.backend.errorDialog=false")
    if ($MesaDir) {
        $defines += "-Dmesa.dir=$MesaDir"
        $defines += "-Dorg.lwjgl.opengl.libname=$MesaDir\opengl32.dll"
    }
    $command = "java " + (($defines + "-cp" + "tools\limn-demo-all.jar;spike\classes" + "Probe" + $Rest) -join " ")
}
Write-Host "RUN $Name : $command"

$started = Get-Date
$process = Start-Process -FilePath cmd.exe -ArgumentList "/c $command > `"$log`" 2>&1" -NoNewWindow -PassThru
$null = $process.Handle  # without it ExitCode reads empty once the process is gone
if ($process.WaitForExit($TimeoutSec * 1000)) {
    $code = $process.ExitCode
} else {
    & taskkill /T /F /PID $process.Id | Out-Null
    $code = "timeout"
}
$seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)

Get-Content $log
"$Name exit=$code seconds=$seconds" | Tee-Object -Append results\runs.txt
# A failed run does not fail the step, so it is raised as an annotation on the run's page instead
# of hiding behind a green check.
if ("$code" -ne "0") {
    Write-Host "::warning title=$Name failed::exit=$code after $seconds s; the output is in results\$Name.log"
}
exit 0
