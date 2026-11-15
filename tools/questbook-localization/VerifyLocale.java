import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ftb.mods.ftblibrary.snbt.SNBT;
import dev.ftb.mods.ftblibrary.util.ClientTextComponentUtils;
import dev.latvian.kubejs.client.KubeJSClientResourcePack;
import dev.latvian.kubejs.KubeJS;
import dev.latvian.kubejs.script.data.KubeJSResourcePack;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.resources.ClientLanguageMap;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.resources.Language;
import net.minecraft.resources.FallbackResourceManager;
import net.minecraft.resources.ResourcePackType;
import net.minecraft.util.text.LanguageMap;
import net.minecraftforge.fml.loading.FMLPaths;

/** Read the staged files with the actual Java 8 KubeJS, Minecraft and FTB classes. */
public final class VerifyLocale {
    private static final String NAMESPACE = "all_in_one_quests";
    private static final String QUESTS = "config/ftbquests/quests/";
    private static final Gson SNBT_STRINGS = new GsonBuilder().disableHtmlEscaping().create();

    public static void main(String[] args) throws Exception {
        Path game = Paths.get(args[0]).toAbsolutePath();
        Field absolutePath = FMLPaths.class.getDeclaredField("absolutePath");
        absolutePath.setAccessible(true);
        absolutePath.set(FMLPaths.GAMEDIR, game);
        // The standalone proof only uses KubeJS's filesystem resource loader.
        // Supply the loader's initialization sentinel without running mod scripts.
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        KubeJS.instance = (KubeJS) unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(singleton.get(null), KubeJS.class);
        FallbackResourceManager resources = new FallbackResourceManager(ResourcePackType.CLIENT_RESOURCES, NAMESPACE);
        try (KubeJSClientResourcePack pack = new KubeJSClientResourcePack()) {
            Field generated = KubeJSResourcePack.class.getDeclaredField("cachedResources");
            generated.setAccessible(true);
            generated.set(pack, Collections.emptyMap());
            require(pack.func_195759_a(ResourcePackType.CLIENT_RESOURCES).contains(NAMESPACE), "KubeJS namespace missing");
            resources.func_199021_a(pack);
            Language english = new Language("en_us", "US", "English", false);
            Language russian = new Language("ru_ru", "Россия", "Русский", false);
            JsonObject en = readLocale(game, "en_us");
            JsonObject ru = readLocale(game, "ru_ru");
            verify(resources, Arrays.asList(english), en);
            verify(resources, Arrays.asList(english, russian), ru);
            verify(resources, Arrays.asList(english), en);
            verifyQuestFiles(game, args[1], en);
            System.out.println(en.size() + " native FTB translations per locale (en_us -> ru_ru -> en_us)");
        }
    }

    private static JsonObject readLocale(Path game, String code) throws Exception {
        Path file = game.resolve("kubejs/assets/" + NAMESPACE + "/lang/" + code + ".json");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, JsonObject.class);
        }
    }

    private static void verify(FallbackResourceManager resources, List<Language> languages, JsonObject expected)
            throws Exception {
        ClientLanguageMap language = ClientLanguageMap.func_239497_a_(resources, languages);
        Method update = I18n.class.getDeclaredMethod("func_239502_a_", LanguageMap.class);
        update.setAccessible(true);
        update.invoke(null, language);
        LanguageMap.func_240594_a_(language);
        for (Map.Entry<String, JsonElement> row : expected.entrySet()) {
            String source = row.getValue().getAsString().replace("%%", "%");
            require(I18n.func_135052_a(row.getKey()).equals(source), "Wrong locale value for " + row.getKey());
            require(ClientTextComponentUtils.parse("{" + row.getKey() + "}")
                    .equals(ClientTextComponentUtils.parse(source)), "Wrong FTB component for " + row.getKey());
        }
    }

    private static void verifyQuestFiles(Path game, String authorPath, JsonObject english) throws Exception {
        try (ZipFile archive = new ZipFile(authorPath)) {
            for (ZipEntry entry : Collections.list(archive.entries())) {
                String name = entry.getName();
                if (!name.startsWith("overrides/" + QUESTS) || !name.endsWith(".snbt")) {
                    continue;
                }
                Path stagedPath = game.resolve(name.substring("overrides/".length()));
                List<String> staged = Files.readAllLines(stagedPath, StandardCharsets.UTF_8);
                require(SNBT.readLines(staged) != null, "Native FTB SNBT rejected " + name);
                List<String> original;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        archive.getInputStream(entry), StandardCharsets.UTF_8))) {
                    original = reader.lines().collect(Collectors.toList());
                }
                String restored = String.join("\n", staged);
                for (Map.Entry<String, JsonElement> row : english.entrySet()) {
                    String value = row.getValue().getAsString().replace("%%", "%");
                    String quoted = SNBT_STRINGS.toJson(value);
                    restored = restored.replace("\"{" + row.getKey() + "}\"", quoted);
                }
                require(SNBT.readLines(Arrays.asList(restored.split("\n", -1))).equals(SNBT.readLines(original)),
                        "Localized quest differs beyond text: " + name);
            }
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }
}
