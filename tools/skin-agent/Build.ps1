param(
    [Parameter(Mandatory)][string] $LauncherSource,
    [Parameter(Mandatory)][string] $RunDirectory
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $LauncherSource).Path
$run = (Resolve-Path -LiteralPath $RunDirectory).Path
$runs = Join-Path $project 'artifacts/agent-runs'
if ((Split-Path -Parent $run) -ine [IO.Path]::GetFullPath($runs) -or !(Test-Path "$run/RUN.json")) {
    throw 'Use an owned launcher workspace run.'
}
$work = Join-Path $run 'skin-agent-build'
if (Test-Path $work) { throw 'Use a fresh build directory.' }
$classes = [IO.Directory]::CreateDirectory("$work/classes").FullName
$hooks = [IO.Directory]::CreateDirectory("$work/hooks").FullName
$asmClasses = [IO.Directory]::CreateDirectory("$work/asm").FullName
$common = Join-Path $project 'Program/IdentityAdapters/Common'
$asm = Join-Path $common 'lib/asm-9.8.jar'
if ((Get-FileHash -LiteralPath $asm).Hash -ine '876eab6a83daecad5ca67eb9fcabb063c97b5aeb8cf1fca7a989ecde17522051') {
    throw 'ASM does not match the pinned launcher dependency.'
}
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$jar = Join-Path $env:JAVA_HOME 'bin/jar.exe'
$encoding = [Text.UTF8Encoding]::new($false)
$skinSource = (Get-Content "$common/PortableSkinProfiles.java" -Raw).
    Replace('profile.getClass().isRecord()', 'false').
    Replace('value.isBlank()', 'value.trim().isEmpty()').
    Replace('Path.of(configuredPath)', 'java.nio.file.Paths.get(configuredPath)').
    Replace("`r`n", "`n")
$begin = $skinSource.IndexOf('    public static void apply(Object profile) {', [StringComparison]::Ordinal)
$end = $skinSource.IndexOf('    /** Replaces only the texture property', [StringComparison]::Ordinal)
if ($begin -lt 0 -or $end -le $begin) { throw 'The shared skin hook changed; review Java 8 compatibility.' }
$apply = $skinSource.Substring($begin, $end - $begin)
$lookup = '            SkinEntry entry = loadEntries().get(id);'
$installed = '            invokeCompatible(properties, "put", "textures", property);'
if (!$apply.Contains($lookup) -or !$apply.Contains($installed)) { throw 'The shared skin lookup changed; review Java 8 compatibility.' }
$lookupWithTunnel = @'
            Map<UUID, SkinEntry> current = loadEntries();
            SkinEntry entry = current.get(id);
            if (entry == null) {
                // The game may give a Steam tunnel profile its tunnel UUID.
                // Resolve only an authenticated registry alias for this skin lookup.
                entry = current.get(PortableIdentityProfiles.remap(id, null));
            }
'@
$tryApply = $apply.Replace('public static void apply(Object profile)', 'public static boolean tryApply(Object profile)').
    Replace('return;', 'return false;').
    Replace($lookup, $lookupWithTunnel.Replace("`r`n", "`n")).
    Replace($installed, '            return Boolean.TRUE.equals(invokeCompatible(properties, "put", "textures", property));').
    Replace('            // updates. Never mutate the shared PropertyMap.EMPTY.', "            // updates. Never mutate the shared PropertyMap.EMPTY.`n            return false;")
$retainApply = "    public static void apply(Object profile) {`n        tryApply(profile);`n    }`n`n"
$skinSource = $skinSource.Substring(0, $begin) + $retainApply + $tryApply + $skinSource.Substring($end)
[IO.File]::WriteAllText("$work/PortableSkinProfiles.java", $skinSource, $encoding)
$identitySource = (Get-Content "$common/PortableIdentityProfiles.java" -Raw).
    Replace('Path.of(configuredPath)', 'java.nio.file.Paths.get(configuredPath)')
[IO.File]::WriteAllText("$work/PortableIdentityProfiles.java", $identitySource, $encoding)
& $javac --release 8 -encoding UTF-8 -d $hooks "$work/PortableSkinProfiles.java" "$work/PortableIdentityProfiles.java" "$common/PortableIdentityReflection.java" "$PSScriptRoot/LegacyMultiplayerHooks.java"
if ($LASTEXITCODE) { throw 'Skin hook compilation failed.' }
& $javac --release 8 -encoding UTF-8 -cp $asm -d $classes "$PSScriptRoot/LegacySkinAgent.java"
if ($LASTEXITCODE) { throw 'Skin agent compilation failed.' }
Expand-Archive -LiteralPath (Copy-Item -LiteralPath $asm -Destination "$work/asm.zip" -PassThru).FullName -DestinationPath "$work/extracted"
Move-Item -LiteralPath "$work/extracted/org" -Destination $asmClasses
& $java "$project/Program/IdentityAdapters/BuildTools/RelocateAsm.java" $classes $asmClasses minecraft/portable/legacy_skins/asm
if ($LASTEXITCODE) { throw 'Skin agent ASM relocation failed.' }
[void][IO.Directory]::CreateDirectory("$asmClasses/META-INF")
Copy-Item -LiteralPath "$common/lib/LICENSE.asm.txt" -Destination "$asmClasses/META-INF/"
& $jar --create --file "$classes/skin-hooks.jar" --date 2000-01-01T00:00:00Z -C $hooks minecraft
if ($LASTEXITCODE) { throw 'Skin hook packaging failed.' }
[IO.File]::WriteAllText("$work/manifest.mf", "Manifest-Version: 1.0`nPremain-Class: minecraft.portable.identity.LegacySkinAgent`n", $encoding)
& $jar --create --file "$run/legacy-skins.jar" --manifest "$work/manifest.mf" --date 2000-01-01T00:00:00Z -C $classes . -C $asmClasses .
if ($LASTEXITCODE) { throw 'Skin agent packaging failed.' }
Get-FileHash -LiteralPath "$run/legacy-skins.jar"
