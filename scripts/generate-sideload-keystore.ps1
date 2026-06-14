param(
    [string] $OutputDirectory = (Join-Path $HOME ".rikkahub-signing"),
    [string] $Alias = "rikkahub-sideload",
    [int] $ValidityDays = 10000,
    [string] $KeytoolPath,
    [switch] $Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function New-Password {
    param([int] $ByteCount = 32)

    $bytes = New-Object byte[] $ByteCount
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Get-KeytoolCandidates {
    $candidateRoots = @(
        $env:JAVA_HOME,
        $env:JDK_HOME,
        $env:ANDROID_STUDIO_JBR,
        "${env:ProgramFiles}\Android\Android Studio\jbr",
        "${env:ProgramFiles}\Android\Android Studio\jre",
        "${env:ProgramFiles}\Java",
        "${env:ProgramFiles}\Eclipse Adoptium",
        "${env:ProgramFiles}\Microsoft",
        "${env:ProgramFiles}\Zulu",
        "${env:ProgramFiles(x86)}\Java"
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    $directCandidates = @()
    if (-not [string]::IsNullOrWhiteSpace($KeytoolPath)) {
        $directCandidates += $KeytoolPath
    }

    $pathCommand = Get-Command keytool -ErrorAction SilentlyContinue
    if ($null -ne $pathCommand) {
        $directCandidates += $pathCommand.Source
    }

    foreach ($candidate in $directCandidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            Get-Item -LiteralPath $candidate
        }
    }

    foreach ($root in $candidateRoots) {
        if (-not (Test-Path -LiteralPath $root)) {
            continue
        }

        $binKeytool = Join-Path $root "bin\keytool.exe"
        if (Test-Path -LiteralPath $binKeytool -PathType Leaf) {
            Get-Item -LiteralPath $binKeytool
        }

        Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
            ForEach-Object {
                $nestedKeytool = Join-Path $_.FullName "bin\keytool.exe"
                if (Test-Path -LiteralPath $nestedKeytool -PathType Leaf) {
                    Get-Item -LiteralPath $nestedKeytool
                }
            }
    }
}

$keytool = Get-KeytoolCandidates |
    Select-Object -ExpandProperty FullName -Unique |
    Select-Object -First 1

if (-not $keytool) {
    throw @"
keytool was not found.

Install a JDK, then rerun this script. Recommended options:
  winget install EclipseAdoptium.Temurin.17.JDK
  winget install Microsoft.OpenJDK.17

If a JDK is already installed, either set JAVA_HOME or pass keytool explicitly:
  `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.x.x"
  .\scripts\generate-sideload-keystore.ps1

  .\scripts\generate-sideload-keystore.ps1 -KeytoolPath "C:\Path\To\jdk\bin\keytool.exe"
"@
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

$keystorePath = Join-Path $OutputDirectory "rikkahub-sideload.keystore"
$secretsPath = Join-Path $OutputDirectory "github-secrets.txt"

if ((Test-Path $keystorePath) -and -not $Force) {
    throw "Keystore already exists: $keystorePath. Pass -Force to replace it."
}

$storePassword = New-Password
$keyPassword = $storePassword

& $keytool -genkeypair `
    -v `
    -storetype PKCS12 `
    -keystore $keystorePath `
    -storepass $storePassword `
    -keypass $keyPassword `
    -alias $Alias `
    -keyalg RSA `
    -keysize 4096 `
    -validity $ValidityDays `
    -dname "CN=RikkaHub Sideload,O=RikkaHub,C=CN"

$keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))

@(
    "SIDELOAD_KEYSTORE_BASE64=$keystoreBase64"
    "SIDELOAD_KEYSTORE_PASSWORD=$storePassword"
    "SIDELOAD_KEY_ALIAS=$Alias"
    "SIDELOAD_KEY_PASSWORD=$keyPassword"
) | Set-Content -Path $secretsPath -Encoding UTF8

Write-Host "Keystore created: $keystorePath"
Write-Host "GitHub Secrets file created: $secretsPath"
Write-Host "Add each line in github-secrets.txt as a repository secret, then store this directory securely."
