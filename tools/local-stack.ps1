param(
    [ValidateSet('Status','Up','Stop','Build','Check')][string]$Action = 'Status',
    [ValidateSet('base','media','messaging','mq-search','transactions','jobs','jobs-search','governance','metrics','tracing','logs','observability','mq-observe')][string]$Group = 'base',
    [switch]$IncludeOptionalApps,
    [switch]$EnableDocs
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskGroups = @{
    base = @('mysql','redis','nacos','identity','catalog','trade','ai','gateway','web')
    media = @('mysql','redis','nacos','identity','catalog','gateway','web')
    messaging = @('mysql','redis','nacos','rocketmq-nameserver','rocketmq-broker','identity','catalog','notify','trade','ai','gateway','web')
    governance = @('mysql','redis','nacos','rocketmq-nameserver','rocketmq-broker','seata','xxl-job','sentinel-dashboard','identity','catalog','benefit','trade','notify','ai','gateway','web')
    jobs = @('mysql','redis','nacos','rocketmq-nameserver','rocketmq-broker','seata','xxl-job','identity','catalog','benefit','trade','notify','ai','gateway','web')
    metrics = @('mysql','redis','nacos','rocketmq-nameserver','rocketmq-broker','seata','identity','catalog','benefit','trade','notify','ai','gateway','web','prometheus','grafana')
    logs = @('mysql','redis','nacos','rocketmq-nameserver','rocketmq-broker','seata','identity','catalog','benefit','trade','notify','ai','gateway','web','prometheus','grafana','loki','alloy')
    observability = @('mysql','redis','nacos','elasticsearch','skywalking-oap','skywalking-ui','identity','catalog','trade','ai','gateway','web','prometheus','grafana','loki','alloy')
    'mq-observe' = @('mysql','redis','nacos','rocketmq-nameserver','rocketmq-broker','elasticsearch','skywalking-oap','skywalking-ui','identity','catalog','trade','notify','gateway','web','prometheus','grafana','loki','alloy')
    tracing = @('mysql','redis','nacos','elasticsearch','skywalking-oap','skywalking-ui','identity','catalog','trade','ai','gateway','web')
    transactions = @('mysql','redis','nacos','rocketmq-nameserver','rocketmq-broker','seata','identity','catalog','benefit','trade','notify','ai','gateway','web')
    'mq-search' = @('mysql','redis','nacos','rocketmq-nameserver','rocketmq-broker','elasticsearch','identity','catalog','trade','search','gateway','web')
    'jobs-search' = @('mysql','redis','nacos','rocketmq-nameserver','rocketmq-broker','elasticsearch','xxl-job','identity','catalog','trade','ai','search','gateway','web')
}
$taskServices = $taskGroups[$Group]
if ($IncludeOptionalApps) {
    if ($Group -ne 'mq-search') { throw 'IncludeOptionalApps applies to the mq-search group.' }
    $taskServices += @('ai','notify')
}
$taskCompose = @('compose','-f','compose.yml')
if ($Group -in @('messaging','mq-search','transactions','jobs','jobs-search','governance','metrics','tracing','logs','observability','mq-observe')) { $taskCompose += @('-f','deploy/local/compose.messaging.yml','--profile','messaging') }
if ($Group -in @('transactions','jobs','governance','metrics','logs','observability','mq-observe')) { $taskCompose += @('-f','deploy/local/compose.transactions.yml','--profile','transactions') }
if ($Group -in @('jobs','jobs-search','governance')) { $taskCompose += @('-f','deploy/local/compose.jobs.yml','--profile','jobs') }
if ($Group -eq 'governance') { $taskCompose += @('-f','deploy/local/compose.governance.yml','--profile','governance') }
if ($Group -in @('mq-search','jobs-search','tracing','observability','mq-observe')) { $taskCompose += @('-f','deploy/local/compose.search.yml','--profile','search') }
if ($Group -eq 'jobs-search') { $taskCompose += @('-f','deploy/local/compose.search-jobs.yml') }
if ($Group -in @('metrics','logs','observability','mq-observe')) { $taskCompose += @('-f','deploy/local/compose.metrics.yml','--profile','metrics') }
if ($Group -in @('tracing','observability','mq-observe')) { $taskCompose += @('-f','deploy/local/compose.tracing.yml','--profile','tracing') }
if ($Group -in @('logs','observability','mq-observe')) { $taskCompose += @('-f','deploy/local/compose.logs.yml','--profile','logs') }
if ($Group -eq 'observability') { $taskCompose += @('-f','deploy/local/compose.observe.yml') }
if ($Group -eq 'mq-observe') { $taskCompose += @('-f','deploy/local/compose.mq-observe.yml') }
$taskPreviousDocs = [Environment]::GetEnvironmentVariable('API_DOCS_ENABLED')
$taskPreviousDocsServices = [Environment]::GetEnvironmentVariable('API_DOCS_SERVICES')
if ($EnableDocs) {
    $env:API_DOCS_ENABLED = 'true'
    $env:API_DOCS_SERVICES = ($taskServices | Where-Object { $_ -in @('identity','catalog','trade','ai','notify','search','benefit') }) -join ','
}
Push-Location $taskRoot
try {
    if ($Action -eq 'Up' -and $Group -notin @('logs','observability','mq-observe')) {
        foreach ($taskCollector in @('alloy','loki')) {
            $taskCollectorIds = @(& docker ps -q --filter 'label=com.docker.compose.project=cinema-cloud' --filter "label=com.docker.compose.service=$taskCollector")
            if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect optional logging.' }
            if ($taskCollectorIds.Count -gt 0) { & docker stop @taskCollectorIds; if ($LASTEXITCODE -ne 0) { throw 'Unable to pause optional logging.' } }
        }
    }
    if ($Action -eq 'Up' -and $Group -notin @('tracing','observability','mq-observe')) {
        foreach ($taskTracer in @('skywalking-ui','skywalking-oap')) {
            $taskTracerIds = @(& docker ps -q --filter 'label=com.docker.compose.project=cinema-cloud' --filter "label=com.docker.compose.service=$taskTracer")
            if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect optional tracing.' }
            if ($taskTracerIds.Count -gt 0) { & docker stop @taskTracerIds; if ($LASTEXITCODE -ne 0) { throw 'Unable to pause optional tracing.' } }
        }
    }
    if ($Action -eq 'Up' -and $Group -in @('tracing','observability','mq-observe')) {
        & python tools/init-tracing.py
        if ($LASTEXITCODE -ne 0) { throw 'Tracing initialization failed.' }
        $taskTracingPause = if ($Group -eq 'mq-observe') { @('search','ai') } else { @('search','notify','rocketmq-broker','rocketmq-nameserver') }
        foreach ($taskPaused in $taskTracingPause) {
            $taskPausedIds = @(& docker ps -q --filter 'label=com.docker.compose.project=cinema-cloud' --filter "label=com.docker.compose.service=$taskPaused")
            if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect tracing startup budget.' }
            if ($taskPausedIds.Count -gt 0) { & docker stop @taskPausedIds; if ($LASTEXITCODE -ne 0) { throw 'Unable to pause optional messaging/search.' } }
        }
    }
    if ($Action -eq 'Up' -and $Group -notin @('metrics','logs','observability','mq-observe')) {
        foreach ($taskObserver in @('prometheus','grafana')) {
            $taskObserverIds = @(& docker ps -q --filter 'label=com.docker.compose.project=cinema-cloud' --filter "label=com.docker.compose.service=$taskObserver")
            if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect optional metrics.' }
            if ($taskObserverIds.Count -gt 0) { & docker stop @taskObserverIds; if ($LASTEXITCODE -ne 0) { throw 'Unable to pause optional metrics.' } }
        }
    }
    if ($Group -in @('metrics','logs','observability','mq-observe') -and $Action -eq 'Up') {
        & python tools/init-metrics.py
        if ($LASTEXITCODE -ne 0) { throw 'Metrics initialization failed.' }
    }
    if ($Action -eq 'Up' -and $Group -ne 'governance') {
        $taskDashboardIds = @(& docker ps -q --filter 'label=com.docker.compose.project=cinema-cloud' --filter 'label=com.docker.compose.service=sentinel-dashboard')
        if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect optional dashboard.' }
        if ($taskDashboardIds.Count -gt 0) { & docker stop @taskDashboardIds; if ($LASTEXITCODE -ne 0) { throw 'Unable to pause dashboard.' } }
    }
    if ($Action -eq 'Up' -and $Group -notin @('jobs','jobs-search','governance')) {
        $taskSchedulerIds = @(& docker ps -q --filter 'label=com.docker.compose.project=cinema-cloud' --filter 'label=com.docker.compose.service=xxl-job')
        if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect optional scheduler.' }
        if ($taskSchedulerIds.Count -gt 0) {
            & docker stop @taskSchedulerIds
            if ($LASTEXITCODE -ne 0) { throw 'Unable to pause optional scheduler.' }
        }
    }
    if ($Action -eq 'Up' -and $Group -notin @('transactions','jobs','governance','metrics','logs')) {
        # Transaction capabilities belong to their startup group. Retain their data
        # volumes and TC recovery records when switching to the lighter groups.
        foreach ($taskTransactionRole in @('benefit','seata')) {
            $taskTransactionIds = @(& docker ps -q --filter 'label=com.docker.compose.project=cinema-cloud' --filter "label=com.docker.compose.service=$taskTransactionRole")
            if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect optional transaction roles.' }
            if ($taskTransactionIds.Count -gt 0) {
                & docker stop @taskTransactionIds
                if ($LASTEXITCODE -ne 0) { throw "Unable to pause $taskTransactionRole." }
            }
        }
    }
    if ($Action -eq 'Up' -and $Group -in @('base','media','messaging','transactions','jobs','governance','metrics','logs')) {
        # A base/messaging Catalog disables its relay. Pause Search too, so it cannot serve stale data.
        # Match both labels; never touch another project's containers or its data volumes.
        foreach ($taskOptional in @('search','elasticsearch')) {
            $taskIds = @(& docker ps -q --filter 'label=com.docker.compose.project=cinema-cloud' --filter "label=com.docker.compose.service=$taskOptional")
            if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect optional search containers.' }
            if ($taskIds.Count -gt 0) {
                & docker stop @taskIds
                if ($LASTEXITCODE -ne 0) { throw "Unable to pause $taskOptional." }
            }
        }
    }
    if ($Group -in @('messaging','mq-search','transactions','jobs','jobs-search','governance','metrics','logs','mq-observe') -and $Action -eq 'Up') {
        & docker compose -f compose.yml up -d --wait --wait-timeout 240 mysql redis nacos
        if ($LASTEXITCODE -ne 0) { throw 'Base middleware did not become ready.' }
        & python tools/init-domain.py notify
        if ($LASTEXITCODE -ne 0) { throw 'Notify schema initialization failed.' }
        if ($Group -in @('transactions','jobs','governance','metrics','logs')) {
            & python tools/init-domain.py seata
            if ($LASTEXITCODE -ne 0) { throw 'Seata initialization failed.' }
            & python tools/init-domain.py benefit
            if ($LASTEXITCODE -ne 0) { throw 'Benefit initialization failed.' }
            & python tools/init-domain.py trade
            if ($LASTEXITCODE -ne 0) { throw 'Trade undo bootstrap failed.' }
        }
        if ($Group -in @('jobs','jobs-search','governance')) {
            & python tools/init-jobs.py
            if ($LASTEXITCODE -ne 0) { throw 'Scheduler initialization failed.' }
        }
        if ($Group -eq 'governance') {
            & python tools/init-governance.py
            if ($LASTEXITCODE -ne 0) { throw 'Persistent governance initialization failed.' }
        }
        if ($Group -in @('mq-search','jobs-search')) {
            & python tools/init-domain.py search
            if ($LASTEXITCODE -ne 0) { throw 'Search schema initialization failed.' }
            if ($Group -eq 'mq-search' -and -not $IncludeOptionalApps) {
                & docker @taskCompose stop ai notify
                if ($LASTEXITCODE -ne 0) { throw 'Unable to pause optional applications for the search group.' }
            }
            if ($Group -eq 'jobs-search') {
                & docker @taskCompose stop notify
                if ($LASTEXITCODE -ne 0) { throw 'Unable to pause optional notifications for the search jobs group.' }
            }
        }
    }
    # Project name and volumes stay those of compose.yml. Never manage unrelated projects.
    & docker @taskCompose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose validation failed; check local .env configuration.' }
    switch ($Action) {
        'Status' {
            $taskJson = & docker @taskCompose ps --all --format json
            if ($LASTEXITCODE -ne 0) { throw 'Unable to read local service state.' }
            $taskJson | ForEach-Object { $_ | ConvertFrom-Json } |
                Select-Object Service, State, Health, Status | Format-Table -AutoSize
        }
        'Up' {
            # No removal of containers outside the selected group, and no volume reset.
            & docker @taskCompose up -d --wait --wait-timeout 240 mysql redis nacos
            if ($LASTEXITCODE -ne 0) { throw 'Base middleware did not become ready.' }
            # Serialize JVM cold starts on the local learning machine to limit CPU/memory peaks.
            foreach ($taskService in ($taskServices | Where-Object { $_ -notin @('mysql','redis','nacos') })) {
                & docker @taskCompose up -d --wait --wait-timeout 240 --scale "$taskService=1" $taskService
                if ($LASTEXITCODE -ne 0) { throw "Service did not become ready: $taskService" }
            }
        }
        'Stop' {
            $taskStop = if ($Group -eq 'mq-observe') { @('alloy','loki','grafana','prometheus','skywalking-ui','skywalking-oap','elasticsearch') } elseif ($Group -eq 'observability') { @('alloy','loki','grafana','prometheus','skywalking-ui','skywalking-oap','elasticsearch') } elseif ($Group -eq 'logs') { @('alloy','loki') } elseif ($Group -eq 'tracing') { @('skywalking-ui','skywalking-oap','elasticsearch') } elseif ($Group -eq 'metrics') { @('grafana','prometheus') } elseif ($Group -eq 'jobs-search') { @('search','elasticsearch','xxl-job') } elseif ($Group -eq 'governance') { @('sentinel-dashboard') } elseif ($Group -eq 'jobs') { @('xxl-job') } elseif ($Group -eq 'transactions') { @('benefit','seata') } elseif ($Group -eq 'mq-search') { @('search','elasticsearch') } elseif ($Group -eq 'messaging') { @('notify','rocketmq-broker','rocketmq-nameserver') } else { $taskServices }
            & docker @taskCompose stop @taskStop
            if ($LASTEXITCODE -ne 0) { throw 'Unable to stop selected project services.' }
            if ($Group -eq 'mq-observe') {
                & $PSCommandPath -Action Up -Group messaging
                if ($LASTEXITCODE -ne 0) { throw 'Unable to restore the plain messaging group.' }
            }
            if ($Group -in @('tracing','observability')) {
                foreach ($taskPlainApp in @('identity','catalog','trade','ai','gateway')) {
                    & docker compose -f compose.yml up -d --no-deps --wait --wait-timeout 240 $taskPlainApp
                    if ($LASTEXITCODE -ne 0) { throw 'Unable to restore applications without tracing agent.' }
                }
            }
            if ($Group -in @('jobs','jobs-search')) {
                # Removing the overlay restores LOCAL mode, so stopping admin does not disable expiry.
                $taskFallback = @('compose','-f','compose.yml','-f','deploy/local/compose.messaging.yml','--profile','messaging')
                if ($Group -eq 'jobs') { $taskFallback += @('-f','deploy/local/compose.transactions.yml','--profile','transactions') }
                & docker @taskFallback up -d --no-deps --wait --wait-timeout 240 trade ai catalog
                if ($LASTEXITCODE -ne 0) { throw 'Unable to restore local expiry compensation.' }
            }
        }
        'Build' {
            $taskApps = $taskServices | Where-Object { $_ -in @('identity','catalog','trade','ai','gateway','notify','search','benefit','web') }
            if ($Group -in @('transactions','jobs','governance','metrics','logs')) { $taskApps += 'seata' }
            if ($Group -in @('jobs','jobs-search','governance')) { $taskApps += 'xxl-job' }
            if ($Group -eq 'governance') { $taskApps += 'sentinel-dashboard' }
            if ($Group -in @('logs','observability','mq-observe')) { $taskApps += 'loki' }
            & docker @taskCompose build @taskApps
            if ($LASTEXITCODE -ne 0) { throw 'Local image build failed.' }
        }
        'Check' {
            & python tools/check_architecture.py
            if ($LASTEXITCODE -ne 0) { throw 'Architecture check failed.' }
        }
    }
}
finally {
    if ($EnableDocs) {
        [Environment]::SetEnvironmentVariable('API_DOCS_ENABLED', $taskPreviousDocs)
        [Environment]::SetEnvironmentVariable('API_DOCS_SERVICES', $taskPreviousDocsServices)
    }
    Pop-Location
}
