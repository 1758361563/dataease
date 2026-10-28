param(
    [string]$DependencyCheckout = '',
    [string]$MysqlUrl = '',
    [string]$MysqlUser = 'root'
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
if (-not $DependencyCheckout) { $DependencyCheckout = Join-Path (Split-Path $taskRoot -Parent) 'dataease' }
$taskBuild = Join-Path $taskRoot 'core/core-backend/target/etl-tests'
New-Item -ItemType Directory -Force -Path $taskBuild | Out-Null
$taskDeps = Get-ChildItem (Join-Path $DependencyCheckout 'core/core-backend/target/dashboard-phase1/local-deps') -Filter '*.jar' | ForEach-Object FullName
$taskClasses = Get-ChildItem (Join-Path $DependencyCheckout 'sdk') -Recurse -Directory -Filter 'classes' | ForEach-Object FullName
$taskClasspath = (@($taskBuild, (Join-Path $DependencyCheckout 'core/core-backend/target/classes')) + $taskClasses + $taskDeps) -join ';'
$taskSources = @(Get-ChildItem (Join-Path $taskRoot 'core/core-backend/src/main/java/io/dataease/company/bi/etl') -Filter '*.java' | ForEach-Object FullName)
$taskSources += Get-ChildItem (Join-Path $taskRoot 'core/core-backend/src/test/java/io/dataease/company/bi/etl') -Filter '*.java' | ForEach-Object FullName
$taskSources += Join-Path $taskRoot 'core/core-backend/src/main/java/io/dataease/company/bi/audit/CompanyAuditActionType.java'
$taskSources += Join-Path $taskRoot 'core/core-backend/src/main/java/io/dataease/company/bi/auth/CompanyBiSchemaInitializer.java'
$taskSources += Join-Path $taskRoot 'core/core-backend/src/main/java/io/dataease/company/bi/auth/CompanyAuthFilterConfig.java'
$taskArgs = @('-encoding','UTF-8','-parameters','-proc:none','-cp',('"'+$taskClasspath.Replace('\','/')+'"'),'-d',('"'+$taskBuild.Replace('\','/')+'"'))
$taskArgs += $taskSources | ForEach-Object { '"'+$_.Replace('\','/')+'"' }
[IO.File]::WriteAllLines((Join-Path $taskBuild 'javac.args'),$taskArgs,[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $taskBuild 'classpath.txt'),$taskClasspath,[Text.UTF8Encoding]::new($false))
& javac ('@'+(Join-Path $taskBuild 'javac.args'))
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$taskJava = @('-cp',$taskClasspath,('-Detl.fixtures='+(Join-Path $taskRoot 'core/core-backend/src/test/resources/company-etl')),('-Detl.schema='+(Join-Path $taskRoot 'core/core-backend/src/main/resources/db/company')))
if ($MysqlUrl) { $taskJava += @(('-Detl.mysql.url='+$MysqlUrl), ('-Detl.mysql.user='+$MysqlUser)) }
# Test password is read by tests from ETL_TEST_MYSQL_PASSWORD, never printed in command output.
$taskJava += 'io.dataease.company.bi.etl.EtlTestLauncher'
$taskJavaLines = $taskJava | ForEach-Object { '"'+$_.Replace('\','/')+'"' }
[IO.File]::WriteAllLines((Join-Path $taskBuild 'java.args'),$taskJavaLines,[Text.UTF8Encoding]::new($false))
& java ('@'+(Join-Path $taskBuild 'java.args'))
exit $LASTEXITCODE
