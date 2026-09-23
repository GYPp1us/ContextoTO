param(
    [ValidatePattern('^emulator-[0-9]+$')][string]$Serial = 'emulator-5554',
    [ValidateRange(320, 4000)][int]$Width = 1080,
    [ValidateRange(480, 8000)][int]$Height = 2376,
    [ValidateRange(120, 800)][int]$Density = 440,
    [switch]$Reset
)

$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' } else { 'adb' }
if (-not (Get-Command $adb -ErrorAction SilentlyContinue)) { throw "adb not found: $adb" }
& $adb -s $Serial get-state | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Emulator is not ready: $Serial" }

if ($Reset) {
    & $adb -s $Serial shell wm size reset
    if ($LASTEXITCODE -ne 0) { throw 'Failed to reset emulator size' }
    & $adb -s $Serial shell wm density reset
    if ($LASTEXITCODE -ne 0) { throw 'Failed to reset emulator density' }
} else {
    & $adb -s $Serial shell wm size "${Width}x${Height}"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to set emulator size' }
    & $adb -s $Serial shell wm density $Density
    if ($LASTEXITCODE -ne 0) { throw 'Failed to set emulator density' }
}

& $adb -s $Serial shell wm size
& $adb -s $Serial shell wm density
