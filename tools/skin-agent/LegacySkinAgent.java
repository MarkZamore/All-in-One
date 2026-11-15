package minecraft.portable.identity;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Java 8 skin and multiplayer compatibility for the pack's pinned Forge client. */
public final class LegacySkinAgent implements ClassFileTransformer {
    private static final String SESSION = "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService";
    private static final String SKINS = "minecraft/portable/identity/PortableSkinProfiles";
    private static final String MULTIPLAYER = "minecraft/portable/identity/LegacyMultiplayerHooks";
    private static final String CLIENT = "net/minecraft/client/Minecraft";
    private static JarFile bootstrap;

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        Path hooks = Files.createTempFile("lanminecraft-skins", ".jar");
        hooks.toFile().deleteOnExit();
        try (InputStream bundled = LegacySkinAgent.class.getResourceAsStream("/skin-hooks.jar")) {
            if (bundled == null) throw new IllegalStateException("Missing launcher skin hooks");
            Files.copy(bundled, hooks, StandardCopyOption.REPLACE_EXISTING);
        }
        bootstrap = new JarFile(hooks.toFile());
        instrumentation.appendToBootstrapClassLoaderSearch(bootstrap);
        instrumentation.addTransformer(new LegacySkinAgent());
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
        if (Boolean.getBoolean("link.e4steam.launcherManagedLan")) {
            if ("me/hypherionmc/simplerpc/Utils".equals(name)) return useConnectedPlayerCount(bytes);
            if ("net/minecraft/client/gui/screen/MainMenuScreen".equals(name))
                return enableSteamMultiplayerButton(bytes);
            if (CLIENT.equals(name)) return enableSteamChat(bytes);
        }
        if (!SESSION.equals(name)) return null;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, method, descriptor, signature, exceptions);
                if ("getTextures".equals(method) &&
                        "(Lcom/mojang/authlib/GameProfile;Z)Ljava/util/Map;".equals(descriptor)) {
                    return new MethodVisitor(Opcodes.ASM9, target) {
                        @Override public void visitCode() {
                            super.visitCode();
                            Label original = new Label();
                            visitVarInsn(Opcodes.ALOAD, 1);
                            visitMethodInsn(Opcodes.INVOKESTATIC, SKINS, "tryApply", "(Ljava/lang/Object;)Z", false);
                            visitJumpInsn(Opcodes.IFEQ, original);
                            visitInsn(Opcodes.ICONST_0);
                            visitVarInsn(Opcodes.ISTORE, 2);
                            visitLabel(original);
                            visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        }
                    };
                }
                if ("isWhitelistedDomain".equals(method) && "(Ljava/lang/String;)Z".equals(descriptor)) {
                    return new MethodVisitor(Opcodes.ASM9, target) {
                        @Override public void visitCode() {
                            super.visitCode();
                            Label original = new Label();
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitMethodInsn(Opcodes.INVOKESTATIC, SKINS, "isRegisteredUrl", "(Ljava/lang/String;)Z", false);
                            visitJumpInsn(Opcodes.IFEQ, original);
                            visitInsn(Opcodes.ICONST_1);
                            visitInsn(Opcodes.IRETURN);
                            visitLabel(original);
                            visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        }
                    };
                }
                return target;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] enableSteamChat(byte[] bytes) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int[] edits = new int[2];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, method, descriptor, signature, exceptions);
                boolean opening = "func_238207_b_".equals(method) && "(Ljava/lang/String;)V".equals(descriptor);
                boolean receiving = "func_238198_a_".equals(method) && "(Ljava/util/UUID;)Z".equals(descriptor);
                if (!opening && !receiving) return target;
                return new MethodVisitor(Opcodes.ASM9, target) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
                        super.visitMethodInsn(opcode, owner, name, desc, isInterface);
                        if (opcode != Opcodes.INVOKEVIRTUAL || !CLIENT.equals(owner)
                                || !"func_238217_s_".equals(name) || !"()Z".equals(desc)) return;
                        visitVarInsn(Opcodes.ALOAD, 0);
                        if (opening) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, MULTIPLAYER, "allowSteamGuestChat",
                                "(ZLjava/lang/Object;)Z", false);
                            edits[0]++;
                        } else {
                            visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, MULTIPLAYER, "allowSteamHostChat",
                                "(ZLjava/lang/Object;Ljava/util/UUID;)Z", false);
                            edits[1]++;
                        }
                    }
                };
            }
        }, 0);
        return edits[0] == 1 && edits[1] == 1 ? writer.toByteArray() : null;
    }

    // A Steam bridge supplies the established play connection and does not
    // support a second status ping on the same local endpoint.
    private static byte[] useConnectedPlayerCount(byte[] bytes) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int[] edits = new int[2];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, method, descriptor, signature, exceptions);
                if (!"parseVars".equals(method) || !"(Ljava/lang/String;)Ljava/lang/String;".equals(descriptor))
                    return target;
                return new MethodVisitor(Opcodes.ASM9, target) {
                    private boolean populationRead;

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
                        if ("net/minecraft/client/network/ServerPinger".equals(owner) &&
                                "func_147224_a".equals(name) &&
                                "(Lnet/minecraft/client/multiplayer/ServerData;Ljava/lang/Runnable;)V".equals(desc)) {
                            visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, MULTIPLAYER, "pingForMaximum",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Runnable;Ljava/lang/String;)V", false);
                            edits[0]++;
                            return;
                        }
                        if (populationRead && "net/minecraft/util/text/ITextComponent".equals(owner) &&
                                "getString".equals(name) && "()Ljava/lang/String;".equals(desc)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, MULTIPLAYER, "populationText",
                                "(Ljava/lang/Object;)Ljava/lang/String;", false);
                            populationRead = false;
                            edits[1]++;
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, name, desc, isInterface);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                        super.visitFieldInsn(opcode, owner, name, desc);
                        populationRead = opcode == Opcodes.GETFIELD &&
                            "net/minecraft/client/multiplayer/ServerData".equals(owner) &&
                            "field_78846_c".equals(name) &&
                            "Lnet/minecraft/util/text/ITextComponent;".equals(desc);
                    }
                };
            }
        }, 0);
        return edits[0] == 1 && edits[1] == 1 ? writer.toByteArray() : null;
    }

    private static byte[] enableSteamMultiplayerButton(byte[] bytes) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int[] edits = new int[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, method, descriptor, signature, exceptions);
                if (!"func_73969_a".equals(method) || !"(II)V".equals(descriptor)) return target;
                return new MethodVisitor(Opcodes.ASM9, target) {
                    private boolean multiplayer;

                    @Override
                    public void visitLdcInsn(Object value) {
                        if ("menu.multiplayer".equals(value)) multiplayer = true;
                        super.visitLdcInsn(value);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                        if (multiplayer && opcode == Opcodes.PUTFIELD &&
                                "net/minecraft/client/gui/widget/button/Button".equals(owner) &&
                                "field_230693_o_".equals(field) && "Z".equals(desc)) {
                            visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/client/gui/screen/MainMenuScreen",
                                "field_230706_i_", "Lnet/minecraft/client/Minecraft;");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, MULTIPLAYER, "enableMultiplayerMenu",
                                "(ZLjava/lang/Object;)Z", false);
                            multiplayer = false;
                            edits[0]++;
                        }
                        super.visitFieldInsn(opcode, owner, field, desc);
                    }
                };
            }
        }, 0);
        return edits[0] == 1 ? writer.toByteArray() : null;
    }
}
