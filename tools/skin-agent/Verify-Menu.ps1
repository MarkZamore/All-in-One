param(
    [Parameter(Mandatory)][string] $LauncherSource,
    [Parameter(Mandatory)][string] $RunDirectory,
    [Parameter(Mandatory)][string] $SteamJar,
    [Parameter(Mandatory)][string] $RuntimeFixtureTools
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $LauncherSource).Path
$run = (Resolve-Path -LiteralPath $RunDirectory).Path
$fixture = (Resolve-Path -LiteralPath $RuntimeFixtureTools).Path
$runs = Join-Path $project 'artifacts/agent-runs'
if ((Split-Path -Parent $run) -ine [IO.Path]::GetFullPath($runs) -or !(Test-Path -LiteralPath "$run/RUN.json") -or
    (Split-Path -Parent $fixture) -ine [IO.Path]::GetFullPath($runs) -or !(Test-Path -LiteralPath "$fixture/RUN.json")) {
    throw 'Use owned launcher workspace runs.'
}
$locked = Get-Content -LiteralPath "$PSScriptRoot/lock.json" -Raw | ConvertFrom-Json
$agent = Join-Path $run 'legacy-skins.jar'
if ((Get-FileHash -LiteralPath $agent).Hash -ine $locked.agent.sha256 -or
    (Get-FileHash -LiteralPath $SteamJar).Hash -ine $locked.nativeInputs.steamSha256) {
    throw 'The selected agent or Steam component differs from the input pins.'
}
$result = Join-Path $run 'menu-result.json'
$runKey = 'allinonemenuprobe'
$game = Join-Path $fixture "game/$runKey"
$evidence = Join-Path $fixture "evidence/$runKey"
$qa = Join-Path $fixture "$runKey.jar"
$classes = Join-Path $run 'menu-probe-build'
foreach ($path in $result, $game, $evidence, $qa, $classes) {
    if (Test-Path -LiteralPath $path) { throw "Use fresh QA output paths: $path" }
}
[void][IO.Directory]::CreateDirectory($classes)
try {
    $javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
    $jar = Join-Path $env:JAVA_HOME 'bin/jar.exe'
    $manifest = Join-Path $classes 'manifest.mf'
    $head = "Manifest-Version: 1.0{0}Premain-Class: lanminecraft.qa.VerifyMenuAgent{0}" -f [char]10
    [IO.File]::WriteAllText($manifest, $head, [Text.UTF8Encoding]::new($false))
    & $javac --release 8 -encoding UTF-8 -d $classes "$PSScriptRoot/VerifyMenuAgent.java"
    if ($LASTEXITCODE) { throw 'Menu probe failed to compile.' }
    & $jar cfm $qa $manifest -C $classes lanminecraft
    if ($LASTEXITCODE) { throw 'Menu probe failed to package.' }
    python -B "$fixture/stage_pack.py" $runKey All-in-One "$project/Minecraft/Packs/All in One" $SteamJar
    if ($LASTEXITCODE) { throw 'Published game files failed source verification.' }
    Copy-Item -LiteralPath $agent -Destination "$game/lanminecraft/legacy-skins.jar"
    $options = @{
        Shared="$project/Minecraft/Launcher/Shared"; Profile='1.16.5-forge-36.2.34'
        Java="$project/Minecraft/Launcher/JavaRuntimes/runtime/windows-x64/java-8/bin/java.exe"
        Game=$game; Evidence=$evidence; Version='1.16.5'; Agent=$qa; Heap=3584; Seconds=240
        TestProperties=@("-Dlanminecraft.allinone.menuEvidence=$result")
    }
    & "$fixture/Start-ForgeFixture.ps1" @options
    if (!(Test-Path -LiteralPath $result)) { throw 'Minecraft exited before the menu check.' }
    $observed = Get-Content -LiteralPath $result -Raw | ConvertFrom-Json
    if ($observed.kind -ne 'complete' -or !$observed.nativeSteamList) { throw 'Multiplayer menu verification did not pass.' }
    $observed
} finally {
    if (Test-Path -LiteralPath "$evidence/stderr.log") {
        Get-Content -LiteralPath "$evidence/stderr.log" -Tail 30 | Set-Content -LiteralPath "$run/menu-stderr-tail.log"
    }
    if (Test-Path -LiteralPath "$fixture/$runKey-stage.json") {
        Move-Item -LiteralPath "$fixture/$runKey-stage.json" -Destination "$run/menu-inputs.json" -Force
    }
    $evidencePrefix = [IO.Path]::GetFullPath($evidence) + '\'
    foreach ($link in Get-ChildItem -LiteralPath $evidence -Recurse -Force -Attributes ReparsePoint -ErrorAction SilentlyContinue) {
        if ($link.Name -ne 'Content.IE5' -or !$link.FullName.StartsWith($evidencePrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Unexpected junction: $($link.FullName)"
        }
        [IO.Directory]::Delete($link.FullName)
    }
    python -B "$fixture/clean_owned.py" "game/$runKey" "evidence/$runKey" "$runKey.jar"
    if ($LASTEXITCODE) { throw 'Owned QA staging could not be removed.' }
    if (Test-Path -LiteralPath $classes) {
        $resolved = (Resolve-Path -LiteralPath $classes).Path
        if ((Split-Path -Parent $resolved) -ine $run -or (Get-ChildItem -LiteralPath $classes -Recurse -Force -Attributes ReparsePoint)) {
            throw 'Unexpected menu probe build path.'
        }
        [IO.Directory]::Delete($resolved, $true)
    }
}
