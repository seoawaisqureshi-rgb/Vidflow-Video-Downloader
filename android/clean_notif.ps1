$f = "app\src\main\java\com\vidflow\app\notifications\NotificationHelper.kt"
$bytes = [System.IO.File]::ReadAllBytes($f)
$str = [System.Text.Encoding]::UTF8.GetString($bytes)
$str = $str.Replace("â€¢", "•")
$str = $str.Replace("âœ“", "✓")
[System.IO.File]::WriteAllText($f, $str, [System.Text.Encoding]::UTF8)
Remove-Item "clean_notif.ps1"
"Fixed UTF-8 in NotificationHelper"