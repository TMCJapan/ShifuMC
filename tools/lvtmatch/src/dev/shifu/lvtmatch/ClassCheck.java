package dev.shifu.lvtmatch;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * クラスファイルが JVM に読めるかを確かめる。
 *
 * <pre>
 * java -cp tools/build/lvtmatch dev.shifu.lvtmatch.ClassCheck <クラスの置き場> <相対パスの一覧>
 * </pre>
 *
 * <p>paperweight が codebook で名前を戻した公式 jar には、{@code javap} では正常に見えるのに
 * JVM が {@code ClassFormatError: Illegal field name} で弾くクラスが混ざる
 * (1.21.11 の {@code BundlerInfo$1$1})。{@code keep_vanilla_classes.py} が公式の
 * バイトコードへ戻したあと、そのクラスを 1 つずつ読ませて落ちるものを並べる。
 *
 * <p>見るのは読み込み(parse)だけ。親クラスがライブラリにあって解決できない
 * {@code NoClassDefFoundError} は parse が通ったあとに出るので、失敗に数えない。
 */
public final class ClassCheck {
    public static void main(final String[] args) throws IOException {
        final Path classes = Path.of(args[0]);
        final List<String> names = Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8);
        int failed = 0;

        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            for (final String rel : names) {
                if (rel.isBlank() || !rel.endsWith(".class")) {
                    continue;
                }

                final String name = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');

                try {
                    Class.forName(name, false, loader);
                } catch (final ClassFormatError e) {
                    System.out.println("FAIL " + rel + " " + e);
                    failed++;
                } catch (final NoClassDefFoundError | ClassNotFoundException e) {
                    // 親や参照先がここに無いだけ。parse は通っている
                } catch (final Throwable t) {
                    System.out.println("FAIL " + rel + " " + t);
                    failed++;
                }
            }
        }

        System.out.println("failed " + failed);
    }
}
