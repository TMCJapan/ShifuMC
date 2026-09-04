// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小の JSON パーサ。
 *
 * <p>ランチャは fabric-loader より前に走るので、外部ライブラリを取りに行けない。
 * 使う API は fill.papermc.io と meta.fabricmc.net の 2 つだけなので、
 * オブジェクト・配列・文字列・数値・真偽・null が読めれば足りる。
 */
final class Json {
	private final String src;
	private int pos;

	private Json(String src) {
		this.src = src;
	}

	static Object parse(String src) {
		Json json = new Json(src);
		json.skipWhitespace();
		Object value = json.readValue();
		json.skipWhitespace();

		if (json.pos != src.length()) {
			throw new IllegalArgumentException("trailing content at " + json.pos);
		}

		return value;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> object(Object value, String... path) {
		for (String key : path) {
			value = ((Map<String, Object>) value).get(key);
			if (value == null) throw new IllegalArgumentException("missing key: " + key);
		}

		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	static List<Object> array(Object value) {
		return (List<Object>) value;
	}

	static String string(Object value) {
		return (String) value;
	}

	private Object readValue() {
		char c = peek();

		return switch (c) {
			case '{' -> readObject();
			case '[' -> readArray();
			case '"' -> readString();
			case 't' -> readLiteral("true", Boolean.TRUE);
			case 'f' -> readLiteral("false", Boolean.FALSE);
			case 'n' -> readLiteral("null", null);
			default -> readNumber();
		};
	}

	private Map<String, Object> readObject() {
		Map<String, Object> result = new LinkedHashMap<>();
		expect('{');
		skipWhitespace();

		if (peek() == '}') {
			pos++;
			return result;
		}

		while (true) {
			skipWhitespace();
			String key = readString();
			skipWhitespace();
			expect(':');
			skipWhitespace();
			result.put(key, readValue());
			skipWhitespace();

			char c = src.charAt(pos++);
			if (c == '}') return result;
			if (c != ',') throw new IllegalArgumentException("expected , or } at " + (pos - 1));
		}
	}

	private List<Object> readArray() {
		List<Object> result = new ArrayList<>();
		expect('[');
		skipWhitespace();

		if (peek() == ']') {
			pos++;
			return result;
		}

		while (true) {
			skipWhitespace();
			result.add(readValue());
			skipWhitespace();

			char c = src.charAt(pos++);
			if (c == ']') return result;
			if (c != ',') throw new IllegalArgumentException("expected , or ] at " + (pos - 1));
		}
	}

	private String readString() {
		expect('"');
		StringBuilder sb = new StringBuilder();

		while (true) {
			char c = src.charAt(pos++);

			if (c == '"') return sb.toString();

			if (c != '\\') {
				sb.append(c);
				continue;
			}

			char escape = src.charAt(pos++);

			switch (escape) {
				case '"', '\\', '/' -> sb.append(escape);
				case 'b' -> sb.append('\b');
				case 'f' -> sb.append('\f');
				case 'n' -> sb.append('\n');
				case 'r' -> sb.append('\r');
				case 't' -> sb.append('\t');
				case 'u' -> {
					sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
					pos += 4;
				}
				default -> throw new IllegalArgumentException("bad escape \\" + escape + " at " + (pos - 1));
			}
		}
	}

	private Object readLiteral(String literal, Object value) {
		if (!src.startsWith(literal, pos)) {
			throw new IllegalArgumentException("expected " + literal + " at " + pos);
		}

		pos += literal.length();

		return value;
	}

	private Double readNumber() {
		int start = pos;

		while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
			pos++;
		}

		return Double.valueOf(src.substring(start, pos));
	}

	private char peek() {
		if (pos >= src.length()) throw new IllegalArgumentException("unexpected end of input");

		return src.charAt(pos);
	}

	private void expect(char expected) {
		if (peek() != expected) {
			throw new IllegalArgumentException("expected " + expected + " at " + pos);
		}

		pos++;
	}

	private void skipWhitespace() {
		while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
			pos++;
		}
	}
}
