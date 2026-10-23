<#
 run_tests.ps1 - runs the CacheFlow load tests end to end and logs everything.

 Put it in the same folder as loadtest.js, then run:
     powershell -ExecutionPolicy Bypass -File .\run_tests.ps1

 Before starting: origin.py running on :9000, CacheFlow running on :8080.

 Flow:
   1. Baseline: k6 straight to the origin (50 users, 30s)
   2. For each load level (1, 5, 10, 25, 50 users):
        - asks you to restart CacheFlow (cold cache) and waits for your Enter
        - waits until the proxy answers, resets the origin counter
        - runs k6 through the proxy, reads origin hits, computes hit rate

 Output goes to a new folder  results_<timestamp>\  containing:
   results.csv   one row per run (requests, req/s, failed %, latencies, hit rate)
   summary.txt   the same table, readable
   *.log         full k6 console output for each run
   *.json        k6 summary export for each run
   run_info.txt  parameters and k6 version

 Options:
   -Keys 10000          harsher key space (lower hit rate)
   -Levels 1,5,10       choose your own user counts
   -StopOnFailure       stop at the first level that has failed requests
   -OriginUrl / -ProxyUrl  if your ports differ
#>
param(
    [string]$OriginUrl = "http://localhost:9000",
    [string]$ProxyUrl  = "http://localhost:8080",
    [int]$Keys = 100,
    [int[]]$Levels = @(1, 5, 10, 25, 50),
    [switch]$StopOnFailure
)

$ErrorActionPreference = "Stop"
$inv = [System.Globalization.CultureInfo]::InvariantCulture

$baseDir = $PSScriptRoot
if (-not $baseDir) { $baseDir = (Get-Location).Path }
$testScript = Join-Path $baseDir "loadtest.js"
if (-not (Test-Path $testScript)) {
    Write-Error "loadtest.js not found next to this script ($baseDir)."
}

$stamp  = Get-Date -Format "yyyyMMdd_HHmmss"
$outDir = Join-Path $baseDir "results_$stamp"
New-Item -ItemType Directory -Path $outDir | Out-Null
$csvFile = Join-Path $outDir "results.csv"

# ---------- helpers ----------

function Get-Duration([int]$vus) {
    if ($vus -ge 50) { return "30s" } else { return "10s" }
}

function Reset-Origin {
    Invoke-RestMethod -Method Post -Uri "$OriginUrl/__reset" | Out-Null
}

function Get-OriginHits {
    return [int](Invoke-RestMethod -Uri "$OriginUrl/__stats").origin_hits
}

function Wait-Proxy {
    # /item/0 is outside the load test's key range (1..KEYS), so it does not
    # warm any key the test uses. The origin counter is reset after this probe.
    for ($i = 0; $i -lt 60; $i++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri "$ProxyUrl/item/0" -TimeoutSec 5 | Out-Null
            return $true
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    return $false
}

function Get-MetricValues($json, [string]$name) {
    $m = $json.metrics.$name
    if ($null -eq $m) { return $null }
    if ($null -ne $m.values) { return $m.values }
    return $m
}

function Read-K6Summary([string]$jsonFile) {
    $r = [pscustomobject]@{
        Reqs = [double]::NaN; Rate = [double]::NaN; FailedPct = [double]::NaN
        Avg = [double]::NaN; Med = [double]::NaN; P90 = [double]::NaN
        P95 = [double]::NaN; Max = [double]::NaN
    }
    try {
        $j = Get-Content $jsonFile -Raw | ConvertFrom-Json
        $reqs = Get-MetricValues $j "http_reqs"
        $dur  = Get-MetricValues $j "http_req_duration"
        $fail = Get-MetricValues $j "http_req_failed"
        $r.Reqs = [double]$reqs.count
        $r.Rate = [double]$reqs.rate
        $r.Avg  = [double]$dur.avg
        $r.Med  = [double]$dur.med
        $r.P90  = [double]$dur.'p(90)'
        $r.P95  = [double]$dur.'p(95)'
        $r.Max  = [double]$dur.max
        if ($null -ne $fail.rate)      { $r.FailedPct = [double]$fail.rate * 100 }
        elseif ($null -ne $fail.value) { $r.FailedPct = [double]$fail.value * 100 }
    } catch {
        Write-Warning "Could not parse $jsonFile ($($_.Exception.Message)). The .log file still has the numbers."
    }
    return $r
}

function Invoke-K6([string]$label, [string]$baseUrl, [int]$vus, [string]$dur) {
    $logFile  = Join-Path $outDir "$label.log"
    $jsonFile = Join-Path $outDir "$label.json"
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"   # k6 writes progress to stderr
    & k6 run --quiet --summary-export $jsonFile `
        -e "BASE_URL=$baseUrl" -e "VUS=$vus" -e "DURATION=$dur" -e "KEYS=$Keys" `
        $testScript 2>&1 | ForEach-Object { "$_" } | Tee-Object -FilePath $logFile | Out-Host
    $ErrorActionPreference = $prev
    return (Read-K6Summary $jsonFile)
}

function Round2($x) {
    if ([double]::IsNaN($x)) { return $x }
    return [math]::Round($x, 2)
}

function New-Row([string]$scenario, [int]$vus, [string]$dur, $m, [int]$originHits) {
    $hit = ""
    if ($scenario -ne "baseline" -and $m.Reqs -gt 0) {
        $hit = [math]::Round((1 - ($originHits / $m.Reqs)) * 100, 2)
    }
    return [pscustomobject]@{
        Scenario   = $scenario
        VUs        = $vus
        Duration   = $dur
        Keys       = $Keys
        Requests   = $m.Reqs
        ReqPerSec  = Round2 $m.Rate
        FailedPct  = Round2 $m.FailedPct
        AvgMs      = Round2 $m.Avg
        MedMs      = Round2 $m.Med
        P90Ms      = Round2 $m.P90
        P95Ms      = Round2 $m.P95
        MaxMs      = Round2 $m.Max
        OriginHits = $originHits
        HitRatePct = $hit
    }
}

# ---------- preflight ----------

Write-Host ""
Write-Host "CacheFlow load test - results will be saved in: $outDir" -ForegroundColor Cyan

try {
    $null = Get-OriginHits
} catch {
    Write-Error "Origin is not reachable at $OriginUrl. Start it with: python origin.py"
}

$k6ver = (& k6 version 2>&1 | Out-String).Trim()
@(
    "Run started : $(Get-Date -Format s)",
    "Origin URL  : $OriginUrl",
    "Proxy URL   : $ProxyUrl",
    "Key space   : $Keys",
    "Levels      : $($Levels -join ', ')",
    "k6          : $k6ver"
) | Set-Content (Join-Path $outDir "run_info.txt")

$results = @()

# ---------- 1. baseline ----------

Write-Host ""
Write-Host "=== Baseline: straight to origin (50 users, 30s) ===" -ForegroundColor Yellow
Reset-Origin
$m = Invoke-K6 "baseline_origin" $OriginUrl 50 "30s"
$hits = Get-OriginHits
$results += New-Row "baseline" 50 "30s" $m $hits
$results | Export-Csv -Path $csvFile -NoTypeInformation

# ---------- 2. through the proxy ----------

foreach ($v in $Levels) {
    $dur = Get-Duration $v
    Write-Host ""
    Write-Host "=== Proxy run: $v users, $dur ===" -ForegroundColor Yellow
    $ans = Read-Host "Restart CacheFlow now (so the cache is cold). Press Enter once it is up, or type Q to stop here"
    if ($ans -match '^[qQ]') { Write-Host "Stopping early at your request."; break }

    if (-not (Wait-Proxy)) {
        Write-Warning "Proxy did not answer at $ProxyUrl within 60s. Stopping."
        break
    }
    # Warm-up: a freshly started JVM is slow for its first seconds (class loading, JIT).
    # These keys are far outside the test range, and the counter is reset right after.
    Write-Host "Warming up the proxy (30 requests outside the test key range)..."
    1..30 | ForEach-Object {
        try { Invoke-WebRequest -UseBasicParsing -Uri "$ProxyUrl/item/$(900000 + $_)" -TimeoutSec 10 | Out-Null } catch { }
    }
    Reset-Origin
    $m = Invoke-K6 ("proxy_{0}vus" -f $v) $ProxyUrl $v $dur
    $hits = Get-OriginHits
    $row = New-Row "proxy" $v $dur $m $hits
    $results += $row
    $results | Export-Csv -Path $csvFile -NoTypeInformation

    Write-Host ("-> {0} users: {1} req/s, p95 {2} ms, failed {3}%, origin hits {4}, hit rate {5}%" -f `
        $v, $row.ReqPerSec, $row.P95Ms, $row.FailedPct, $hits, $row.HitRatePct) -ForegroundColor Green

    if ($StopOnFailure -and $row.FailedPct -gt 0) {
        Write-Warning "Failures at $v users - stopping because -StopOnFailure was set."
        break
    }
}

# ---------- summary ----------

Write-Host ""
Write-Host "=== Summary ===" -ForegroundColor Cyan
$table = $results | Format-Table -AutoSize | Out-String
Write-Host $table
$table | Set-Content (Join-Path $outDir "summary.txt")
Write-Host "Saved to: $outDir"