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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Label;

/** Adapts the existing launcher skin registry to the Java 8 authlib in this pack. */
public final class LegacySkinAgent implements ClassFileTransformer {
    private static final String SESSION = "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService";
    private static final String SKINS = "minecraft/portable/identity/PortableSkinProfiles";
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
        if (!SESSION.equals(name)) return null;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9, writer) {
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
                            visitMethodInsn(Opcodes.INVOKESTATIC, SKINS, "isPortableSkin", "(Ljava/lang/Object;)Z", false);
                            visitJumpInsn(Opcodes.IFEQ, original);
                            visitVarInsn(Opcodes.ALOAD, 1);
                            visitMethodInsn(Opcodes.INVOKESTATIC, SKINS, "apply", "(Ljava/lang/Object;)V", false);
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
}
