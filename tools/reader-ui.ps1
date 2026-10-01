param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$TapText,
    [string]$TapDescription
)

# Inspect/tap only VedaReader using actual accessibility bounds, never guessed coordinates.
# No device settings, source databases, or other apps are changed.
$ErrorActionPreference = 'Stop'
$adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$remoteDump = '/data/local/tmp/vedareader-browse-check.xml'
$localDump = Join-Path (Split-Path $PSScriptRoot -Parent) '.reference\vedareader-ui.xml'

function Invoke-ReaderAdb([string[]]$Arguments) {
    $output = & $adbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')" }
    return $output
}

function Assert-ReaderForeground {
    $activities = Invoke-ReaderAdb @('shell', 'dumpsys', 'activity', 'activities')
    $resumed = $activities | Select-String '(topResumedActivity|mResumedActivity).*org\.vedareader/'
    if (-not $resumed) { throw 'VedaReader is not the resumed activity; no tap performed.' }
}

function Read-ReaderUi {
    Assert-ReaderForeground
    try {
        $null = Invoke-ReaderAdb @('shell', 'uiautomator', 'dump', $remoteDump)
        $null = New-Item -ItemType Directory -Path (Split-Path $localDump -Parent) -Force
        $null = Invoke-ReaderAdb @('pull', $remoteDump, $localDump)
        return [xml][IO.File]::ReadAllText($localDump, [Text.Encoding]::UTF8)
    } finally {
        $null = Invoke-ReaderAdb @('shell', 'rm', '-f', $remoteDump)
    }
}

$ui = Read-ReaderUi
if ($TapText -or $TapDescription) {
    $matches = @($ui.SelectNodes('//node[@package="org.vedareader"]') | Where-Object {
        ($TapText -and $_.GetAttribute('text') -eq $TapText) -or
        ($TapDescription -and $_.GetAttribute('content-desc') -eq $TapDescription)
    })
    if ($matches.Count -ne 1) { throw "Expected exactly one visible matching control; found $($matches.Count)." }
    $bounds = [regex]::Match($matches[0].GetAttribute('bounds'), '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $bounds.Success) { throw 'Invalid control bounds.' }
    $tapX = [int](([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2)
    $tapY = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
    Assert-ReaderForeground
    $null = Invoke-ReaderAdb @('shell', 'input', 'tap', "$tapX", "$tapY")
    $ui = Read-ReaderUi
}
$ui.SelectNodes('//node[@package="org.vedareader"][@text!="" or @content-desc!=""]') | ForEach-Object {
    # ASCII-escaped output preserves Unicode facts despite the Windows terminal code page.
    $value = [pscustomobject]@{ text = $_.GetAttribute('text'); description = $_.GetAttribute('content-desc'); bounds = $_.GetAttribute('bounds') } | ConvertTo-Json -Compress
    [regex]::Replace($value, '[^\x20-\x7e]', { param($character) '\u{0:x4}' -f [int][char]$character.Value })
}