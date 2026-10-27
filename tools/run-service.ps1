param(
    [Parameter(Mandatory)][ValidateSet('identity','catalog','trade','ai','gateway','notify','search','benefit')][string]$Service
)
$ErrorActionPreference = 'Stop'
$Service = $Service.ToLowerInvariant()
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskEnv = Join-Path $taskRoot '.env'
if (!(Test-Path -LiteralPath $taskEnv)) { throw 'Run tools/init-local-env.ps1 first.' }
foreach ($taskLine in Get-Content -LiteralPath $taskEnv) {
    if ($taskLine -match '^([A-Z_]+)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
$taskPorts = @{identity=8081;catalog=8082;trade=8083;ai=8084;gateway=8080;notify=8085;search=8086;benefit=8087}
$env:ROCKETMQ_ENDPOINTS = '127.0.0.1:18081'
$env:SERVER_PORT = [string]$taskPorts[$Service]
$env:NACOS_ADDR = '127.0.0.1:8848'
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '16379'
if ($Service -ne 'gateway') {
    $env:DB_USERNAME = "cinema_$Service"
    $env:DB_PASSWORD = [Environment]::GetEnvironmentVariable("$($Service.ToUpperInvariant())_DB_PASSWORD",'Process')
    $env:DB_URL = "jdbc:mysql://127.0.0.1:13306/cinema_$($Service)?serverTimezone=Asia/Shanghai&connectTimeout=2000&socketTimeout=10000"
}
$taskJar = Join-Path $taskRoot "cinema-$Service/target/cinema-$Service-0.1.0-exec.jar"
if (!(Test-Path -LiteralPath $taskJar)) { throw 'Build the reactor with mvn package first.' }
$taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
Push-Location $taskRoot
try { & $taskJava '-Duser.timezone=Asia/Shanghai' -jar $taskJar }
finally { Pop-Location }
