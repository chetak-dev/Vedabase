param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-z0-9-]+$')][string]$Capture,
    [string]$TapText,
    [string]$TapId,
    [switch]$HierarchyOnly,
    [switch]$Back
)
# User-authorized design inspection only. Never changes settings or library data.
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$folder = Join-Path (Split-Path $PSScriptRoot -Parent) '.reference'
$remote = '/data/local/tmp/vedareader-transcend-inspect'
$local = Join-Path $folder $Capture
function Invoke-Adb([string[]]$Arguments) {
    $result = & $adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB operation failed: $($Arguments -join ' ')" }
    return $result
}
function Assert-Foreground {
    $activity = Invoke-Adb @('shell', 'dumpsys', 'activity', 'activities')
    if (-not ($activity | Select-String '(topResumedActivity|mResumedActivity).*com\.bbt\.transcend/')) {
        throw 'Transcend is not foreground; no interaction performed.'
    }
}
function Read-Ui {
    Assert-Foreground
    $null = Invoke-Adb @('shell', 'uiautomator', 'dump', "$remote.xml")
    $null = Invoke-Adb @('pull', "$remote.xml", "$local.xml")
    return [xml][IO.File]::ReadAllText("$local.xml", [Text.Encoding]::UTF8)
}
try {
    $tree = Read-Ui
    if ($Back) {
        $null = Invoke-Adb @('shell', 'input', 'keyevent', '4')
        $tree = Read-Ui
    } elseif ($TapText -or $TapId) {
        $targets = @($tree.SelectNodes('//node[@package="com.bbt.transcend"]') | Where-Object {
            (($TapText -and $_.GetAttribute('text') -eq $TapText) -or ($TapId -and $_.GetAttribute('resource-id') -eq $TapId)) -and
            $_.GetAttribute('bounds') -ne '[0,0][0,0]' -and $_.GetAttribute('enabled') -eq 'true'
        })
        if ($targets.Count -ne 1) { throw "Expected one visible control; found $($targets.Count)." }
        $bounds = [regex]::Match($targets[0].GetAttribute('bounds'), '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
        if (-not $bounds.Success) { throw 'Invalid control bounds.' }
        $x = [int](([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2)
        $y = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
        Assert-Foreground
        $null = Invoke-Adb @('shell', 'input', 'tap', "$x", "$y")
        $tree = Read-Ui
    }
    Assert-Foreground
    if (-not $HierarchyOnly) {
        $null = Invoke-Adb @('shell', 'screencap', '-p', "$remote.png")
        $null = Invoke-Adb @('pull', "$remote.png", "$local.png")
        Write-Output "Screenshot: $local.png"
    }
    Write-Output "Hierarchy: $local.xml"
    $tree.SelectNodes('//node[@package="com.bbt.transcend"][@text!="" or @content-desc!="" or @clickable="true"]') |
        Where-Object { $_.GetAttribute('bounds') -ne '[0,0][0,0]' } | ForEach-Object {
            $text = $_.GetAttribute('text')
            if ($text.Length -gt 130) { $text = '[body omitted]' }
            $json = [pscustomobject]@{ text=$text; description=$_.GetAttribute('content-desc'); id=$_.GetAttribute('resource-id'); bounds=$_.GetAttribute('bounds') } | ConvertTo-Json -Compress
            [regex]::Replace($json, '[^\x20-\x7e]', { param($m) '\u{0:x4}' -f [int][char]$m.Value })
        }
} finally { $null = Invoke-Adb @('shell', 'rm', '-f', "$remote.xml", "$remote.png") }