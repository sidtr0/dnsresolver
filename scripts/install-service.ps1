# Install WinSW service
Requires -RunAsAdministrator

$scriptPath = Split-Path -Parent $MyInvocation.MyCommand.Definition
$rootPath = Split-Path -Parent $scriptPath
$serviceExe = Join-Path $rootPath "dns-resolver-service.exe"
$serviceXml = Join-Path $rootPath "dns-resolver-service.xml"

# Download WinSW if not present
if (-not (Test-Path $serviceExe)) {
    Write-Host "Downloading WinSW..."
    Invoke-WebRequest -Uri "https://github.com/winsw/winsw/releases/download/v3.0.0-alpha.11/WinSW-x64.exe" -OutFile $serviceExe
}

Write-Host "Installing DNS Resolver Service..."
& $serviceExe install
if ($LASTEXITCODE -eq 0) {
    Write-Host "Service installed successfully."
    Write-Host "Configuring system DNS to use the local resolver..."
    & (Join-Path $scriptPath "set-dns.ps1")
} else {
    Write-Host "Failed to install service." -ForegroundColor Red
}
