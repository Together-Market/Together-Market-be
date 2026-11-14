param([switch]$BuildOnly)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
Push-Location $projectDirectory
$previousProfiles = $env:SPRING_PROFILES_ACTIVE
$previousGeminiKey = $env:GEMINI_API_KEY
$keyPointer = [IntPtr]::Zero
try {
    if ($BuildOnly) {
        & ./gradlew.bat spotlessCheck build
        if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
        return
    }
    foreach ($requiredName in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($requiredName))) {
            throw "Set $requiredName for the demo database before starting."
        }
    }
    if (($previousProfiles -split ',') -match '^\s*(prod|production)\s*$') {
        throw 'Open a development terminal without a production profile to run the demo.'
    }
    if ([string]::IsNullOrWhiteSpace($env:GEMINI_API_KEY)) {
        $secret = Read-Host 'Gemini API key (hidden)' -AsSecureString
        $keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
        $env:GEMINI_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)
    }
    $env:SPRING_PROFILES_ACTIVE = 'demo'
    & ./gradlew.bat --no-daemon bootRun
    if ($LASTEXITCODE -ne 0) { throw 'Demo server exited with an error' }
} finally {
    $env:SPRING_PROFILES_ACTIVE = $previousProfiles
    $env:GEMINI_API_KEY = $previousGeminiKey
    if ($keyPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer)
    }
    Pop-Location
}
