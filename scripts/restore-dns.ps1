# Restore DNS to DHCP (automatic) for all active network adapters
Write-Host "Restoring system DNS to DHCP..."
$adapters = Get-NetAdapter | Where-Object { $_.Status -eq "Up" }
foreach ($adapter in $adapters) {
    Set-DnsClientServerAddress -InterfaceIndex $adapter.InterfaceIndex -ResetServerAddresses
    Write-Host "Restored DNS for $($adapter.Name)"
}
Write-Host "DNS configuration restored to DHCP."
