$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$taskBuild = Join-Path $taskRoot 'core/core-backend/target/pareto-tests'
New-Item -ItemType Directory -Force -Path $taskBuild | Out-Null
$taskDeps = Get-ChildItem (Join-Path $taskRoot 'core/core-backend/target/dashboard-phase1/local-deps') -Filter '*.jar' | ForEach-Object FullName
$taskClasses = Get-ChildItem (Join-Path $taskRoot 'sdk') -Recurse -Directory -Filter 'classes' | ForEach-Object FullName
$taskClasspath = (@($taskBuild, (Join-Path $taskRoot 'core/core-backend/target/classes')) + $taskClasses + $taskDeps) -join ';'
$taskSources = @('core/core-backend/src/main/java/io/dataease/chart/charts/impl/others/ParetoHandler.java', 'core/core-backend/src/test/java/io/dataease/chart/charts/impl/others/ParetoHandlerTest.java') | ForEach-Object { Join-Path $taskRoot $_ }
$taskArgs = @('-encoding','UTF-8','-parameters','-cp',('"'+$taskClasspath.Replace('\','/')+'"'),'-d',('"'+$taskBuild.Replace('\','/')+'"'))
$taskArgs += $taskSources | ForEach-Object { '"'+$_.Replace('\','/')+'"' }
[IO.File]::WriteAllLines((Join-Path $taskBuild 'javac.args'),$taskArgs,[Text.UTF8Encoding]::new($false))
& javac ('@'+(Join-Path $taskBuild 'javac.args'))
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$taskJavaArgs = @('-cp', $taskClasspath, 'io.dataease.chart.charts.impl.others.ParetoHandlerTest')
[IO.File]::WriteAllLines((Join-Path $taskBuild 'java.args'), ($taskJavaArgs | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), [Text.UTF8Encoding]::new($false))
& java ('@' + (Join-Path $taskBuild 'java.args'))
exit $LASTEXITCODE
