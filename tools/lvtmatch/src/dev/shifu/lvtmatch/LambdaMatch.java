// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.lvtmatch;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * lambda の合成メソッドの形を Mojang の公式クラスに合わせる。
 *
 * <pre>java dev.shifu.lvtmatch.LambdaMatch &lt;クラスの置き場&gt; &lt;Mojang の jar&gt; &lt;対象の一覧&gt;</pre>
 *
 * <h2>何が違うか</h2>
 *
 * javac は {@code x -> this.foo(x)} の本体を **instance の** 合成メソッドにする。
 * Mojang の jar は ProGuard を通っていて、それが **static で受け手を第 1 引数に取る**
 * 形に変わっている。
 *
 * <pre>
 * 公式  : private static void lambda$load$0(ServerScoreboard, Objective$Packed)
 * こちら: private        void lambda$load$0(Objective$Packed)
 * </pre>
 *
 * 名前で狙う mixin は当たるが、署名まで書く mixin は当たらない。
 * ソースからは作り分けられない(javac は必ず instance にする)ので、
 * バイトコードで合わせる。
 *
 * <h2>やること</h2>
 *
 * instance のときの slot 0 は {@code this}、static で受け手を第 1 引数にしたときの
 * slot 0 はその引数。**本体の命令列は 1 つも変わらない。** 変えるのは 3 つだけ。
 *
 * <ol>
 * <li>メソッドを ACC_STATIC にして、署名の先頭に所有クラスを足す</li>
 * <li>その lambda を指す invokedynamic の method handle を
 *     {@code H_INVOKESPECIAL} から {@code H_INVOKESTATIC} に変え、署名を同じように直す</li>
 * <li>BasicVerifier に通し、通らなければ元に戻す</li>
 * </ol>
 *
 * indy が捕まえている引数({@code this})は変わらないので、呼び出し側は触らない。
 */
public final class LambdaMatch {

    public static void main(String[] args) throws Exception {
        Path classes = Paths.get(args[0]);
        Path jar = Paths.get(args[1]);
        Path list = Paths.get(args[2]);

        if (!Files.exists(list)) {
            System.out.println("no list");

            return;
        }

        int changed = 0;
        int matched = 0;
        int skipped = 0;

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String line : Files.readAllLines(list)) {
                String name = line.trim();

                if (name.isEmpty()) {
                    continue;
                }

                Path file = classes.resolve(name + ".class");
                ZipEntry official = zip.getEntry(name + ".class");

                if (!Files.exists(file) || official == null) {
                    skipped++;
                    continue;
                }

                ClassNode ours = read(Files.readAllBytes(file));
                ClassNode theirs;

                try (InputStream in = zip.getInputStream(official)) {
                    theirs = read(in.readAllBytes());
                }

                int made = makeStatic(ours, theirs);

                if (made > 0) {
                    ClassWriter writer = new ClassWriter(0);
                    ours.accept(writer);
                    Files.write(file, writer.toByteArray());
                    changed++;
                    matched += made;
                } else {
                    skipped++;
                }
            }
        }

        System.out.println("lambda made static : " + matched);
        System.out.println("rewritten class    : " + changed);
        System.out.println("left alone         : " + skipped);
    }

    private static ClassNode read(byte[] data) {
        ClassNode node = new ClassNode();
        new ClassReader(data).accept(node, ClassReader.EXPAND_FRAMES);

        return node;
    }

    private static int makeStatic(ClassNode ours, ClassNode theirs) {
        Map<String, MethodNode> official = new HashMap<>();

        for (MethodNode method : theirs.methods) {
            if (method.name.startsWith("lambda$")) {
                official.put(method.name, method);
            }
        }

        List<MethodNode> targets = new ArrayList<>();

        for (MethodNode method : ours.methods) {
            if (!method.name.startsWith("lambda$") || (method.access & Opcodes.ACC_STATIC) != 0) {
                continue;
            }

            MethodNode moj = official.get(method.name);

            if (moj == null || (moj.access & Opcodes.ACC_STATIC) == 0) {
                continue;
            }

            // 公式の署名が「所有クラス + こちらの引数」になっているものだけ
            if (moj.desc.equals("(L" + ours.name + ";" + method.desc.substring(1))) {
                targets.add(method);
            }
        }

        if (targets.isEmpty()) {
            return 0;
        }

        Map<String, String> renamed = new HashMap<>();

        for (MethodNode method : targets) {
            renamed.put(method.name + method.desc, "(L" + ours.name + ";" + method.desc.substring(1));
        }

        // 呼び出し側(invokedynamic の method handle)を直す。
        // 直せた分だけを static にする。1 つでも取りこぼすと NoSuchMethodError になる
        List<Runnable> undo = new ArrayList<>();
        Map<String, Integer> fixed = new HashMap<>();

        for (MethodNode method : ours.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof InvokeDynamicInsnNode indy)) {
                    continue;
                }

                for (int i = 0; i < indy.bsmArgs.length; i++) {
                    if (!(indy.bsmArgs[i] instanceof Handle handle)) {
                        continue;
                    }

                    String key = handle.getName() + handle.getDesc();
                    String want = renamed.get(key);

                    if (want == null || !handle.getOwner().equals(ours.name)) {
                        continue;
                    }

                    if (handle.getTag() != Opcodes.H_INVOKESPECIAL && handle.getTag() != Opcodes.H_INVOKEVIRTUAL) {
                        continue;
                    }

                    final int at = i;
                    final Object before = indy.bsmArgs[i];
                    undo.add(() -> indy.bsmArgs[at] = before);
                    indy.bsmArgs[i] = new Handle(Opcodes.H_INVOKESTATIC, ours.name,
                            handle.getName(), want, false);
                    fixed.merge(key, 1, Integer::sum);
                }
            }
        }

        List<MethodNode> ready = new ArrayList<>();

        for (MethodNode method : targets) {
            if (fixed.getOrDefault(method.name + method.desc, 0) == 1) {
                ready.add(method);
            }
        }

        if (ready.size() != targets.size()) {
            // 参照を全部は直せなかった。何もしない
            for (Runnable step : undo) {
                step.run();
            }

            return 0;
        }

        for (MethodNode method : ready) {
            method.access |= Opcodes.ACC_STATIC;
            method.desc = "(L" + ours.name + ";" + method.desc.substring(1);
        }

        for (MethodNode method : ready) {
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(ours.name, method);
            } catch (Throwable e) {
                for (MethodNode back : ready) {
                    back.access &= ~Opcodes.ACC_STATIC;
                    back.desc = "(" + back.desc.substring(("(L" + ours.name + ";").length());
                }

                for (Runnable step : undo) {
                    step.run();
                }

                return 0;
            }
        }

        // 古い署名を指す参照が残っていないか
        for (MethodNode method : ours.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle handle && handle.getOwner().equals(ours.name)
                                && renamed.containsKey(handle.getName() + handle.getDesc())) {
                            for (MethodNode back : ready) {
                                back.access &= ~Opcodes.ACC_STATIC;
                                back.desc = "(" + back.desc.substring(("(L" + ours.name + ";").length());
                            }

                            for (Runnable step : undo) {
                                step.run();
                            }

                            return 0;
                        }
                    }
                }

                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.owner.equals(ours.name)
                        && renamed.containsKey(call.name + call.desc)) {
                    for (MethodNode back : ready) {
                        back.access &= ~Opcodes.ACC_STATIC;
                        back.desc = "(" + back.desc.substring(("(L" + ours.name + ";").length());
                    }

                    for (Runnable step : undo) {
                        step.run();
                    }

                    return 0;
                }
            }
        }

        return ready.size();
    }
}
