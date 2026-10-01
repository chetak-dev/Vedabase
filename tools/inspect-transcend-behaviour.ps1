param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-z0-9-]+$')][string]$Capture,
    [ValidateSet('Inspect','SwipeLeft','SwipeRight','SwipeUp','SwipeDown','TapCenter','TapLeadingEdge','TapTrailingEdge')]
    [string]$Action = 'Inspect'
)
# Normal visible reader gestures only. No protected screenshots, book extraction or settings changes.
$ErrorActionPreference = 'Stop'
$adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$folder = Join-Path (Split-Path $PSScriptRoot -Parent) '.reference'
$local = Join-Path $folder "$Capture.xml"
$remote = '/data/local/tmp/transcend-behaviour.xml'
function Invoke-Adb([string[]]$Arguments) {
    $result = & $adbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')" }
    return $result
}
function Assert-Reader {
    $activity = Invoke-Adb @('shell','dumpsys','activity','activities')
    if (-not ($activity | Select-String '(topResumedActivity|mResumedActivity).*com\.bbt\.transcend/.*EbookReaderHostActivity')) {
        throw 'Transcend reader changed or is not foreground. No gesture performed.'
    }
}
function Read-Tree {
    Assert-Reader
    $null = Invoke-Adb @('shell','uiautomator','dump',$remote)
    $null = Invoke-Adb @('pull',$remote,$local)
    return [xml][IO.File]::ReadAllText($local,[Text.Encoding]::UTF8)
}
function Bounds($node) {
    $match = [regex]::Match($node.GetAttribute('bounds'), '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $match.Success) { throw 'Invalid observed bounds' }
    return @(1..4 | ForEach-Object { [int]$match.Groups[$_].Value })
}
try {
    $tree = Read-Tree
    $views = @($tree.SelectNodes('//node[@package="com.bbt.transcend"][@class="android.webkit.WebView"]') |
        Where-Object { $_.GetAttribute('bounds') -ne '[0,0][0,0]' } |
        Group-Object { $_.GetAttribute('bounds') } | ForEach-Object { $_.Group[0] })
    if ($views.Count -ne 1) { throw "Expected one visible book viewport, found $($views.Count)." }
    $rect = Bounds $views[0]
    $left = $rect[0]; $top = $rect[1]; $width = $rect[2] - $left; $height = $rect[3] - $top
    $centerX = [int]($left + $width * .5); $centerY = [int]($top + $height * .5)
    $nearX = [int]($left + $width * .22); $farX = [int]($left + $width * .78)
    $nearY = [int]($top + $height * .22); $farY = [int]($top + $height * .78)
    Assert-Reader
    switch ($Action) {
        'SwipeLeft' { $null = Invoke-Adb @('shell','input','swipe',"$farX","$centerY","$nearX","$centerY",'450') }
        'SwipeRight' { $null = Invoke-Adb @('shell','input','swipe',"$nearX","$centerY","$farX","$centerY",'450') }
        'SwipeUp' { $null = Invoke-Adb @('shell','input','swipe',"$centerX","$farY","$centerX","$nearY",'450') }
        'SwipeDown' { $null = Invoke-Adb @('shell','input','swipe',"$centerX","$nearY","$centerX","$farY",'450') }
        'TapCenter' { $null = Invoke-Adb @('shell','input','tap',"$centerX","$centerY") }
        'TapLeadingEdge' { $null = Invoke-Adb @('shell','input','tap',"$([int]($left + $width * .08))","$centerY") }
        'TapTrailingEdge' { $null = Invoke-Adb @('shell','input','tap',"$([int]($left + $width * .92))","$centerY") }
    }
    if ($Action -ne 'Inspect') { $tree = Read-Tree }
    $visible = @($tree.SelectNodes('//node[@package="com.bbt.transcend"][@text!="" or @content-desc!=""]') |
        Where-Object { $_.GetAttribute('bounds') -ne '[0,0][0,0]' })
    Write-Output "Hierarchy: $local"
    foreach ($node in $visible) {
        $text = $node.GetAttribute('text')
        $label = if ($text.Length -le 100) { $text } else { "[body: $($text.Length) characters]" }
        [pscustomobject]@{text=$label;id=$node.GetAttribute('resource-id');description=$node.GetAttribute('content-desc');bounds=$node.GetAttribute('bounds')} | ConvertTo-Json -Compress
    }
} finally { $null = Invoke-Adb @('shell','rm','-f',$remote) }