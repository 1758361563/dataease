# Code-only checks. Does not connect to a database or start services.
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$taskBuild = Join-Path $taskRoot 'core/core-backend/target/preview-performance-tests'
New-Item -ItemType Directory -Force -Path $taskBuild | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $taskBuild 'mockito-extensions') | Out-Null
[IO.File]::WriteAllText((Join-Path $taskBuild 'mockito-extensions/org.mockito.plugins.MockMaker'),'mock-maker-subclass',[Text.UTF8Encoding]::new($false))
$taskDeps = Get-ChildItem (Join-Path $taskRoot 'core/core-backend/target/dashboard-phase1/local-deps') -Filter '*.jar' | ForEach-Object FullName
$taskClasses = Get-ChildItem (Join-Path $taskRoot 'sdk') -Recurse -Directory -Filter 'classes' | ForEach-Object FullName
$taskClasspath = (@($taskBuild, (Join-Path $taskRoot 'core/core-backend/target/classes')) + $taskClasses + $taskDeps) -join ';'
$taskSources = @('dataset/manage/PreviewExecution.java','dataset/manage/DatasetPreviewRequest.java','dataset/manage/DatasetPreviewManage.java','dataset/manage/DatasetDataManage.java','dataset/server/DatasetPreviewServer.java','datasource/provider/CalciteProvider.java') | ForEach-Object { Join-Path $taskRoot ('core/core-backend/src/main/java/io/dataease/'+$_) }
$taskSources += @('DatasetPreviewRequestTest.java','PreviewExecutionTest.java','PreviewTestLauncher.java') | ForEach-Object { Join-Path $taskRoot ('core/core-backend/src/test/java/io/dataease/dataset/manage/'+$_) }
$taskArgs = @('-encoding','UTF-8','-parameters','-cp',('"'+$taskClasspath.Replace('\','/')+'"'),'-d',('"'+$taskBuild.Replace('\','/')+'"'))
$taskArgs += $taskSources | ForEach-Object { '"'+$_.Replace('\','/')+'"' }
[IO.File]::WriteAllLines((Join-Path $taskBuild 'javac.args'),$taskArgs,[Text.UTF8Encoding]::new($false))
& javac ('@'+(Join-Path $taskBuild 'javac.args'))
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$taskAgent = Get-ChildItem (Join-Path $taskRoot 'core/core-backend/target/dashboard-phase1/local-deps') -Filter 'byte-buddy-agent-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$taskJava = @(('-javaagent:'+$taskAgent),'-cp',$taskClasspath,'io.dataease.dataset.manage.PreviewTestLauncher')
[IO.File]::WriteAllLines((Join-Path $taskBuild 'java.args'),($taskJava | ForEach-Object { '"'+$_.Replace('\','/')+'"' }),[Text.UTF8Encoding]::new($false))
& java ('@'+(Join-Path $taskBuild 'java.args'))
exit $LASTEXITCODE
