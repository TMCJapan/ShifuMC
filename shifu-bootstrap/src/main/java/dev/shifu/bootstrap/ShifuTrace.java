// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.bootstrap;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * vanilla と候補実装の処理順を突き合わせるためのトレース。
 *
 * <p>「vanilla と完全一致」を条件にする以上、一致していないことを検出できる仕組みが要る。
 * コードレビューでは処理順の差は捕まらないので、両方に同じ計測器を当てて出力を差分する。
 *
 * <p>{@code -Dshifu.trace=<file>} を付けたときだけ動く。
 * 呼び出しは {@link TraceRules} がバイトコードに差し込む。
 * NMS の型に依存しないよう、引数は {@code Object} で受けて文字列化する。
 */
public final class ShifuTrace {
	private static final BufferedWriter WRITER = open();
	private static long sequence;

	private ShifuTrace() {
	}

	private static BufferedWriter open() {
		String target = System.getProperty("shifu.trace");

		if (target == null) return null;

		try {
			Path path = Path.of(target).toAbsolutePath();
			Files.createDirectories(path.getParent());

			System.out.println("[shifu] trace opened: " + path
					+ " (loader " + ShifuTrace.class.getClassLoader() + ")");

			return Files.newBufferedWriter(path, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static boolean enabled() {
		return WRITER != null;
	}

	/**
	 * サーバー tick の区切り。ここまでの順序を tick 単位で比較できるようにする。
	 *
	 * <p>ここで flush する。Minecraft の停止経路は {@code Runtime.halt} を通ることがあり、
	 * その場合シャットダウンフックが走らないので、溜めたまま終わると全部消える。
	 */
	public static void tick() {
		write("tick");
		flush();
	}

	private static synchronized void flush() {
		if (WRITER == null) return;

		try {
			WRITER.flush();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static void setBlock(Object pos, Object state, int flags) {
		write("setBlock " + pos + " " + state + " flags=" + flags);
	}

	public static void updateNeighborsAt(Object pos, Object block, Object orientation) {
		write("updateNeighborsAt " + pos + " " + name(block) + " " + orientation);
	}

	public static void neighborChanged(Object pos, Object block, Object orientation) {
		write("neighborChanged " + pos + " " + name(block) + " " + orientation);
	}

	public static void tickBlock(Object pos, Object block) {
		write("tickBlock " + pos + " " + name(block));
	}

	/**
	 * ブロックのクラス名だけを出す。
	 * {@code toString()} はインスタンスごとのハッシュを含むことがあり、比較に使えない。
	 */
	private static String name(Object block) {
		return block == null ? "null" : block.getClass().getName();
	}

	private static synchronized void write(String line) {
		if (WRITER == null) return;

		try {
			WRITER.write(Long.toString(sequence++));
			WRITER.write(' ');
			WRITER.write(line);
			WRITER.write('\n');

			if ((sequence & 0x3FF) == 0) WRITER.flush();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** JVM 終了時に取りこぼさないようにする。 */
	static {
		if (WRITER != null) {
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					WRITER.flush();
					WRITER.close();
				} catch (IOException ignored) {
					// 終了処理なので握りつぶす
				}
			}, "shifu-trace-flush"));
		}
	}
}
