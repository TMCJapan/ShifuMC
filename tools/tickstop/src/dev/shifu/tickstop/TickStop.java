// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.tickstop;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 測定用の Java agent。vanilla と Shifu の両方に同じものを付けて、両者を同じ条件で走らせる。
 *
 * <ul>
 *   <li>{@code RandomSupport.generateUniqueSeed()} を、時刻を混ぜない決定的な列に置き換える。
 *       vanilla は {@code seedUniquifier ^ System.nanoTime()} で、走らせるたびに乱数が変わる。</li>
 *   <li>{@code MinecraftServer.tickServer} の先頭で tick を数え、N tick 目で {@code halt(false)} を呼ぶ。
 *       そのあと vanilla の停止処理(保存)がそのまま走る。</li>
 * </ul>
 *
 * 製品には入れない。世界生成のあと(tick される範囲)の処理順を突き合わせるためだけのもの。
 *
 * <pre>java -javaagent:tickstop.jar=1200 -jar server.jar --nogui</pre>
 */
public final class TickStop {
    private static long limit = 1200;
    private static long ticks;
    private static long seed = 8682522807148012L;

    private TickStop() {
    }

    public static void premain(final String args, final Instrumentation instrumentation) {
        if (args != null && !args.isBlank()) {
            limit = Long.parseLong(args.trim());
        }

        System.err.println("[tickstop] stop after " + limit + " ticks, deterministic seeds");
        instrumentation.addTransformer(new Transformer());
    }

    /** {@code RandomSupport.generateUniqueSeed()} の代わり。呼ぶたびに違う値、走らせるたびに同じ列。 */
    public static synchronized long nextSeed() {
        seed *= 1181783497276652981L;
        return seed;
    }

    /** {@code MinecraftServer.tickServer} の先頭。 */
    public static void onTick(final Object server) {
        ticks++;

        if (ticks == limit) {
            System.err.println("[tickstop] tick " + ticks + " reached, halting");

            try {
                server.getClass().getMethod("halt", boolean.class).invoke(server, false);
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException("halt(boolean) が呼べない", e);
            }
        }
    }

    private static final class Transformer implements ClassFileTransformer {
        @Override
        public byte[] transform(final ClassLoader loader, final String name, final Class<?> redefined,
                                final ProtectionDomain domain, final byte[] bytes) {
            if ("net/minecraft/world/level/levelgen/RandomSupport".equals(name)) {
                return rewrite(bytes, new SeedVisitor());
            }

            if ("net/minecraft/server/MinecraftServer".equals(name)) {
                return rewrite(bytes, new TickVisitor());
            }

            return null;
        }

        private static byte[] rewrite(final byte[] bytes, final Shape shape) {
            try {
                final ClassReader reader = new ClassReader(bytes);
                final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                shape.attach(writer);
                reader.accept(shape, 0);
                System.err.println("[tickstop] transformed " + reader.getClassName() + " (" + shape.getClass().getSimpleName() + ")");
                return writer.toByteArray();
            } catch (final RuntimeException | LinkageError e) {
                // JVM は transformer の例外を黙って捨てるので、ここで出す
                System.err.println("[tickstop] transform failed: " + e);
                e.printStackTrace();
                return null;
            }
        }
    }

    private abstract static class Shape extends ClassVisitor {
        Shape() {
            super(Opcodes.ASM9);
        }

        void attach(final ClassVisitor next) {
            this.cv = next;
        }
    }

    /** generateUniqueSeed() の本体を捨てて、nextSeed() を返すだけの版を足す。 */
    private static final class SeedVisitor extends Shape {
        private boolean dropped;

        @Override
        public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                         final String signature, final String[] exceptions) {
            if ("generateUniqueSeed".equals(name) && "()J".equals(descriptor)) {
                this.dropped = true;
                return null;
            }

            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }

        @Override
        public void visitEnd() {
            if (!this.dropped) {
                throw new IllegalStateException("RandomSupport.generateUniqueSeed()J が無い");
            }

            final MethodVisitor method = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "generateUniqueSeed", "()J", null, null);
            method.visitCode();
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/shifu/tickstop/TickStop", "nextSeed", "()J", false);
            method.visitInsn(Opcodes.LRETURN);
            method.visitMaxs(2, 0);
            method.visitEnd();
            super.visitEnd();
        }
    }

    /** tickServer(BooleanSupplier) の先頭に onTick(this) を足す。 */
    private static final class TickVisitor extends Shape {
        private boolean found;

        @Override
        public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                         final String signature, final String[] exceptions) {
            final MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);

            if (!"tickServer".equals(name) || !"(Ljava/util/function/BooleanSupplier;)V".equals(descriptor)) {
                return next;
            }

            this.found = true;

            return new MethodVisitor(Opcodes.ASM9, next) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/shifu/tickstop/TickStop", "onTick", "(Ljava/lang/Object;)V", false);
                }
            };
        }

        @Override
        public void visitEnd() {
            if (!this.found) {
                throw new IllegalStateException("MinecraftServer.tickServer(BooleanSupplier) が無い");
            }

            super.visitEnd();
        }
    }
}
