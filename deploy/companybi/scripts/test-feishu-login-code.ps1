$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$taskBuild = Join-Path $taskRoot 'core/core-backend/target/feishu-login-tests'
New-Item -ItemType Directory -Force -Path $taskBuild | Out-Null
$taskDeps = Get-ChildItem (Join-Path $taskRoot 'core/core-backend/target/dashboard-phase1/local-deps') -Filter '*.jar' | ForEach-Object FullName
$taskClasses = Get-ChildItem (Join-Path $taskRoot 'sdk') -Recurse -Directory -Filter 'classes' | ForEach-Object FullName
$taskClasspath = (@($taskBuild, (Join-Path $taskRoot 'core/core-backend/target/classes')) + $taskClasses + $taskDeps) -join ';'
$taskSources = @(
    'admin/user/CompanyUserAdminService.java', 'admin/user/CompanyUserCreateRequest.java',
    'admin/user/CompanyUserUpdateRequest.java', 'admin/user/CompanyUserVO.java',
    'auth/CompanyAuthFilterConfig.java', 'auth/CompanyBiSchemaInitializer.java',
    'auth/CompanyTokenIssuer.java', 'auth/CompanyUserStatusFilter.java',
    'feishu/CompanyFeishuService.java', 'feishu/CompanyFeishuIdentityService.java',
    'feishu/FeishuIdentityController.java', 'feishu/FeishuLoginController.java',
    'feishu/FeishuLoginService.java', 'feishu/FeishuLoginSettings.java', 'feishu/FeishuOAuthClient.java'
) | ForEach-Object { Join-Path $taskRoot ('core/core-backend/src/main/java/io/dataease/company/bi/' + $_) }
$taskSources += Join-Path $taskRoot 'sdk/common/src/main/java/io/dataease/utils/WhitelistUtils.java'
$taskSources += Join-Path $taskRoot 'core/core-backend/src/test/java/io/dataease/company/bi/feishu/FeishuLoginCodeTest.java'
$taskArgs = @('-encoding', 'UTF-8', '-parameters', '-cp', ('"'+$taskClasspath.Replace('\','/')+'"'),
    '-d', ('"'+$taskBuild.Replace('\','/')+'"'), '-sourcepath', ('"'+(Join-Path $taskRoot 'core/core-backend/src/main/java').Replace('\','/')+'"'))
$taskArgs += $taskSources | ForEach-Object { '"'+$_.Replace('\','/')+'"' }
[IO.File]::WriteAllLines((Join-Path $taskBuild 'javac.args'),$taskArgs,[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllLines((Join-Path $taskBuild 'java.args'),@('-cp',('"'+$taskClasspath.Replace('\','/')+'"'),'io.dataease.company.bi.feishu.FeishuLoginCodeTest'),[Text.UTF8Encoding]::new($false))
Push-Location $taskRoot
try {
    & javac ('@'+(Join-Path $taskBuild 'javac.args'))
    if ($LASTEXITCODE -ne 0) { throw 'Feishu code compilation failed' }
    & java ('@'+(Join-Path $taskBuild 'java.args'))
    if ($LASTEXITCODE -ne 0) { throw 'Feishu code tests failed' }
} finally { Pop-Location }
