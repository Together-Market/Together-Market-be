param([switch]$SelfTest)
$ErrorActionPreference = 'Stop'
$cacheRoot = if ($env:GRADLE_USER_HOME) {
    Join-Path $env:GRADLE_USER_HOME 'caches/modules-2/files-2.1'
} else {
    Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
}
function Find-DependencyJar([string]$relativePath) {
    $dependencyPath = Join-Path $cacheRoot $relativePath
    $item = Get-ChildItem -LiteralPath $dependencyPath -Filter '*.jar' -Recurse |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)[.]jar$' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $item) { throw '필요한 라이브러리가 없습니다. 프로젝트의 Gradle 빌드를 먼저 실행하세요.' }
    return $item.FullName
}
$cryptoJar = Find-DependencyJar 'org.springframework.security/spring-security-crypto'
$loggingJar = Find-DependencyJar 'commons-logging/commons-logging'
$javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$sourcePath = Join-Path $PSScriptRoot 'DemoOwnerSql.java'
$javaOptions = @('--class-path', ($cryptoJar + ';' + $loggingJar), $sourcePath)
if ($SelfTest) { $javaOptions += '--self-test' }
& $javaExecutable @javaOptions
if ($LASTEXITCODE -ne 0) { throw '계정 SQL 준비가 완료되지 않았습니다. 위 안내를 확인하세요.' }