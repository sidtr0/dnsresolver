# Set DNS to 127.0.0.1 for all active network adapters
Write-Host "Configuring system DNS to 127.0.0.1..."
$adapters = Get-NetAdapter | Where-Object { $_.Status -eq "Up" }
foreach ($adapter in $adapters) {
    Set-DnsClientServerAddress -InterfaceIndex $adapter.InterfaceIndex -ServerAddresses ("127.0.0.1")
    Write-Host "Set DNS for $($adapter.Name)"
}
Write-Host "DNS configuration complete."
