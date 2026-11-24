[CmdletBinding()]
param(
    [string]$Version,
    [string]$OutputDirectory,
    [switch]$SkipTests,
    [switch]$AllowPythonPrerelease
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $root "release" }
$shell = Join-Path $root "src\shell"
$relay = Join-Path $root "src\relay-cpp"
$buildFile = Join-Path $shell "build.gradle.kts"
$gradle = Join-Path $shell "gradlew.bat"

if (-not (Test-Path $gradle)) { throw "Gradle wrapper not found: $gradle" }
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    throw "Set JAVA_HOME to a JDK 21 installation before packaging."
}

function Get-RequiredVersion([string]$Text, [string]$Pattern, [string]$Label) {
    $match = [regex]::Match($Text, $Pattern)
    if (-not $match.Success) { throw "$Label was not found or is not a literal version." }
    return $match.Groups[1].Value
}

function Get-ZipEntry($Zip, [string]$Name) {
    return $Zip.Entries | Where-Object { $_.FullName.Replace('\', '/') -eq $Name } | Select-Object -First 1
}

function Read-ZipText($Zip, [string]$Name) {
    $entry = Get-ZipEntry $Zip $Name
    if ($null -eq $entry) { throw "Archive is missing $Name" }
    $stream = $entry.Open()
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose(); $stream.Dispose() }
}

$buildText = Get-Content $buildFile -Raw
$projectVersion = Get-RequiredVersion $buildText 'packageVersion\s*=\s*"([^"]+)"' "packageVersion in $buildFile"
$gradleVersion = Get-RequiredVersion $buildText '(?m)^\s*version\s*=\s*"([^"]+)"' "Gradle version in $buildFile"
$relaySource = Join-Path $relay "kz_relay.cpp"
$relayVersion = Get-RequiredVersion (Get-Content $relaySource -Raw) 'constexpr\s+const\s+char\*\s+kVersion\s*=\s*"([^"]+)"' "kVersion in $relaySource"
if ($gradleVersion -ne $projectVersion -or $relayVersion -ne $projectVersion) {
    throw "Version mismatch: Gradle=$gradleVersion, package=$projectVersion, relay=$relayVersion. Update all three before packaging."
}
if (-not $Version) { $Version = $projectVersion }
if ($Version -ne $projectVersion -or $Version -ne $gradleVersion -or $Version -ne $relayVersion) {
    throw "Requested version $Version does not match Gradle/package/relay version $projectVersion."
}

$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
$python = if ($pythonCommand) { $pythonCommand.Source } else { $null }
$hasPyInstaller = $false
if ($python) {
    & $python -c "import importlib.util, sys; sys.exit(0 if importlib.util.find_spec('PyInstaller') else 1)"
    $hasPyInstaller = $LASTEXITCODE -eq 0
}
if (-not $hasPyInstaller -and -not $AllowPythonPrerelease) {
    throw "PyInstaller is required for a self-contained release. Install Python 3.11+ and tools/requirements-dev.txt, or explicitly use -AllowPythonPrerelease to require Python on user machines."
}
if (-not $hasPyInstaller -and -not $python) {
    throw "Python 3.11+ was not found. Install Python and verify it is on PATH before using -AllowPythonPrerelease."
}
if (-not $hasPyInstaller) {
    Write-Warning "This package will require Python 3.11+ on each user machine (PATH or KAZE_PYTHON)."
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

$adapterRuntime = Join-Path $shell "build\adapter-runtime\dist"
if ($hasPyInstaller) {
    Write-Host "[package] build standalone Python adapters"
    $adapterBuild = Join-Path $shell "build\adapter-runtime"
    if (Test-Path $adapterBuild) { Remove-Item $adapterBuild -Recurse -Force }
    New-Item -ItemType Directory -Force $adapterRuntime, (Join-Path $adapterBuild "work"), (Join-Path $adapterBuild "spec") | Out-Null
    $adapterSpecs = @(
        @{ Name = "kaze-mvmz-adapter"; Script = "adapters\mvmz\mvmz_adapter.py" },
        @{ Name = "kaze-memory-adapter"; Script = "adapters\memory\memory_adapter.py" }
    )
    foreach ($spec in $adapterSpecs) {
        & $python -m PyInstaller --noconfirm --clean --onefile `
            --name $spec.Name --distpath $adapterRuntime `
            --workpath (Join-Path $adapterBuild "work\$($spec.Name)") `
            --specpath (Join-Path $adapterBuild "spec") `
            --paths (Join-Path $root "adapters\renpy") `
            --paths (Join-Path $root "adapters\mvmz") `
            --hidden-import kz_wire --hidden-import kaze_client `
            (Join-Path $root $spec.Script)
        if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed for $($spec.Name): $LASTEXITCODE" }
        $builtAdapter = Join-Path $adapterRuntime "$($spec.Name).exe"
        if (-not (Test-Path $builtAdapter)) { throw "Standalone adapter was not created: $builtAdapter" }
        & $builtAdapter --help | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Standalone adapter smoke test failed: $($spec.Name)" }
    }
}

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
if ($hasPyInstaller) {
    $runtimeDestination = Join-Path $bundle "runtime-bin"
    New-Item -ItemType Directory -Force $runtimeDestination | Out-Null
    Copy-Item (Join-Path $adapterRuntime "*") $runtimeDestination -Recurse -Force
} else {
    $runtimeNotice = @"
Kaze adapter runtime prerequisite
=================================
This explicitly requested Python-prerelease build does not contain standalone adapters.
Install 64-bit Python 3.11 or newer on each user machine.
Python must be available on PATH, or KAZE_PYTHON must point to python.exe.
No pip packages are required by the included adapters.
Rebuild with tools/requirements-dev.txt installed to produce standalone runtime-bin adapters.
"@
    [System.IO.File]::WriteAllText((Join-Path $bundle "RUNTIME-REQUIREMENTS.txt"), $runtimeNotice, [System.Text.UTF8Encoding]::new($false))
}
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

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($archive)
try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
    $required = @("$bundleName/Kaze.exe", "$bundleName/LICENSE", "$bundleName/adapters/mvmz/mvmz_adapter.py")
    if ($hasPyInstaller) {
        $required += @("$bundleName/runtime-bin/kaze-mvmz-adapter.exe", "$bundleName/runtime-bin/kaze-memory-adapter.exe")
    } else {
        $required += "$bundleName/RUNTIME-REQUIREMENTS.txt"
    }
    foreach ($entryName in $required) {
        if ($names -notcontains $entryName) { throw "Archive is missing $entryName" }
    }
    $mainJarPrefix = "$bundleName/app/kaze-shell-$Version"
    $mainJarName = $names | Where-Object {
        $_.StartsWith($mainJarPrefix) -and $_.EndsWith(".jar") -and
            ($_ -eq "$mainJarPrefix.jar" -or $_.Substring($mainJarPrefix.Length).StartsWith("-"))
    } | Select-Object -First 1
    if (-not $mainJarName) { throw "Archive does not contain the Gradle $Version main JAR." }
    $jpackage = [xml](Read-ZipText $zip "$bundleName/app/.jpackage.xml")
    $packagedVersion = $jpackage.'jpackage-state'.'app-version'
    if ($packagedVersion -ne $Version) { throw "Packaged app-version $packagedVersion does not match $Version." }
    $cfg = Read-ZipText $zip "$bundleName/app/Kaze.cfg"
    if ($cfg -notmatch "(?m)^java-options=-Djpackage\.app-version=$([regex]::Escape($Version))\s*$") {
        throw "Kaze.cfg app-version does not match $Version."
    }
    $jarMemory = [System.IO.MemoryStream]::new()
    $jarArchive = $null
    $runtimeMemory = $null
    $runtimeArchive = $null
    try {
        $jarEntry = Get-ZipEntry $zip $mainJarName
        $jarEntryStream = $jarEntry.Open()
        try { $jarEntryStream.CopyTo($jarMemory) } finally { $jarEntryStream.Dispose() }
        $jarMemory.Position = 0
        $jarArchive = [System.IO.Compression.ZipArchive]::new($jarMemory, [System.IO.Compression.ZipArchiveMode]::Read)
        $runtimeEntry = $jarArchive.GetEntry("kaze-relay-runtime.zip")
        if ($null -eq $runtimeEntry) { throw "Main JAR is missing kaze-relay-runtime.zip." }
        $runtimeMemory = [System.IO.MemoryStream]::new()
        $runtimeEntryStream = $runtimeEntry.Open()
        try { $runtimeEntryStream.CopyTo($runtimeMemory) } finally { $runtimeEntryStream.Dispose() }
        $runtimeMemory.Position = 0
        $runtimeArchive = [System.IO.Compression.ZipArchive]::new($runtimeMemory, [System.IO.Compression.ZipArchiveMode]::Read)
        if ($null -eq $runtimeArchive.GetEntry("kaze-relay.exe")) { throw "Embedded relay runtime is missing kaze-relay.exe." }
    } finally {
        if ($runtimeArchive) { $runtimeArchive.Dispose() }
        if ($runtimeMemory) { $runtimeMemory.Dispose() }
        if ($jarArchive) { $jarArchive.Dispose() }
        $jarMemory.Dispose()
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
