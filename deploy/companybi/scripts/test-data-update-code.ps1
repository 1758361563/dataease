# Code-only checks. Does not connect to a database or start services.
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$taskBuild = Join-Path $taskRoot 'core/core-backend/target/data-update-tests'
New-Item -ItemType Directory -Force -Path $taskBuild | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $taskBuild 'mockito-extensions') | Out-Null
[IO.File]::WriteAllText((Join-Path $taskBuild 'mockito-extensions/org.mockito.plugins.MockMaker'),'mock-maker-subclass',[Text.UTF8Encoding]::new($false))
$taskDeps = Get-ChildItem (Join-Path $taskRoot 'core/core-backend/target/dashboard-phase1/local-deps') -Filter '*.jar' | ForEach-Object FullName
$taskClasses = Get-ChildItem (Join-Path $taskRoot 'sdk') -Recurse -Directory -Filter 'classes' | ForEach-Object FullName
$taskClasspath = (@($taskBuild, (Join-Path $taskRoot 'core/core-backend/target/classes')) + $taskClasses + $taskDeps) -join ';'
$taskSources = @(Get-ChildItem (Join-Path $taskRoot 'core/core-backend/src/main/java/io/dataease/company/bi/dataupdate') -Filter '*.java' | ForEach-Object FullName)
$taskSources += Get-ChildItem (Join-Path $taskRoot 'core/core-backend/src/test/java/io/dataease/company/bi/dataupdate') -Filter '*.java' | ForEach-Object FullName
# Compile referenced legacy types too: cached baseline classes may predate the ETL merge.
$taskSources += Get-ChildItem (Join-Path $taskRoot 'core/core-backend/src/main/java/io/dataease/company/bi/etl') -Filter '*.java' | ForEach-Object FullName
$taskSources += Join-Path $taskRoot 'core/core-backend/src/main/java/io/dataease/company/bi/audit/CompanyAuditActionType.java'
$taskSources += Join-Path $taskRoot 'core/core-backend/src/main/java/io/dataease/company/bi/auth/CompanyBiSchemaInitializer.java'
$taskArgs = @('-encoding','UTF-8','-parameters','-proc:none','-cp',('"'+$taskClasspath.Replace('\','/')+'"'),'-d',('"'+$taskBuild.Replace('\','/')+'"'))
$taskArgs += $taskSources | ForEach-Object { '"'+$_.Replace('\','/')+'"' }
[IO.File]::WriteAllLines((Join-Path $taskBuild 'javac.args'),$taskArgs,[Text.UTF8Encoding]::new($false))
& javac ('@'+(Join-Path $taskBuild 'javac.args'))
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$taskAgent = Get-ChildItem (Join-Path $taskRoot 'core/core-backend/target/dashboard-phase1/local-deps') -Filter 'byte-buddy-agent-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$taskJava = @(('-javaagent:'+$taskAgent),'-cp',$taskClasspath,'io.dataease.company.bi.dataupdate.UpdateTestLauncher')
[IO.File]::WriteAllLines((Join-Path $taskBuild 'java.args'),($taskJava | ForEach-Object { '"'+$_.Replace('\','/')+'"' }),[Text.UTF8Encoding]::new($false))
& java ('@'+(Join-Path $taskBuild 'java.args'))
exit $LASTEXITCODE
