param(
    [switch]$Clean,
    [switch]$SelfTest
)

$ErrorActionPreference = "Stop"

$here   = Split-Path -Parent $MyInvocation.MyCommand.Path
$msysCandidates = @(
    $env:KAZE_MSYS,
    (Join-Path $here "..\..\..\tools\msys64"),
    (Join-Path $here "..\..\tools\msys64"),
    (Join-Path $env:USERPROFILE "msys64")
) | Where-Object { $_ -and (Test-Path (Join-Path $_ "mingw64\bin\g++.exe")) }
$msys = $msysCandidates | Select-Object -First 1
if (-not $msys) {
    throw "MSYS2 not found. Set KAZE_MSYS to a msys64 root containing mingw64\bin\g++.exe (and protoc)."
}
$gxx    = Join-Path $msys "mingw64\bin\g++.exe"
$protoc = Join-Path $msys "mingw64\bin\protoc.exe"
$pkgcfg = Join-Path $msys "mingw64\bin\pkg-config.exe"
$proto  = Resolve-Path (Join-Path $here "..\..\proto\kaze.proto")
$gen    = Join-Path $here "gen"
$build  = Join-Path $here "build"

if (-not (Test-Path $gxx))    { throw "g++ not found: $gxx" }
if (-not (Test-Path $protoc)) { throw "protoc not found: $protoc" }

$env:PKG_CONFIG_PATH = Join-Path $msys "mingw64\lib\pkgconfig"
$env:PATH = (Join-Path $msys "mingw64\bin") + ";" + $env:PATH

if ($Clean) {
    Remove-Item $gen, $build -Recurse -Force -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Force $gen, $build | Out-Null

Write-Host "[build] protoc -> $gen"
& $protoc "--proto_path=$(Split-Path $proto)" "--cpp_out=$gen" $proto
if ($LASTEXITCODE -ne 0) { throw "protoc failed" }

$pkgCflags = (& $pkgcfg --cflags protobuf) -split '\s+' | Where-Object { $_ }
$pkgLibs   = (& $pkgcfg --libs protobuf)   -split '\s+' | Where-Object { $_ }

$args = @(
    "-std=c++20", "-O2", "-Wall", "-Wextra"
) + $pkgCflags + @(
    "-I", $gen,
    (Join-Path $here "kz_protocol.cpp"),
    (Join-Path $here "kz_relay.cpp"),
    (Join-Path $gen "kaze.pb.cc")
) + $pkgLibs + @(
    "-lws2_32",
    "-static-libgcc", "-static-libstdc++",
    "-o", (Join-Path $build "kaze-relay.exe")
)

Write-Host "[build] g++"
& $gxx @args
if ($LASTEXITCODE -ne 0) { throw "compile failed" }

$dllPatterns = @("libprotobuf*.dll", "libabsl*.dll", "libutf8*.dll", "libgcc_s_seh-1.dll", "libstdc++-6.dll", "libwinpthread-1.dll", "zlib1.dll")
foreach ($pattern in $dllPatterns) {
    Get-ChildItem (Join-Path $msys "mingw64\bin") -Filter $pattern -ErrorAction SilentlyContinue |
        Copy-Item -Destination $build -Force
}

Write-Host "[build] ok -> $(Join-Path $build 'kaze-relay.exe')"

if ($SelfTest) {
    $env:PATH = (Join-Path $msys "mingw64\bin") + ";" + $env:PATH
    & (Join-Path $build "kaze-relay.exe") --selftest
}
