package lanminecraft.qa;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Exercises the published FancyMenu layout through the real Minecraft buttons. */
public final class VerifyMenuAgent {
    private static final String SCREENS = "net.minecraft.client.gui.screen.";
    private static final String CLIENT = "net.minecraft.client.Minecraft";
    private static final Class<?>[] NO_ARGUMENTS = new Class<?>[0];

    public static void premain(String option, Instrumentation instrumentation) throws Exception {
        Path evidence = Paths.get(System.getProperty("lanminecraft.allinone.menuEvidence")).toAbsolutePath().normalize();
        Path game = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        boolean ownedRun = false;
        for (Path parent = game; parent != null; parent = parent.getParent()) {
            if (Files.isRegularFile(parent.resolve("RUN.json")) &&
                Files.isRegularFile(evidence.getParent().resolve("RUN.json")) &&
                evidence.getParent().getParent().equals(parent.getParent())) {
                ownedRun = true;
                break;
            }
        }
        if (!ownedRun) throw new IllegalArgumentException("Use an owned run for game and evidence");
        Thread worker = new Thread(() -> verify(evidence, instrumentation), "AllInOneMenuQA");
        worker.setDaemon(true);
        worker.start();
    }

    private static void verify(Path evidence, Instrumentation instrumentation) {
        Object client = null;
        try {
            long start = System.nanoTime();
            while (client == null && System.nanoTime() - start < TimeUnit.MINUTES.toNanos(2)) {
                for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                    if (type.getName().equals(CLIENT)) client = call(type, null, "func_71410_x");
                }
                if (client == null) Thread.sleep(250);
            }
            require(client != null, "Minecraft singleton never initialized");
            Object minecraft = client;
            Executor clientThread = (Executor) client;
            start = System.nanoTime();
            while (System.nanoTime() - start < TimeUnit.MINUTES.toNanos(2)) {
                FutureTask<String> check = new FutureTask<>(() -> checkMenu(minecraft));
                clientThread.execute(check);
                String result = check.get(30, TimeUnit.SECONDS);
                if (result != null) {
                    Files.write(evidence, result.getBytes(StandardCharsets.UTF_8));
                    return;
                }
                Thread.sleep(250);
            }
            throw new IllegalStateException("Patched FancyMenu title never became ready");
        } catch (Throwable failure) {
            failure.printStackTrace();
            try {
                Files.write(evidence, ("FAIL " + failure + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception writingFailure) {
                writingFailure.printStackTrace();
            }
        } finally {
            Object minecraft = client;
            if (minecraft != null) ((Executor) minecraft).execute(() -> {
                try { call(minecraft.getClass(), minecraft, "func_71400_g"); }
                catch (Exception failure) { failure.printStackTrace(); }
            });
        }
    }

    private static String checkMenu(Object client) throws Exception {
        Object main = field(client, "field_71462_r");
        if (main == null || !main.getClass().getName().equals(SCREENS + "MainMenuScreen")) return null;
        Object multiplayer = null;
        Object realms = null;
        int matchingRows = 0;
        for (Object button : buttons(main)) {
            if (!(Boolean) field(button, "field_230694_p_")) continue;
            if ((Integer) call(button.getClass(), button, "func_230998_h_") == 158) {
                // The pack's FancyMenu replaces the singleplayer and multiplayer
                // labels with textures. Multiplayer is the lower of those rows.
                if (multiplayer == null || y(button) > y(multiplayer)) multiplayer = button;
                matchingRows++;
            }
            Object text = call(button.getClass(), button, "func_230458_i_");
            if ("Minecraft Realms".equals(call(text.getClass(), text, "getString"))) realms = button;
        }
        if (matchingRows == 0) return null;
        require(matchingRows == 2, "Unexpected FancyMenu native button layout");
        boolean vanillaAllows = (Boolean) call(client.getClass(), client, "func_238216_r_");
        require((Boolean) field(multiplayer, "field_230693_o_"), "Multiplayer button is disabled");
        if (realms != null)
            require(vanillaAllows == (Boolean) field(realms, "field_230693_o_"), "Realms availability changed");
        press(multiplayer);
        Object screen = field(client, "field_71462_r");
        if (screen.getClass().getName().equals(SCREENS + "MultiplayerWarningScreen")) {
            // The native Proceed button is the left one. Continue through the
            // first-visit screen without setting the Don't Show Again checkbox.
            Object proceed = null;
            for (Object button : buttons(screen)) {
                if (!button.getClass().getName().endsWith(".Button")) continue;
                if (proceed == null || (Integer) field(button, "field_230690_l_") <
                    (Integer) field(proceed, "field_230690_l_")) proceed = button;
            }
            require(proceed != null, "Missing confirmation button");
            press(proceed);
            screen = field(client, "field_71462_r");
        }
        require(screen.getClass().getName().equals(SCREENS + "MultiplayerScreen"), "Native multiplayer list did not open");
        field(screen, "e4steam$shownWorlds");
        return "{\"kind\":\"complete\",\"multiplayerActive\":true,\"nativeSteamList\":true,\"vanillaMultiplayerAllowed\":"
            + vanillaAllows + ",\"realmsPreserved\":" + (realms != null) + "}\n";
    }

    private static List<?> buttons(Object screen) throws Exception {
        return (List<?>) field(screen, "field_230710_m_");
    }

    private static int y(Object button) throws Exception {
        return (Integer) field(button, "field_230691_m_");
    }

    private static void press(Object button) throws Exception {
        call(button.getClass(), button, "func_230930_b_");
    }

    private static Object call(Class<?> type, Object receiver, String name) throws Exception {
        Method method = type.getMethod(name, NO_ARGUMENTS);
        return method.invoke(receiver);
    }

    private static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException missing) {
                // The screen, widget and game each inherit some fields.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void require(boolean actual, String message) {
        if (!actual) throw new IllegalStateException(message);
    }
}
