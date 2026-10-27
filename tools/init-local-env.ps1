$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskEnv = Join-Path $taskRoot '.env'
if (Test-Path -LiteralPath $taskEnv) { Write-Output 'Using existing .env; current secrets are preserved.'; return }
function New-CinemaSecret {
    $taskBytes = [byte[]]::new(48)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($taskBytes)
    [Convert]::ToBase64String($taskBytes)
}
$taskText = Get-Content -LiteralPath (Join-Path $taskRoot '.env.example') -Raw
foreach ($taskName in @('MYSQL_ROOT_PASSWORD','IDENTITY_DB_PASSWORD','CATALOG_DB_PASSWORD','TRADE_DB_PASSWORD','AI_DB_PASSWORD','JWT_SECRET','INTERNAL_SERVICE_KEY','REDIS_PASSWORD','NACOS_TOKEN_SECRET','NACOS_IDENTITY_VALUE','NACOS_PASSWORD','INITIAL_ADMIN_PASSWORD','CINEMA_METRICS_KEY','GRAFANA_ADMIN_PASSWORD','SKYWALKING_AUTH_TOKEN','SKYWALKING_UI_PASSWORD')) {
    $taskText = $taskText.Replace("$taskName=`n", "$taskName=$(New-CinemaSecret)`n")
    $taskText = $taskText.Replace("$taskName=`r`n", "$taskName=$(New-CinemaSecret)`r`n")
}
[IO.File]::WriteAllText($taskEnv, $taskText, [Text.UTF8Encoding]::new($false))
Write-Output 'Created .env with local secrets. These values are excluded from Git.'
