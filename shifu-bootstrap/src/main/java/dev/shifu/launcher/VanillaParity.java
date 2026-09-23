// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Paper が vanilla から変えている挙動のうち、設定で戻せるものを戻す。
 *
 * <p>ここに入れてよいのは「Paper 26.2 のバイトコードで実際に読まれていること」と
 * 「どの値が vanilla 相当か」の両方を確認した項目だけ。
 * 確認していない設定を書くと、直すつもりで別の挙動を壊す。
 *
 * <p>戻せないものはバイトコード側で扱う({@code VanillaParityRules})。
 * どちらでも戻せないものは docs/VANILLA-PARITY.md に列挙する。
 *
 * <p>持ち主の記録は {@code .shifu/vanilla-parity-generated.properties} に
 * <strong>鍵ごとに</strong>置く。ファイル単位で持っていたときは、Paper が書き直した設定に
 * 毎回 8 つの鍵を書き戻していて、利用者が直した値が起動のたびに消えていた。
 */
final class VanillaParity {
	private static final String GENERATED_MARKER = "# Shifu が vanilla 挙動に合わせるために書いた";
	private static final String OWNERSHIP_FILE = "vanilla-parity-generated.properties";
	private static final char RECORD_SEPARATOR = '|';
	/** 鍵がそもそも無かったことを表す。戻すときはその鍵ごと消す。 */
	private static final String ABSENT = "(absent)";

	/**
	 * Spigot は既定でエンティティの活性化範囲を絞り、範囲外のエンティティを tick しない。
	 * 範囲を広げても vanilla には届かない。活性の判定に使う箱は
	 * {@code maxRange = Math.min((simulationDistance << 4) - 8, maxRange)} で頭を押さえられるので、
	 * 既定の simulation-distance 10 では 152 ブロック、最大の 32 でも 504 ブロックにしかならない。
	 * 0 にすると {@code initializeEntityActivationState} がその分類の全エンティティで true を返し、
	 * {@code defaultActivationState} が立って {@code checkIfActive} が常に true を返す
	 * = vanilla と同じ「シミュレーション距離内は全部 tick する」になる。
	 * (読んだ位置: Paper-Server src/main/java/org/spigotmc/ActivationRange.java:194 と :133、:388。
	 * mache の版は paper-server patches/features/*-Entity-Activation-Range-2.0.patch の同じ行)
	 *
	 * <p>max-tnt-per-tick は {@code PrimedTnt#tick} が {@code > 0} のときだけ制限するので、
	 * 0 で vanilla と同じ(制限なし)になる
	 * (読んだ位置: Paper-Server src/main/java/net/minecraft/world/entity/item/PrimedTnt.java:78)。
	 */
	private static final Map<String, String> SPIGOT_VALUES = Map.of(
			"world-settings.default.entity-activation-range.animals", "0",
			"world-settings.default.entity-activation-range.monsters", "0",
			"world-settings.default.entity-activation-range.raiders", "0",
			"world-settings.default.entity-activation-range.misc", "0",
			"world-settings.default.entity-activation-range.water", "0",
			"world-settings.default.entity-activation-range.villagers", "0",
			"world-settings.default.entity-activation-range.flying-monsters", "0",
			"world-settings.default.max-tnt-per-tick", "0");

	/**
	 * Paper の per-player mob spawns は湧き上限をプレイヤーごとに数える。
	 * vanilla はワールド単位の 1 つの上限なので、湧き効率の計算がまるごと変わる。
	 * 既定が true なので false に戻す。
	 * ({@code NaturalSpawner} / {@code ServerChunkCache} / {@code ChunkMap} で参照されている)
	 */
	private static final Map<String, String> PAPER_WORLD_VALUES = Map.of(
			"entities.spawning.per-player-mob-spawns", "false");

	/**
	 * 1.19 より前の Paper はワールドの設定を {@code paper.yml} の {@code world-settings.default} で読む
	 * ({@code config/paper-world-defaults.yml} は 1.19 で入った)。書く場所を版で分ける。
	 * 1.18.2 では {@code config/} に書いても読まれず、per-player-mob-spawns が true のまま走っていた。
	 */
	private static final Map<String, String> PAPER_YML_VALUES = Map.of(
			"world-settings.default.per-player-mob-spawns", "false");

	private static final Map<String, String> MANAGED_VALUES = managedValues();

	private static final String SPIGOT_YML = """
			# Shifu が vanilla 挙動に合わせるために書いた spigot.yml。
			# 書かなかった項目は Paper が既定値で埋める。
			#
			# ここに書いてあるものは Paper 26.2 のバイトコードで
			#   1. その設定が実際に読まれていること
			#   2. どの値が vanilla 相当か
			# の両方を確認した上で入れてある。詳細は docs/VANILLA-PARITY.md。
			world-settings:
			  default:
			    # 0 で活性化範囲の判定そのものが外れ、vanilla と同じく
			    # シミュレーション距離内のエンティティを全て tick する。
			    entity-activation-range:
			      animals: 0
			      monsters: 0
			      raiders: 0
			      misc: 0
			      water: 0
			      villagers: 0
			      flying-monsters: 0
			    # vanilla には 1 tick あたりの TNT 数の上限が無い。0 で制限が外れる。
			    max-tnt-per-tick: 0
			""";

	private static final String PAPER_WORLD_DEFAULTS_YML = """
			# Shifu が vanilla 挙動に合わせるために書いた paper-world-defaults.yml。
			# 書かなかった項目は Paper が既定値で埋める。詳細は docs/VANILLA-PARITY.md。
			entities:
			  spawning:
			    # vanilla の湧き上限はワールド単位。Paper の既定はプレイヤーごと。
			    per-player-mob-spawns: false
			""";

	private static final String PAPER_YML_LEGACY = """
			# Shifu が vanilla 挙動に合わせるために書いた paper.yml(1.19 より前)。
			# 書かなかった項目は Paper が既定値で埋める。詳細は docs/VANILLA-PARITY.md。
			world-settings:
			  default:
			    # vanilla の湧き上限はワールド単位。Paper の既定はプレイヤーごと。
			    per-player-mob-spawns: false
			""";

	private VanillaParity() {
	}

	static void apply(Path serverDir, String minecraftVersion) throws IOException {
		Properties ownership = loadOwnership(serverDir);
		apply(serverDir, spigotYml(serverDir), SPIGOT_YML, SPIGOT_VALUES, ownership);

		if (before119(minecraftVersion)) {
			apply(serverDir, paperYml(serverDir), PAPER_YML_LEGACY, PAPER_YML_VALUES, ownership);
		} else {
			apply(serverDir, paperWorldDefaultsYml(serverDir), PAPER_WORLD_DEFAULTS_YML,
					PAPER_WORLD_VALUES, ownership);
		}

		saveOwnership(serverDir, ownership);
	}

	/** Shifu が書き換えた値だけを元へ戻す。 */
	static void disable(Path serverDir, String minecraftVersion) throws IOException {
		Map<String, Map<String, String>> records = records(loadOwnership(serverDir));

		restore(serverDir, spigotYml(serverDir), SPIGOT_VALUES, records);
		// 版を切り替えたディレクトリには両方が残っているので、版に関係なく 2 つとも見る。
		restore(serverDir, paperYml(serverDir), PAPER_YML_VALUES, records);
		restore(serverDir, paperWorldDefaultsYml(serverDir), PAPER_WORLD_VALUES, records);

		saveOwnership(serverDir, new Properties());
	}

	/** {@code 1.18.2} のような版の文字列が 1.19 より前か。{@code 26.2} のような新しい形は前ではない。 */
	static boolean before119(String version) {
		final String[] parts = version.split("\\.");

		try {
			final int major = Integer.parseInt(parts[0]);
			final int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

			return major == 1 && minor < 19;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/**
	 * 1 つの鍵に書き込むのは 1 度だけで、書く前の値を記録する。
	 * 2 度目以降はその欄の持ち主が利用者に移るので触らない。
	 */
	private static void apply(Path serverDir, Path file, String content, Map<String, String> values,
			Properties ownership) throws IOException {
		String relative = relative(serverDir, file);

		if (!Files.exists(file)) {
			Files.createDirectories(file.getParent());
			Files.writeString(file, content, StandardCharsets.UTF_8);
			values.keySet().forEach(path -> ownership.setProperty(record(relative, path), ABSENT));
			Log.info("wrote %s with vanilla-parity settings", file.getFileName());
			return;
		}

		if (!Files.isRegularFile(file)) {
			Log.warn("%s is not a regular file - leaving it alone", file.getFileName());
			return;
		}

		String original = Files.readString(file, StandardCharsets.UTF_8);
		List<String> lines = new ArrayList<>(original.lines().toList());
		int applied = 0;

		for (Map.Entry<String, String> setting : values.entrySet()) {
			String key = record(relative, setting.getKey());

			if (ownership.containsKey(key)) {
				continue;
			}

			YamlLookup found = lookup(lines, setting.getKey());
			ownership.setProperty(key, found.line() < 0 ? ABSENT : scalar(found.value()));

			if (setYamlScalar(lines, setting.getKey(), setting.getValue())) {
				applied++;
			}
		}

		if (applied > 0) {
			writeLines(file, original, lines);
			Log.info("applied %d vanilla-parity settings to %s", applied, file.getFileName());
		}
	}

	/**
	 * 記録がある鍵を書く前の値へ戻す。いま入っている値が Shifu の書いたものでなければ、
	 * 利用者が後から変えたものなので触らない。
	 */
	private static void restore(Path serverDir, Path file, Map<String, String> values,
			Map<String, Map<String, String>> allRecords) throws IOException {
		if (!Files.isRegularFile(file)) {
			return;
		}

		String original = Files.readString(file, StandardCharsets.UTF_8);

		if (isKnownGeneratedContent(original, file)) {
			Files.delete(file);
			Log.info("removed Shifu-generated %s because vanilla-parity is off", file.getFileName());
			return;
		}

		Map<String, String> records = allRecords.getOrDefault(relative(serverDir, file), Map.of());

		// 鍵ごとの記録を持つ前のランチャが作ったファイル。印がある間は値も Shifu のもの。
		if (records.isEmpty() && original.contains(GENERATED_MARKER)) {
			records = new LinkedHashMap<>();
			for (String path : values.keySet()) {
				records.put(path, ABSENT);
			}
		}

		List<String> lines = new ArrayList<>(original.lines().toList());
		boolean changed = lines.removeIf(line -> line.trim().contains(GENERATED_MARKER));
		int restored = 0;

		for (Map.Entry<String, String> record : records.entrySet()) {
			String managed = MANAGED_VALUES.get(record.getKey());

			if (managed == null || !scalarEquals(lookup(lines, record.getKey()).value(), managed)) {
				continue;
			}

			if (ABSENT.equals(record.getValue())
					? removeYamlScalar(lines, record.getKey())
					: setYamlScalar(lines, record.getKey(), record.getValue())) {
				restored++;
			}
		}

		if (restored == 0 && !changed) {
			return;
		}

		if (lines.stream().allMatch(String::isBlank)) {
			Files.delete(file);
			Log.info("removed Shifu-generated %s because vanilla-parity is off", file.getFileName());
			return;
		}

		writeLines(file, original, lines);
		Log.info("restored %d settings in %s because vanilla-parity is off", restored, file.getFileName());
	}

	private static Path spigotYml(Path serverDir) {
		return serverDir.resolve("spigot.yml");
	}

	private static Path paperYml(Path serverDir) {
		return serverDir.resolve("paper.yml");
	}

	private static Path paperWorldDefaultsYml(Path serverDir) {
		return serverDir.resolve("config").resolve("paper-world-defaults.yml");
	}

	private static Map<String, String> managedValues() {
		Map<String, String> all = new LinkedHashMap<>(SPIGOT_VALUES);
		all.putAll(PAPER_WORLD_VALUES);
		all.putAll(PAPER_YML_VALUES);
		return Map.copyOf(all);
	}

	private static boolean isKnownGeneratedContent(String content, Path file) {
		String expected = switch (file.getFileName().toString()) {
			case "spigot.yml" -> SPIGOT_YML;
			case "paper.yml" -> PAPER_YML_LEGACY;
			case "paper-world-defaults.yml" -> PAPER_WORLD_DEFAULTS_YML;
			default -> null;
		};

		return expected != null && content.equals(expected);
	}

	private static void writeLines(Path file, String original, List<String> lines) throws IOException {
		String newline = original.contains("\r\n") ? "\r\n" : "\n";
		boolean trailingNewline = original.endsWith("\n") || original.endsWith("\r");

		Files.writeString(file, String.join(newline, lines) + (trailingNewline ? newline : ""),
				StandardCharsets.UTF_8);
	}

	/** {@code path} が指す行と、無いときに足す位置を 1 回の走査で見つける。 */
	private static YamlLookup lookup(List<String> lines, String path) {
		Deque<YamlNode> parents = new ArrayDeque<>();
		YamlInsertion best = null;

		for (int i = 0; i < lines.size(); i++) {
			YamlEntry entry = parseYamlEntry(lines.get(i));

			if (entry == null) {
				continue;
			}

			while (!parents.isEmpty() && parents.peekLast().indent() >= entry.indent()) {
				parents.removeLast();
			}

			StringBuilder current = new StringBuilder();
			for (YamlNode parent : parents) {
				current.append(parent.key()).append('.');
			}
			current.append(entry.key());
			String currentPath = current.toString();

			if (currentPath.equals(path)) {
				return new YamlLookup(i, List.copyOf(parents), best, entry.value());
			}

			if (entry.value().isEmpty()) {
				int depth = parents.size() + 1;
				if (path.startsWith(currentPath + ".") && (best == null || depth > best.depth())) {
					best = new YamlInsertion(entry.indent(), i, depth);
				}
				parents.addLast(new YamlNode(entry.indent(), entry.key(), i));
			}
		}

		return new YamlLookup(-1, List.of(), best, null);
	}

	private static boolean setYamlScalar(List<String> lines, String path, String value) {
		YamlLookup found = lookup(lines, path);

		if (found.line() >= 0) {
			if (scalarEquals(found.value(), value)) {
				return false;
			}

			String line = lines.get(found.line());
			int colon = line.indexOf(':');
			int comment = line.indexOf(" #", colon + 1);
			String suffix = comment >= 0 ? line.substring(comment) : "";
			lines.set(found.line(), line.substring(0, colon + 1) + " " + value + suffix);
			return true;
		}

		int insertion = lines.size();
		int indent = 0;
		int depth = 0;

		if (found.insertion() != null) {
			insertion = subtreeEnd(lines, found.insertion().line(), found.insertion().indent());
			indent = found.insertion().indent() + 2;
			depth = found.insertion().depth();
		}

		String[] keys = path.split("\\.");
		List<String> added = new ArrayList<>();

		for (int i = depth; i < keys.length; i++) {
			boolean leaf = i == keys.length - 1;
			added.add(" ".repeat(indent) + keys[i] + ":" + (leaf ? " " + value : ""));
			indent += 2;
		}

		lines.addAll(insertion, added);
		return true;
	}

	/** 鍵ごと消す。中身が無くなった親も消すが、Paper や利用者が足した行は残す。 */
	private static boolean removeYamlScalar(List<String> lines, String path) {
		YamlLookup found = lookup(lines, path);

		if (found.line() < 0) {
			return false;
		}

		lines.remove(found.line());
		List<YamlNode> parents = found.parents();

		// 深い方から消す。親の行番号は子より小さいので、消しても前の要素はずれない。
		for (int i = parents.size() - 1; i >= 0; i--) {
			YamlNode parent = parents.get(i);

			if (!hasYamlChild(lines, parent.line(), parent.indent())) {
				lines.remove(parent.line());
			}
		}

		return true;
	}

	private static int subtreeEnd(List<String> lines, int parentLine, int parentIndent) {
		for (int i = parentLine + 1; i < lines.size(); i++) {
			String trimmed = lines.get(i).trim();
			if (!trimmed.isEmpty() && !trimmed.startsWith("#") && indentation(lines.get(i)) <= parentIndent) {
				return i;
			}
		}
		return lines.size();
	}

	private static boolean hasYamlChild(List<String> lines, int parentLine, int parentIndent) {
		for (int i = parentLine + 1; i < lines.size(); i++) {
			String trimmed = lines.get(i).trim();

			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}

			return indentation(lines.get(i)) > parentIndent;
		}

		return false;
	}

	private static YamlEntry parseYamlEntry(String line) {
		String trimmed = line.trim();
		int colon = trimmed.indexOf(':');
		if (colon <= 0 || trimmed.startsWith("-") || trimmed.startsWith("#")) {
			return null;
		}
		return new YamlEntry(indentation(line), trimmed.substring(0, colon).trim(),
				trimmed.substring(colon + 1).trim());
	}

	private static int indentation(String line) {
		int indent = 0;
		while (indent < line.length() && line.charAt(indent) == ' ') {
			indent++;
		}
		return indent;
	}

	private static boolean scalarEquals(String actual, String expected) {
		return actual != null && scalar(actual).equalsIgnoreCase(expected);
	}

	/** 行末の注記と引用符を落とした値。 */
	private static String scalar(String raw) {
		int comment = raw.indexOf(" #");
		String value = (comment >= 0 ? raw.substring(0, comment) : raw).trim();
		if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
				|| (value.startsWith("'") && value.endsWith("'")))) {
			value = value.substring(1, value.length() - 1);
		}
		return value;
	}

	private record YamlNode(int indent, String key, int line) {
	}

	private record YamlEntry(int indent, String key, String value) {
	}

	private record YamlInsertion(int indent, int line, int depth) {
	}

	private record YamlLookup(int line, List<YamlNode> parents, YamlInsertion insertion, String value) {
	}

	private static String record(String relative, String path) {
		return relative + RECORD_SEPARATOR + path;
	}

	/** 記録をファイル名ごとにまとめ直す。 */
	private static Map<String, Map<String, String>> records(Properties ownership) {
		Map<String, Map<String, String>> byFile = new LinkedHashMap<>();

		for (String key : ownership.stringPropertyNames()) {
			int separator = key.indexOf(RECORD_SEPARATOR);

			if (separator <= 0) {
				continue;
			}

			byFile.computeIfAbsent(key.substring(0, separator), relative -> new LinkedHashMap<>())
					.put(key.substring(separator + 1), ownership.getProperty(key));
		}

		return byFile;
	}

	private static String relative(Path serverDir, Path file) {
		return serverDir.relativize(file).toString().replace('\\', '/');
	}

	private static Path ownershipPath(Path serverDir) {
		return serverDir.resolve(".shifu").resolve(OWNERSHIP_FILE);
	}

	private static Properties loadOwnership(Path serverDir) throws IOException {
		Properties ownership = new Properties();
		Path file = ownershipPath(serverDir);

		if (Files.isRegularFile(file)) {
			try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				ownership.load(in);
			}
		}

		return ownership;
	}

	private static void saveOwnership(Path serverDir, Properties ownership) throws IOException {
		Path file = ownershipPath(serverDir);

		if (ownership.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}

		Files.createDirectories(file.getParent());

		try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			ownership.store(out, "Settings Shifu changed for vanilla parity (key = the value it found)");
		}
	}
}
