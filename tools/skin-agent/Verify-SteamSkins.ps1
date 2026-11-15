param(
    [Parameter(Mandatory)][string] $LauncherSource,
    [Parameter(Mandatory)][string] $RunDirectory,
    [Parameter(Mandatory)][string] $BaselineAgent
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $LauncherSource).Path
$run = (Resolve-Path -LiteralPath $RunDirectory).Path
$runs = Join-Path $project 'artifacts/agent-runs'
if ((Split-Path -Parent $run) -ine [IO.Path]::GetFullPath($runs) -or !(Test-Path -LiteralPath "$run/RUN.json")) {
    throw 'Use an owned launcher workspace run.'
}
$work = Join-Path $run 'steam-skins-verify'
if (Test-Path -LiteralPath $work) { throw 'Use a fresh verification directory.' }
$shared = Join-Path $project 'Minecraft/Launcher/Shared'
$profile = Get-Content -LiteralPath "$shared/versions/1.16.5/1.16.5.json" -Raw | ConvertFrom-Json
$inputs = @($profile.libraries | Where-Object { $_.name -match '^(com\.mojang:authlib|com\.google\.(guava:guava|code\.gson:gson)|org\.apache\.logging\.log4j:log4j-(api|core)|org\.apache\.commons:commons-lang3|commons-codec:commons-codec|commons-io:commons-io):' })
if ($inputs.Count -ne 8) { throw 'The Minecraft libraries changed; review the Authlib verification inputs.' }
$dependencies = @($inputs | ForEach-Object { Join-Path "$shared/libraries" $_.downloads.artifact.path })
$authlib = $dependencies | Where-Object { $_ -like '*authlib-2.1.28.jar' }
$authlibHash = (Get-FileHash -LiteralPath $authlib).Hash.ToLowerInvariant()
if ($authlibHash -ne 'bae2283ec6afde9806142cd877b786123143ae50686fdd138ddb281b3de81d59') { throw 'Unexpected Authlib 2.1.28.' }
$baseline = (Resolve-Path -LiteralPath $BaselineAgent).Path
$baselineHash = (Get-FileHash -LiteralPath $baseline).Hash.ToLowerInvariant()
if ($baselineHash -ne 'c925785b5098123bfd31576203fd6a5d95b7186f8f7ec8d30b49b077e7c3f45a') { throw 'Unexpected published skin agent.' }
$candidate = "$run/legacy-skins.jar"
$candidateHash = (Get-FileHash -LiteralPath $candidate).Hash.ToLowerInvariant()
$java = Join-Path $project 'Minecraft/Launcher/JavaRuntimes/runtime/windows-x64/java-8/bin/java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$classPath = ($dependencies + $work) -join [IO.Path]::PathSeparator
[void][IO.Directory]::CreateDirectory($work)
try {
    & $javac --release 8 -encoding UTF-8 -cp $classPath -d $work "$PSScriptRoot/VerifySteamSkins.java"
    if ($LASTEXITCODE) { throw 'Skin verification source failed to compile.' }
    $results = [ordered]@{authlibSha256=$authlibHash; baselineSha256=$baselineHash; candidateSha256=$candidateHash; javaSha256=(Get-FileHash -LiteralPath $java).Hash.ToLowerInvariant()}
    foreach ($mode in 'baseline', 'candidate') {
        $agent = if ($mode -eq 'baseline') { $baseline } else { $candidate }
        $output = & $java -Xmx128m -Xverify:all '-Djava.awt.headless=true' "-Djava.io.tmpdir=$work" "-javaagent:$agent" -cp $classPath VerifySteamSkins $work $mode
        if ($LASTEXITCODE -or !($output -match '^PASS ')) { throw "Authlib verification failed in $mode mode." }
        $results[$mode] = ($output | Where-Object { $_ -match '^PASS ' })
    }
    $results | ConvertTo-Json | Set-Content -LiteralPath "$run/authlib-proof.json" -Encoding UTF8
    $results
} finally {
    $resolved = (Resolve-Path -LiteralPath $work).Path
    if ((Split-Path -Parent $resolved) -ine $run -or (Get-ChildItem -LiteralPath $resolved -Recurse -Force -Attributes ReparsePoint)) {
        throw 'Unexpected skin verification directory.'
    }
    [IO.Directory]::Delete($resolved, $true)
}
