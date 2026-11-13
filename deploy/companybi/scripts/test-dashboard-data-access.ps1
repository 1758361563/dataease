$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$taskBuild = Join-Path $taskRoot 'core/core-backend/target/dashboard-data-access-tests'
New-Item -ItemType Directory -Force -Path $taskBuild | Out-Null
$taskDeps = Get-ChildItem (Join-Path $taskRoot 'core/core-backend/target/dashboard-phase1/local-deps') -Filter '*.jar' | ForEach-Object FullName
$taskClasses = Get-ChildItem (Join-Path $taskRoot 'sdk') -Recurse -Directory -Filter 'classes' | ForEach-Object FullName
$taskClasspath = (@($taskBuild, (Join-Path $taskRoot 'core/core-backend/target/classes')) + $taskClasses + $taskDeps) -join ';'
$taskSources = @(
 'core/core-backend/src/main/java/io/dataease/company/bi/permission/CompanyDashboardDataGuard.java',
 'core/core-backend/src/main/java/io/dataease/chart/manage/ChartDataManage.java',
 'core/core-backend/src/main/java/io/dataease/dataset/manage/DatasetDataManage.java',
 'core/core-backend/src/main/java/io/dataease/visualization/calculation/DashboardCalculationManage.java',
 'sdk/api/api-base/src/main/java/io/dataease/api/dataset/dto/MultFieldValuesRequest.java',
 'sdk/api/api-base/src/main/java/io/dataease/api/dataset/dto/EnumValueRequest.java',
 'core/core-backend/src/main/java/io/dataease/dataset/manage/DatasetPreviewRequest.java',
 'core/core-backend/src/test/java/io/dataease/company/bi/permission/CompanyDashboardDataGuardTest.java'
) | ForEach-Object { Join-Path $taskRoot $_ }
$taskArgs = @('-encoding', 'UTF-8', '-parameters', '-cp', ('"'+$taskClasspath.Replace('\','/')+'"'),
    '-d', ('"'+$taskBuild.Replace('\','/')+'"'), '-sourcepath', ('"'+(Join-Path $taskRoot 'core/core-backend/src/main/java').Replace('\','/')+'"'))
$taskArgs += $taskSources | ForEach-Object { '"'+$_.Replace('\','/')+'"' }
[IO.File]::WriteAllLines((Join-Path $taskBuild 'javac.args'),$taskArgs,[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllLines((Join-Path $taskBuild 'java.args'),@('-cp',('"'+$taskClasspath.Replace('\','/')+'"'),'io.dataease.company.bi.permission.CompanyDashboardDataGuardTest'),[Text.UTF8Encoding]::new($false))
Push-Location $taskRoot
try {
    & javac ('@'+(Join-Path $taskBuild 'javac.args'))
    if ($LASTEXITCODE -ne 0) { throw 'Dashboard data access compilation failed' }
    & java ('@'+(Join-Path $taskBuild 'java.args'))
    if ($LASTEXITCODE -ne 0) { throw 'Dashboard data access tests failed' }
} finally { Pop-Location }
