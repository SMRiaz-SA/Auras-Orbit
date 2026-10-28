param(
    [switch]$CheckArgumentMarshalling
)

$ErrorActionPreference = "Stop"

function New-PluginRunGradleArguments {
    param([string]$RelativePath)

    return @(":sandbox:run", "-PpluginPath=$RelativePath")
}

if ($CheckArgumentMarshalling) {
    $pathWithSpaces = "test_plugins/repository with spaces/plugin file.cs3"
    $arguments = New-PluginRunGradleArguments -RelativePath $pathWithSpaces
    if ($arguments.Count -ne 2 -or $arguments[1] -cne "-PpluginPath=$pathWithSpaces") {
        Write-Host "Argument check failed: a path containing spaces was split across Gradle arguments."
        exit 1
    }

    Write-Host "Argument check passed: the path with spaces is one Gradle property argument."
    exit 0
}

Write-Host "--- CloudStream Batch Plugin Tester ---"
Write-Host "Finding all .cs3 plugins..."

$plugins = Get-ChildItem -Path "test_plugins" -Recurse -Filter "*.cs3"
$total = $plugins.Count

if ($total -eq 0) {
    Write-Host "No .cs3 files found in test_plugins/"
    exit 0
}

Write-Host "Found $total plugins. Starting batch testing..."
Write-Host "----------------------------------------------"

$count = 1
$failedCount = 0
foreach ($plugin in $plugins) {
    Write-Host "[$count/$total] Testing $($plugin.Name)..."
    
    # We use a relative path from the plugin-sandbox root for gradle args
    $relativePath = $plugin.FullName.Substring((Get-Location).Path.Length + 1).Replace('\', '/')
    
    # Run the gradle task
    $gradleArguments = New-PluginRunGradleArguments -RelativePath $relativePath
    & .\..\gradlew.bat @gradleArguments 2>&1 | Out-Default
    $gradleExitCode = $LASTEXITCODE
    if ($gradleExitCode -ne 0) {
        Write-Host "   Failed with Gradle exit code $gradleExitCode."
        $failedCount++
    }
    
    $count++
}

Write-Host "----------------------------------------------"
Write-Host "Batch testing complete. Failed plugins: $failedCount / $total. Check the 'reports' folders inside each repository folder."
if ($failedCount -gt 0) {
    exit 1
}
exit 0
