param(
    [string]$UnsignedApk = (Join-Path $PSScriptRoot 'app/build/outputs/apk/release/app-release-unsigned.apk'),
    [string]$Keystore = (Join-Path $env:USERPROFILE '.android\RupeeRaid-release.p12')
)
$ErrorActionPreference = 'Stop'
$inputFile = (Resolve-Path -LiteralPath $UnsignedApk).Path
if ($inputFile -notlike '*-unsigned.apk') { throw 'Select a release APK ending in -unsigned.apk.' }
$outputFile = $inputFile -replace '-unsigned\.apk$', '.apk'
if (Test-Path -LiteralPath $outputFile) { throw "A signed output already exists at $outputFile." }
$sdk = $env:ANDROID_HOME
if (!$sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$signer = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (!$signer) { throw 'Install Android SDK Build Tools in Android Studio.' }
if (!$env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr' }
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (!(Test-Path -LiteralPath $keytool)) { throw 'Set JAVA_HOME to your Android Studio JDK.' }
if (!(Test-Path -LiteralPath $Keystore)) {
    $keyDir = Split-Path -Parent ([IO.Path]::GetFullPath($Keystore))
    [IO.Directory]::CreateDirectory($keyDir) | Out-Null
    Write-Host "Creating your release signing key at $Keystore. Save the password in your password manager and back up the keystore."
    & $keytool -genkeypair -storetype PKCS12 -keystore $Keystore -alias rupeeraid-release -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=RupeeRaid Release'
    if ($LASTEXITCODE -ne 0) { throw 'Key generation did not finish.' }
}
& $signer sign --ks $Keystore --ks-key-alias rupeeraid-release --v4-signing-enabled false --out $outputFile $inputFile
if ($LASTEXITCODE -ne 0) { throw 'APK signing did not finish.' }
& $signer verify --verbose --print-certs $outputFile
if ($LASTEXITCODE -ne 0) { throw 'APK verification failed.' }
Write-Host "Verified signed APK at $outputFile"
Get-FileHash -Algorithm SHA256 -LiteralPath $outputFile
