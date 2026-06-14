param(
    [string] $OutputDirectory = (Join-Path $HOME ".rikkahub-signing"),
    [string] $Alias = "rikkahub-sideload",
    [int] $ValidityDays = 10000,
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

$keytoolCommand = Get-Command keytool -ErrorAction SilentlyContinue
$keytool = if ($null -ne $keytoolCommand) { $keytoolCommand.Source } else { $null }
if (-not $keytool) {
    throw "keytool was not found. Install a JDK and ensure keytool is in PATH."
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
