param(
    [string]$TargetPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools",
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ExpectedSha256,
    [switch]$ConfirmUpgrade,
    [switch]$AddToUserPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$downloadUrl = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
$target = [System.IO.Path]::GetFullPath($TargetPath)
$parent = Split-Path -Parent $target
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("tv-remote-adb-" + [guid]::NewGuid().ToString('N'))
$archive = Join-Path $temporaryRoot 'platform-tools.zip'
$expanded = Join-Path $temporaryRoot 'expanded'
$backup = $null

if ((Test-Path -LiteralPath $target) -and -not $ConfirmUpgrade) {
    throw "Target already exists; rerun only after explicit upgrade confirmation: $target"
}

try {
    New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
    Invoke-WebRequest -Uri $downloadUrl -OutFile $archive -UseBasicParsing
    $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    if ($actualHash -ne $ExpectedSha256.ToUpperInvariant()) {
        throw "Platform Tools checksum mismatch. Expected $ExpectedSha256, received $actualHash"
    }

    Expand-Archive -LiteralPath $archive -DestinationPath $expanded
    $candidate = Join-Path $expanded 'platform-tools'
    $candidateAdb = Join-Path $candidate 'adb.exe'
    if (-not (Test-Path -LiteralPath $candidateAdb)) {
        throw 'Downloaded archive does not contain platform-tools\adb.exe'
    }
    & $candidateAdb version | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Downloaded adb.exe failed validation with exit code $LASTEXITCODE"
    }

    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    if (Test-Path -LiteralPath $target) {
        $backup = "$target.backup-$(Get-Date -Format 'yyyyMMddHHmmss')"
        Move-Item -LiteralPath $target -Destination $backup
    }
    Move-Item -LiteralPath $candidate -Destination $target

    if ($AddToUserPath) {
        $currentPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        $entries = @($currentPath -split ';' | Where-Object { $_ })
        if ($target -notin $entries) {
            [Environment]::SetEnvironmentVariable('Path', (($entries + $target) -join ';'), 'User')
        }
    }

    Write-Output "ADB installed at: $target"
    if ($backup) { Write-Output "Previous version backed up at: $backup" }
}
catch {
    if ($backup -and -not (Test-Path -LiteralPath $target) -and (Test-Path -LiteralPath $backup)) {
        Move-Item -LiteralPath $backup -Destination $target
    }
    throw
}
finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
