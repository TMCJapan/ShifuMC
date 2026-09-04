// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.util.Locale;

/** ランチャは fabric-loader より前に走るので、ロギング実装に頼らない。 */
final class Log {
	private Log() {
	}

	static void info(String format, Object... args) {
		System.out.println("[shifu] " + String.format(Locale.ROOT, format, args));
	}

	static void warn(String format, Object... args) {
		System.out.println("[shifu] WARN " + String.format(Locale.ROOT, format, args));
	}
}
