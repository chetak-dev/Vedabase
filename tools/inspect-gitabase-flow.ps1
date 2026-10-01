param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-z0-9-]+$')][string]$Capture,
    [string]$TapText,
    [string]$TapId,
    [switch]$Back
)

# Reference inspection only: observed UI taps/back and screenshots, never settings or source data.
$ErrorActionPreference = 'Stop'
$adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$referenceDir = Join-Path (Split-Path $PSScriptRoot -Parent) '.reference'
$remoteXml = '/data/local/tmp/vedareader-gitabase-flow.xml'
$remotePng = '/data/local/tmp/vedareader-gitabase-flow.png'
$localXml = Join-Path $referenceDir "$Capture.xml"
$localPng = Join-Path $referenceDir "$Capture.png"

function Invoke-GitaAdb([string[]]$Arguments) {
    $result = & $adbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB operation failed: $($Arguments -join ' ')" }
    return $result
}
function Assert-GitaForeground {
    $activity = Invoke-GitaAdb @('shell', 'dumpsys', 'activity', 'activities')
    if (-not ($activity | Select-String '(topResumedActivity|mResumedActivity).*com\.gbpackage\.reader/')) {
        throw 'Gitabase is not foreground; no interaction or capture performed.'
    }
}
function Read-GitaUi {
    Assert-GitaForeground
    $null = Invoke-GitaAdb @('shell', 'uiautomator', 'dump', $remoteXml)
    $null = Invoke-GitaAdb @('pull', $remoteXml, $localXml)
    return [xml][IO.File]::ReadAllText($localXml, [Text.Encoding]::UTF8)
}

try {
    $tree = Read-GitaUi
    if ($Back -and ($TapText -or $TapId)) { throw 'Choose one interaction only.' }
    if ($Back) {
        Assert-GitaForeground
        $null = Invoke-GitaAdb @('shell', 'input', 'keyevent', '4')
        $tree = Read-GitaUi
    } elseif ($TapText -or $TapId) {
        $targets = @($tree.SelectNodes('//node[@package="com.gbpackage.reader"]') | Where-Object {
            (($_.GetAttribute('text') -eq $TapText -and $TapText) -or
             ($_.GetAttribute('resource-id') -eq $TapId -and $TapId)) -and
            $_.GetAttribute('bounds') -ne '[0,0][0,0]' -and $_.GetAttribute('enabled') -eq 'true'
        })
        if ($targets.Count -ne 1) { throw "Expected one visible matching control; got $($targets.Count)." }
        $bounds = [regex]::Match($targets[0].GetAttribute('bounds'), '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
        if (-not $bounds.Success) { throw 'Invalid target bounds.' }
        $x = [int](([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2)
        $y = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
        Assert-GitaForeground
        $null = Invoke-GitaAdb @('shell', 'input', 'tap', "$x", "$y")
        $tree = Read-GitaUi
    }
    Assert-GitaForeground
    $null = Invoke-GitaAdb @('shell', 'screencap', '-p', $remotePng)
    $null = Invoke-GitaAdb @('pull', $remotePng, $localPng)
    Write-Output "Screenshot: $localPng"
    Write-Output "Hierarchy: $localXml"
    $tree.SelectNodes('//node[@package="com.gbpackage.reader"][@text!="" or @content-desc!="" or @clickable="true"]') |
        Where-Object { $_.GetAttribute('bounds') -ne '[0,0][0,0]' } | ForEach-Object {
            $text = $_.GetAttribute('text')
            # Long body paragraphs aren't needed for a navigation/layout inspection.
            if ($text.Length -gt 140) { $text = '[body text omitted]' }
            $row = [pscustomobject]@{ text = $text; description = $_.GetAttribute('content-desc');
                id = $_.GetAttribute('resource-id'); clickable = $_.GetAttribute('clickable'); bounds = $_.GetAttribute('bounds') } |
                ConvertTo-Json -Compress
            [regex]::Replace($row, '[^\x20-\x7e]', { param($character) '\u{0:x4}' -f [int][char]$character.Value })
        }
} finally {
    $null = Invoke-GitaAdb @('shell', 'rm', '-f', $remoteXml, $remotePng)
}