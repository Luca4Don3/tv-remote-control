param(
    [string]$TargetPath = "$env:LOCALAPPDATA\TV Remote Control\platform-tools",
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$ExpectedVersion,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 536870912)]
    [long]$ExpectedSize,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ExpectedSha256,
    [switch]$ConfirmUpgrade
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repositoryMetadataUrl = 'https://dl.google.com/android/repository/repository2-1.xml'
$repositoryBaseUrl = [uri]'https://dl.google.com/android/repository/'
$markerName = '.tv-remote-control-managed.json'
$maximumMetadataBytes = 32MB
$maximumExpandedBytes = 1GB
$maximumEntryBytes = 256MB
$maximumArchiveEntries = 4096
$networkTimeoutSeconds = 120
$processTimeoutMilliseconds = 10000

function Assert-NoReparseAncestor {
    param([Parameter(Mandatory = $true)][string]$Path)
    $current = [System.IO.Path]::GetFullPath($Path)
    while ($current) {
        if (Test-Path -LiteralPath $current) {
            $attributes = (Get-Item -LiteralPath $current -Force).Attributes
            if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Unsafe ADB path; reparse points and symbolic links are forbidden: $current"
            }
        }
        $next = Split-Path -Parent $current
        if (-not $next -or $next -eq $current) { break }
        $current = $next
    }
}

function Get-RequiredChildText {
    param(
        [Parameter(Mandatory = $true)]$Node,
        [Parameter(Mandatory = $true)][string]$LocalName
    )
    $child = $Node.SelectSingleNode("./*[local-name()='$LocalName']")
    if (-not $child -or [string]::IsNullOrWhiteSpace($child.InnerText)) {
        throw "Official repository metadata is missing $LocalName"
    }
    return $child.InnerText.Trim()
}

function Get-OptionalRevisionNumber {
    param(
        [Parameter(Mandatory = $true)]$Revision,
        [Parameter(Mandatory = $true)][string]$LocalName
    )
    $node = $Revision.SelectSingleNode("./*[local-name()='$LocalName']")
    if (-not $node) { return 0 }
    return [int]::Parse($node.InnerText.Trim(), [System.Globalization.CultureInfo]::InvariantCulture)
}

function Test-SafeZipComponent {
    param([Parameter(Mandatory = $true)][string]$Component)
    if (-not $Component -or $Component -eq '.' -or $Component -eq '..') { return $false }
    if ($Component.EndsWith('.') -or $Component.EndsWith(' ')) { return $false }
    if ($Component.IndexOfAny([char[]]'<>:"|?*') -ge 0) { return $false }
    foreach ($character in $Component.ToCharArray()) {
        if ([int]$character -lt 32) { return $false }
    }
    $stem = $Component.Split('.')[0].ToUpperInvariant()
    $reserved = @('CON', 'PRN', 'AUX', 'NUL', 'COM1', 'COM2', 'COM3', 'COM4', 'COM5', 'COM6', 'COM7', 'COM8', 'COM9', 'LPT1', 'LPT2', 'LPT3', 'LPT4', 'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9')
    return $reserved -notcontains $stem
}

$reportedArchitecture = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
$runtimeArchitecture = switch ($reportedArchitecture.ToUpperInvariant()) {
    'AMD64' { 'X64' }
    'ARM64' { 'Arm64' }
    'X86' { 'X86' }
    default { $reportedArchitecture }
}
if ($runtimeArchitecture -eq 'X86') {
    throw 'Automatic Platform Tools installation is unavailable on Windows x86; select a compatible adb.exe and complete the capability probe instead.'
}
if ($runtimeArchitecture -notin @('X64', 'Arm64')) {
    throw "Unsupported Windows architecture for automatic ADB installation: $runtimeArchitecture"
}

if ($TargetPath -notmatch '^[A-Za-z]:[\\/][^\\/]' -or
    $TargetPath.StartsWith('\\') -or
    $TargetPath.StartsWith('\\?\') -or
    $TargetPath.StartsWith('\\.\')) {
    throw "Unsafe ADB target; a normal local absolute path is required: $TargetPath"
}
$rawPathAfterDrive = $TargetPath.Substring(2)
if ($rawPathAfterDrive.Contains(':')) {
    throw "Unsafe ADB target; alternate data streams are forbidden: $TargetPath"
}
$rawComponents = @($rawPathAfterDrive.Split([char[]]@('\', '/'), [System.StringSplitOptions]::RemoveEmptyEntries))
foreach ($component in $rawComponents) {
    if (-not (Test-SafeZipComponent -Component $component)) {
        throw "Unsafe ADB target component: $component"
    }
}

$target = [System.IO.Path]::GetFullPath($TargetPath).TrimEnd('\')
$managedTarget = [System.IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'TV Remote Control\platform-tools')).TrimEnd('\')
if (-not $target.Equals($managedTarget, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Automatic installation is restricted to the application-managed directory: $managedTarget"
}
$volumeRoot = [System.IO.Path]::GetPathRoot($target).TrimEnd('\')
$parent = Split-Path -Parent $target
if ($target -eq $volumeRoot) {
    throw "Unsafe ADB target; volume roots are forbidden: $target"
}
if (-not ([System.IO.Path]::GetFileName($target)).Equals('platform-tools', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Unsafe ADB target; the final directory must be named platform-tools: $target"
}
$windowsRoot = [System.IO.Path]::GetFullPath($env:WINDIR).TrimEnd('\')
if ($target.Equals($windowsRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
    $target.StartsWith($windowsRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Unsafe ADB target; Windows system directories are forbidden: $target"
}
$systemRoots = @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:ProgramData, $env:PUBLIC) |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
    ForEach-Object { [System.IO.Path]::GetFullPath($_).TrimEnd('\') }
foreach ($systemRoot in $systemRoots) {
    if ($target.Equals($systemRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        $target.StartsWith($systemRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe ADB target; system-managed directories are forbidden: $target"
    }
}
Assert-NoReparseAncestor -Path $target

$marker = Join-Path $target $markerName
if (Test-Path -LiteralPath $target) {
    if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) {
        throw "Refusing to replace a directory not managed by TV Remote Control: $target"
    }
    if (-not $ConfirmUpgrade) {
        throw "Target already exists; rerun only after explicit upgrade confirmation: $target"
    }
    try {
        $existingMarker = Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
        if ($existingMarker.schemaVersion -notin @(1, 2) -or
            -not ([string]$existingMarker.source).StartsWith('https://dl.google.com/android/repository/')) {
            throw 'marker content is not recognized'
        }
    }
    catch {
        throw "Refusing to replace a target with invalid managed metadata: $marker"
    }
}

New-Item -ItemType Directory -Path $parent -Force | Out-Null
Assert-NoReparseAncestor -Path $parent
$temporaryRoot = Join-Path $parent ('.tv-remote-adb-staging-' + [guid]::NewGuid().ToString('N'))
$metadataFile = Join-Path $temporaryRoot 'repository.xml'
$archive = Join-Path $temporaryRoot 'platform-tools.zip'
$expanded = Join-Path $temporaryRoot 'expanded'
$backup = $null
$activated = $false

try {
    New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $repositoryMetadataUrl -OutFile $metadataFile -UseBasicParsing -TimeoutSec $networkTimeoutSeconds
    $metadataLength = (Get-Item -LiteralPath $metadataFile).Length
    if ($metadataLength -le 0 -or $metadataLength -gt $maximumMetadataBytes) {
        throw "Official repository metadata has an invalid size: $metadataLength"
    }

    $xmlSettings = [System.Xml.XmlReaderSettings]::new()
    $xmlSettings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
    $xmlSettings.XmlResolver = $null
    $xmlReader = [System.Xml.XmlReader]::Create($metadataFile, $xmlSettings)
    try {
        $repository = [System.Xml.XmlDocument]::new()
        $repository.XmlResolver = $null
        $repository.Load($xmlReader)
    }
    finally {
        $xmlReader.Dispose()
    }

    $stablePackages = @()
    $packages = $repository.SelectNodes("//*[local-name()='remotePackage' and @path='platform-tools']")
    foreach ($package in $packages) {
        $channel = $package.SelectSingleNode("./*[local-name()='channelRef']")
        if ($channel -and $channel.GetAttribute('ref') -ne 'channel-0') { continue }
        $revision = $package.SelectSingleNode("./*[local-name()='revision']")
        if (-not $revision -or $revision.SelectSingleNode("./*[local-name()='preview']")) { continue }
        $major = Get-OptionalRevisionNumber -Revision $revision -LocalName 'major'
        if ($major -le 0) { throw 'Official repository metadata contains an invalid Platform Tools major version.' }
        $minor = Get-OptionalRevisionNumber -Revision $revision -LocalName 'minor'
        $micro = Get-OptionalRevisionNumber -Revision $revision -LocalName 'micro'
        $stablePackages += [pscustomobject]@{
            Version = [Version]::new($major, $minor, $micro)
            Node = $package
        }
    }
    if ($stablePackages.Count -eq 0) {
        throw 'Official repository metadata does not contain a stable Platform Tools package.'
    }
    $selectedPackage = $stablePackages | Sort-Object -Property Version -Descending | Select-Object -First 1
    $lockedVersion = [Version]::Parse($ExpectedVersion)
    if ($selectedPackage.Version -ne $lockedVersion) {
        throw "Platform Tools lock mismatch. Official stable version is $($selectedPackage.Version); expected $lockedVersion"
    }

    $selectedArchive = $null
    foreach ($archiveNode in $selectedPackage.Node.SelectNodes("./*[local-name()='archives']/*[local-name()='archive']")) {
        $hostOs = $archiveNode.SelectSingleNode("./*[local-name()='host-os']")
        if ($hostOs -and $hostOs.InnerText.Trim() -eq 'windows') {
            $selectedArchive = $archiveNode
            break
        }
    }
    if (-not $selectedArchive) {
        throw 'Official repository metadata does not contain a stable Windows Platform Tools archive.'
    }
    $complete = $selectedArchive.SelectSingleNode("./*[local-name()='complete']")
    if (-not $complete) { throw 'Official repository metadata is missing the complete Windows archive.' }
    $archiveName = Get-RequiredChildText -Node $complete -LocalName 'url'
    $metadataSize = [long]::Parse((Get-RequiredChildText -Node $complete -LocalName 'size'), [System.Globalization.CultureInfo]::InvariantCulture)
    $metadataChecksum = Get-RequiredChildText -Node $complete -LocalName 'checksum'
    if ($metadataSize -ne $ExpectedSize) {
        throw "Platform Tools size lock mismatch. Official size is $metadataSize; expected $ExpectedSize"
    }
    if ($archiveName.Contains('/') -or $archiveName.Contains('\') -or $archiveName.Contains('..') -or
        -not $archiveName.StartsWith('platform-tools_') -or -not $archiveName.EndsWith('-win.zip')) {
        throw "Unsafe archive name in official repository metadata: $archiveName"
    }

    $downloadUri = [uri]::new($repositoryBaseUrl, $archiveName)
    if ($downloadUri.Scheme -ne 'https' -or $downloadUri.Host -ne 'dl.google.com' -or
        -not $downloadUri.AbsolutePath.StartsWith('/android/repository/') -or
        [System.IO.Path]::GetFileName($downloadUri.AbsolutePath) -ne $archiveName) {
        throw "Untrusted Platform Tools download URL: $downloadUri"
    }

    Invoke-WebRequest -Uri $downloadUri -OutFile $archive -UseBasicParsing -TimeoutSec $networkTimeoutSeconds
    $actualSize = (Get-Item -LiteralPath $archive).Length
    if ($actualSize -ne $ExpectedSize) {
        throw "Platform Tools size mismatch. Expected $ExpectedSize, received $actualSize"
    }
    $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    if ($actualHash -ne $ExpectedSha256.ToUpperInvariant()) {
        throw "Platform Tools checksum mismatch. Expected $ExpectedSha256, received $actualHash"
    }
    if ($metadataChecksum -match '^[A-Fa-f0-9]{40}$') {
        $actualRepositoryHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA1).Hash
    }
    elseif ($metadataChecksum -match '^[A-Fa-f0-9]{64}$') {
        $actualRepositoryHash = $actualHash
    }
    else {
        throw "Unsupported checksum in official repository metadata: $metadataChecksum"
    }
    if ($actualRepositoryHash -ne $metadataChecksum.ToUpperInvariant()) {
        throw "Official repository checksum mismatch. Expected $metadataChecksum, received $actualRepositoryHash"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($archive)
    try {
        if ($zip.Entries.Count -gt $maximumArchiveEntries) {
            throw "Platform Tools archive contains too many entries: $($zip.Entries.Count)"
        }
        [long]$expandedBytes = 0
        $normalizedEntries = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName
            if ([string]::IsNullOrWhiteSpace($name) -or $name.StartsWith('/') -or $name.StartsWith('\') -or $name.Contains(':')) {
                throw "Unsafe Platform Tools archive entry: $name"
            }
            $components = @($name.TrimEnd([char[]]@('/', '\')).Split([char[]]@('/', '\'), [System.StringSplitOptions]::RemoveEmptyEntries))
            if ($components.Count -eq 0 -or $components[0] -ne 'platform-tools') {
                throw "Unexpected Platform Tools archive root: $name"
            }
            foreach ($component in $components) {
                if (-not (Test-SafeZipComponent -Component $component)) {
                    throw "Unsafe Platform Tools archive entry component: $name"
                }
            }
            $normalized = $components -join '\'
            if (-not $normalizedEntries.Add($normalized)) {
                throw "Duplicate Platform Tools archive entry: $name"
            }
            $unixFileType = (($entry.ExternalAttributes -shr 16) -band 0xF000)
            if ($unixFileType -eq 0xA000) {
                throw "Symbolic links are forbidden in the Platform Tools archive: $name"
            }
            if ($entry.Length -gt $maximumEntryBytes) {
                throw "Platform Tools archive entry is too large: $name"
            }
            $expandedBytes += $entry.Length
            if ($expandedBytes -gt $maximumExpandedBytes) {
                throw "Platform Tools archive expands beyond $maximumExpandedBytes bytes"
            }
        }
    }
    finally {
        $zip.Dispose()
    }

    Expand-Archive -LiteralPath $archive -DestinationPath $expanded
    Assert-NoReparseAncestor -Path $expanded
    Get-ChildItem -LiteralPath $expanded -Recurse -Force | ForEach-Object {
        if (($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Expanded Platform Tools contains a reparse point: $($_.FullName)"
        }
    }

    $candidate = Join-Path $expanded 'platform-tools'
    $candidateAdb = Join-Path $candidate 'adb.exe'
    if (-not (Test-Path -LiteralPath $candidateAdb -PathType Leaf)) {
        throw 'Downloaded archive does not contain platform-tools\adb.exe'
    }
    $versionStdout = Join-Path $temporaryRoot 'adb-version.stdout'
    $versionStderr = Join-Path $temporaryRoot 'adb-version.stderr'
    $versionProcess = Start-Process -FilePath $candidateAdb -ArgumentList 'version' -NoNewWindow -PassThru `
        -RedirectStandardOutput $versionStdout -RedirectStandardError $versionStderr
    if (-not $versionProcess.WaitForExit($processTimeoutMilliseconds)) {
        $versionProcess.Kill()
        $versionProcess.WaitForExit()
        throw "Downloaded adb.exe version probe timed out after $processTimeoutMilliseconds ms"
    }
    $versionOutput = ((Get-Content -LiteralPath $versionStdout -Raw) + "`n" + (Get-Content -LiteralPath $versionStderr -Raw)).Trim()
    if ($versionProcess.ExitCode -ne 0) {
        throw "Downloaded adb.exe failed validation with exit code $($versionProcess.ExitCode)"
    }
    if ($versionOutput -notmatch ('(?m)^Version\s+' + [regex]::Escape($ExpectedVersion) + '([\-\s]|$)')) {
        throw "Downloaded adb.exe did not report locked Platform Tools version $ExpectedVersion"
    }

    $managedMetadata = [ordered]@{
        schemaVersion = 2
        version = $ExpectedVersion
        source = $downloadUri.AbsoluteUri
        size = $actualSize
        sha256 = $actualHash
        installedArchitecture = 'x64'
        emulatedOnArm64 = ($runtimeArchitecture -eq 'Arm64')
    } | ConvertTo-Json
    [System.IO.File]::WriteAllText((Join-Path $candidate $markerName), $managedMetadata, [System.Text.UTF8Encoding]::new($false))

    if (Test-Path -LiteralPath $target) {
        $backup = "$target.backup-$([guid]::NewGuid().ToString('N'))"
        Move-Item -LiteralPath $target -Destination $backup
    }
    Move-Item -LiteralPath $candidate -Destination $target
    $activated = $true

    Write-Output "ADB installed at: $target"
    if ($runtimeArchitecture -eq 'Arm64') { Write-Output 'ADB mode: x64 binary under Windows ARM64 emulation' }
    if ($backup) { Write-Output "Previous version backed up at: $backup" }
}
catch {
    $failure = $_
    if ($activated -and (Test-Path -LiteralPath $target)) {
        Move-Item -LiteralPath $target -Destination (Join-Path $temporaryRoot 'failed-activated-install')
        $activated = $false
    }
    if ($backup -and -not (Test-Path -LiteralPath $target) -and (Test-Path -LiteralPath $backup)) {
        Move-Item -LiteralPath $backup -Destination $target
    }
    throw $failure
}
finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
