$url = "https://www.facebook.com"
$headers = @{
    "User-Agent" = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15"
}
try {
    $res = Invoke-WebRequest -Uri $url -Headers $headers -MaximumRedirection 5 -TimeoutSec 10
    "Status: " + $res.StatusCode
} catch {
    "Error: " + $_.Exception.Message
}
Remove-Item "test_extract.ps1"