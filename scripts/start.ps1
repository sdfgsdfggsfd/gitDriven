# Windows equivalent of start.sh (PowerShell 5.1+). Run it FROM INSIDE the server folder.
#
#   powershell -ExecutionPolicy Bypass -File scripts\start.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\start.ps1 -ApplyOnly
#
# Same variables as start.sh (SERVER_JAR, MEMORY, JAVA, JAVA_ARGS, RESTART_DELAY), or the
# matching parameters (-ServerJar, -Memory, -Java, -JavaArgs, -RestartDelay).
param(
    [string]$ServerJar = $(if ($env:SERVER_JAR) { $env:SERVER_JAR } else { "folia.jar" }),
    [string]$Memory = $(if ($env:MEMORY) { $env:MEMORY } else { "4G" }),
    [string]$Java = $(if ($env:JAVA) { $env:JAVA } else { "java" }),
    [string[]]$JavaArgs = $(if ($env:JAVA_ARGS) { -split $env:JAVA_ARGS } else { @() }),
    [int]$RestartDelay = $(if ($env:RESTART_DELAY) { [int]$env:RESTART_DELAY } else { 5 }),
    [switch]$ApplyOnly
)
$ErrorActionPreference = "Stop"
# Tells Core that this loop will start the server again after it shuts down. Without it,
# Core never restarts the server by itself (it would just stay off).
$env:GIT_DRIVEN_LAUNCHER = "start.ps1"
$root = (Get-Location).Path
$gd = Join-Path $root ".git-driven"

function Apply-Update {
    $staging = Join-Path $gd "staging"
    if (-not (Test-Path (Join-Path $staging "READY"))) { return }
    $commit = (Get-Content (Join-Path $staging "COMMIT") -TotalCount 1).Trim()
    Write-Host "[git-driven] Applying $commit"

    $plugins = Join-Path $root "plugins"
    New-Item -ItemType Directory -Force $plugins | Out-Null
    # Remove the jars Core installed last time (plugins deleted from the repo disappear).
    $managed = Join-Path $gd "managed-plugins.txt"
    if (Test-Path $managed) {
        foreach ($jar in Get-Content $managed) {
            $jar = $jar.Trim()
            if ($jar) {
                $path = Join-Path $plugins $jar
                if (Test-Path $path) { Remove-Item -Force $path }
            }
        }
    }
    $jars = @(Get-ChildItem (Join-Path $staging "plugins") -Filter *.jar)
    foreach ($jar in $jars) { Copy-Item -Force $jar.FullName $plugins }
    # ascii: no BOM, Core reads it as UTF-8
    Set-Content -Encoding ascii -Path $managed -Value ($jars | ForEach-Object { $_.Name })

    # Versioned configs (the repo's server/ folder) over the server root.
    $files = Join-Path $staging "files"
    if (Test-Path $files) {
        $base = (Resolve-Path $files).Path.TrimEnd('\') + '\'
        foreach ($f in Get-ChildItem $files -Recurse -File -Force) {
            $dst = Join-Path $root $f.FullName.Substring($base.Length)
            New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
            Copy-Item -Force $f.FullName $dst
        }
    }

    $changes = Join-Path $staging "CHANGES.txt"
    if (Test-Path $changes) { Copy-Item -Force $changes (Join-Path $gd "last-changes.txt") }
    Set-Content -Encoding ascii -Path (Join-Path $gd "deployed-commit") -Value $commit
    Remove-Item -Recurse -Force $staging
    Write-Host "[git-driven] $commit applied."
}

if ($ApplyOnly) { Apply-Update; exit 0 }

while ($true) {
    Apply-Update
    & $Java "-Xms$Memory" "-Xmx$Memory" @JavaArgs -jar $ServerJar --nogui
    Write-Host "[git-driven] Server stopped. Starting again in ${RestartDelay}s (Ctrl+C to quit)..."
    Start-Sleep -Seconds $RestartDelay
}
