param(
    [Parameter(Mandatory)][string] $LauncherSource,
    [Parameter(Mandatory)][string] $RunDirectory,
    [Parameter(Mandatory)][string] $ForgeClient,
    [Parameter(Mandatory)][string] $Java8
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $LauncherSource).Path
$run = (Resolve-Path -LiteralPath $RunDirectory).Path
if ((Split-Path -Parent $run) -ine (Join-Path $project 'artifacts/agent-runs') -or
    !(Test-Path -LiteralPath "$run/RUN.json")) { throw 'Use a marked workspace run.' }
$asm = Join-Path $project 'Program/IdentityAdapters/Common/lib/asm-9.8.jar'
if ((Get-FileHash -LiteralPath $asm).Hash -ine '876eab6a83daecad5ca67eb9fcabb063c97b5aeb8cf1fca7a989ecde17522051' -or
    (Get-FileHash -LiteralPath $ForgeClient).Hash -ine 'd100be51f8fe32e7bced1f018154f6c83d33c2a68ee4a3505ff86916b881abb2') {
    throw 'Unexpected ASM or patched Forge client.'
}
$classes = Join-Path $run 'steam-chat-probe'
if (Test-Path -LiteralPath $classes) { throw 'Use a fresh probe directory.' }
[void][IO.Directory]::CreateDirectory($classes)
& (Join-Path $env:JAVA_HOME 'bin/javac.exe') --release 8 -encoding UTF-8 -cp $asm -d $classes `
    "$PSScriptRoot/LegacyMultiplayerHooks.java" "$PSScriptRoot/LegacySkinAgent.java" `
    "$PSScriptRoot/VerifySteamChat.java" "$PSScriptRoot/fixtures/SteamRuntime.java"
if ($LASTEXITCODE) { throw 'Native chat site probe did not compile.' }
& $Java8 -cp "$classes$([IO.Path]::PathSeparator)$asm" VerifySteamChat $ForgeClient
if ($LASTEXITCODE) { throw 'Native chat site probe failed.' }
