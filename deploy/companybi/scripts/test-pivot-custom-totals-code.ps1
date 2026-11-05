# Compile and run the focused regression using local build dependencies; no service or SQL.
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$taskBuild = Join-Path $taskRoot 'core/core-backend/target/pivot-custom-totals-tests'
New-Item -ItemType Directory -Force -Path $taskBuild | Out-Null
$taskDeps = Get-ChildItem (Join-Path $taskRoot 'core/core-backend/target/dashboard-phase1/local-deps') -Filter '*.jar' | ForEach-Object FullName
$taskClasses = Get-ChildItem (Join-Path $taskRoot 'sdk') -Recurse -Directory -Filter 'classes' | ForEach-Object FullName
$taskClasspath = (@($taskBuild, (Join-Path $taskRoot 'core/core-backend/target/classes')) + $taskClasses + $taskDeps) -join ';'
$taskSources = @(
    'core/core-backend/src/main/java/io/dataease/engine/trans/Dimension2SQLObj.java',
    'core/core-backend/src/test/java/io/dataease/chart/utils/PivotWeekSqlTest.java',
    'core/core-backend/src/main/java/io/dataease/chart/charts/impl/table/TablePivotHandler.java',
    'core/core-backend/src/test/java/io/dataease/chart/utils/PivotCustomTotalsTest.java'
) | ForEach-Object { Join-Path $taskRoot $_ }
$taskArgs = @('-encoding', 'UTF-8', '-parameters', '-cp', ('"' + $taskClasspath.Replace('\', '/') + '"'), '-d', ('"' + $taskBuild.Replace('\', '/') + '"'))
$taskArgs += $taskSources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }
[IO.File]::WriteAllLines((Join-Path $taskBuild 'javac.args'), $taskArgs, [Text.UTF8Encoding]::new($false))
& javac ('@' + (Join-Path $taskBuild 'javac.args'))
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$taskJavaArgs = @('-Dfile.encoding=UTF-8', '-cp', $taskClasspath, 'io.dataease.chart.utils.PivotCustomTotalsTest')
[IO.File]::WriteAllLines((Join-Path $taskBuild 'java.args'), ($taskJavaArgs | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), [Text.UTF8Encoding]::new($false))
& java ('@' + (Join-Path $taskBuild 'java.args'))
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$taskJavaArgs[-1] = 'io.dataease.chart.utils.PivotWeekSqlTest'
[IO.File]::WriteAllLines((Join-Path $taskBuild 'java-week.args'), ($taskJavaArgs | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), [Text.UTF8Encoding]::new($false))
& java ('@' + (Join-Path $taskBuild 'java-week.args'))
exit $LASTEXITCODE
