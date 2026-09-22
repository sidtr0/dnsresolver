# Uninstall WinSW service
Requires -RunAsAdministrator

$scriptPath = Split-Path -Parent $MyInvocation.MyCommand.Definition
$rootPath = Split-Path -Parent $scriptPath
$serviceExe = Join-Path $rootPath "dns-resolver-service.exe"

Write-Host "Restoring system DNS settings..."
& (Join-Path $scriptPath "restore-dns.ps1")

Write-Host "Stopping DNS Resolver Service..."
& $serviceExe stop

Write-Host "Uninstalling DNS Resolver Service..."
& $serviceExe uninstall

if ($LASTEXITCODE -eq 0) {
    Write-Host "Service uninstalled successfully."
} else {
    Write-Host "Failed to uninstall service (or it was not installed)." -ForegroundColor Yellow
}
