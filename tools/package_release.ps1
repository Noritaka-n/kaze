[CmdletBinding()]
param(
    [string]$Version,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\release"),
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$shell = Join-Path $root "src\shell"
$relay = Join-Path $root "src\relay-cpp"
$buildFile = Join-Path $shell "build.gradle.kts"
$gradle = Join-Path $shell "gradlew.bat"

if (-not (Test-Path $gradle)) { throw "Gradle wrapper not found: $gradle" }
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    throw "Set JAVA_HOME to a JDK 21 installation before packaging."
}

$buildText = Get-Content $buildFile -Raw
$match = [regex]::Match($buildText, 'packageVersion\s*=\s*"([^"]+)"')
if (-not $match.Success) { throw "packageVersion was not found in $buildFile" }
$projectVersion = $match.Groups[1].Value
if (-not $Version) { $Version = $projectVersion }
if ($Version -ne $projectVersion) {
    throw "Requested version $Version does not match packageVersion $projectVersion. Update build.gradle.kts first."
}

$msysCandidates = @(
    $env:KAZE_MSYS,
    (Join-Path $root "tools\msys64"),
    (Join-Path $root "..\tools\msys64"),
    (Join-Path $env:USERPROFILE "msys64")
) | Where-Object { $_ -and (Test-Path (Join-Path $_ "mingw64\bin\g++.exe")) }
$msys = $msysCandidates | Select-Object -First 1
if (-not $msys) { throw "MSYS2 not found. Set KAZE_MSYS to the msys64 root." }
$env:KAZE_MSYS = $msys

Write-Host "[package] build relay"
& (Join-Path $relay "build.ps1") -Clean
if ($LASTEXITCODE -ne 0) { throw "Relay build failed: $LASTEXITCODE" }
& (Join-Path $relay "build\kaze-relay.exe") --selftest
if ($LASTEXITCODE -ne 0) { throw "Relay self-test failed: $LASTEXITCODE" }

$resources = Join-Path $shell "src\main\resources"
$runtimeZip = Join-Path $resources "kaze-relay-runtime.zip"
New-Item -ItemType Directory -Force $resources | Out-Null
if (Test-Path $runtimeZip) { Remove-Item $runtimeZip -Force }
Compress-Archive -Path (Join-Path $relay "build\*") -DestinationPath $runtimeZip -CompressionLevel Optimal

Write-Host "[package] test and create distribution"
# Compose validates this input even for app-image. WiX tasks are excluded below;
# the empty generated directory is only validation input and is never packaged.
New-Item -ItemType Directory -Force (Join-Path $shell "build\wix311") | Out-Null
$gradleArgs = @("-p", $shell, "--no-daemon", "--warning-mode", "all")
if (-not $SkipTests) { $gradleArgs += "test" }
$gradleArgs += @("createDistributable", "-x", "downloadWix", "-x", "unzipWix")
& $gradle @gradleArgs
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed: $LASTEXITCODE" }

$distribution = Join-Path $shell "build\compose\binaries\main\app\Kaze"
if (-not (Test-Path (Join-Path $distribution "Kaze.exe"))) {
    throw "Distribution was not created at $distribution"
}

$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$bundleName = "Kaze-$Version-win-x64"
$bundle = Join-Path $OutputDirectory $bundleName
$archive = Join-Path $OutputDirectory "$bundleName.zip"
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
if (Test-Path $bundle) { Remove-Item $bundle -Recurse -Force }
if (Test-Path $archive) { Remove-Item $archive -Force }
New-Item -ItemType Directory -Force $bundle | Out-Null

Copy-Item (Join-Path $distribution "*") $bundle -Recurse -Force
foreach ($destination in @((Join-Path $bundle "adapters"), (Join-Path $bundle "app\adapters"))) {
    # The second copy supports launchers whose working directory is app/.
    & robocopy (Join-Path $root "adapters") $destination /E /XD __pycache__ /XF *.pyc *.pyo *.log settings.json relay.json | Out-Host
    if ($LASTEXITCODE -gt 7) { throw "Failed to stage adapters: $LASTEXITCODE" }
}
foreach ($name in @("LICENSE", "README.md", "THIRD-PARTY-NOTICES.md")) {
    $source = Join-Path $root $name
    if (Test-Path $source) { Copy-Item $source (Join-Path $bundle $name) -Force }
}

Write-Host "[package] archive $archive"
Compress-Archive -Path $bundle -DestinationPath $archive -CompressionLevel Optimal
$hash = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant()
$hashFile = "$archive.sha256"
[System.IO.File]::WriteAllText($hashFile, "$hash  $bundleName.zip`n", [System.Text.UTF8Encoding]::new($false))

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($archive)
try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
    foreach ($required in @("$bundleName/Kaze.exe", "$bundleName/LICENSE", "$bundleName/adapters/mvmz/mvmz_adapter.py")) {
        if ($names -notcontains $required) { throw "Archive is missing $required" }
    }
    $forbidden = $names | Where-Object { $_ -match '(__pycache__|\.py[co]$|\.log$|settings\.json$|relay\.json$)' }
    if ($forbidden) { throw "Archive contains local state: $($forbidden -join ', ')" }
} finally {
    $zip.Dispose()
}

Write-Host "[package] ok"
Write-Host "  archive: $archive"
Write-Host "  sha256:  $hash"
Write-Host "  hash file: $hashFile"
