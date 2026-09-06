// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.bootstrap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Paper が vanilla のメンバを作り変えたせいで成立しなくなった MOD 側の Mixin を修正する。
 *
 * <p>{@code KnotClassDelegate#getPreMixinClassByteArray} は Mixin クラス自身を含む
 * すべての変換対象クラスに対して {@code provider.getEntrypointTransformer().transform(name)}
 * を呼ぶ。つまり GameProvider の正規の口だけで MOD の Mixin クラスを書き換えられる。
 *
 * <p>ここに並ぶのが Shifu の保守対象の実体である。Cardboard / Taiyitist が抱える
 * 「CraftBukkit のパッチ 558 件を Mixin として書き続ける」に対応するのがこの表で、
 * 対象は「Paper が改名・改型したメンバを踏んでいる注入点」だけに限られる。
 */
final class ShifuCompatTransformer extends GameTransformer {
	private Map<String, List<Rule>> rules = Map.of();
	private FabricLauncher launcher;

	/** 対象が Paper か素の vanilla かは locateGame まで分からないので、規則は後から入れる。 */
	void setRules(Map<String, List<Rule>> rules) {
		this.rules = rules;
	}

	void setLauncher(FabricLauncher launcher) {
		this.launcher = launcher;
	}

	@Override
	public byte[] transform(String name) {
		byte[] input = super.transform(name);
		List<Rule> applicable = rules.get(name);

		if (applicable == null) return input;

		if (input == null) {
			try {
				input = launcher.getClassByteArray(name, false);
			} catch (IOException e) {
				throw new RuntimeException("failed to read " + name + " for Paper compatibility patching", e);
			}

			if (input == null) return null;
		}

		ClassNode node = new ClassNode();
		new ClassReader(input).accept(node, 0);

		for (Rule rule : applicable) {
			rule.apply(node);
		}

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);

		return writer.toByteArray();
	}

	interface Rule {
		void apply(ClassNode node);
	}

	/**
	 * Paper が消したメンバを狙っている注入ハンドラを丸ごと削る。
	 *
	 * <p>その注入が担っていた機能は動かなくなるので、必ず警告を出す。
	 * 本来は Paper 側の等価な位置に張り替えるべきで、これは暫定措置。
	 */
	record DropInjector(String method, String reason) implements Rule {
		@Override
		public void apply(ClassNode node) {
			if (node.methods.removeIf(m -> m.name.equals(method))) {
				Log.warn(LogCategory.GAME_PROVIDER,
						"[shifu] disabled %s#%s: %s", node.name, method, reason);
			}
		}
	}

	/**
	 * 注入ハンドラの引数の「型」を差し替える。
	 *
	 * <p>CraftBukkit がターゲットを void から戻り値ありに変えると、
	 * ハンドラの {@code CallbackInfo} を {@code CallbackInfoReturnable} にしないと通らない。
	 *
	 * <p>{@link InsertInjectorParams} と同じく、元のハンドラを退避してブリッジを生成する。
	 * 元の型が参照型なら CHECKCAST を挟むので、広い型に戻す方向でも安全。
	 *
	 * <p>**機能は失われない**。
	 */
	record RetypeInjectorParam(String method, int index, String newDesc) implements Rule {
		@Override
		public void apply(ClassNode node) {
			MethodNode original = null;

			for (MethodNode m : node.methods) {
				if (m.name.equals(method)) {
					original = m;
					break;
				}
			}

			if (original == null) {
				Log.error(LogCategory.GAME_PROVIDER, "[shifu] no such method %s#%s", node.name, method);
				return;
			}

			Type[] oldArgs = Type.getArgumentTypes(original.desc);
			Type returnType = Type.getReturnType(original.desc);

			Type[] newArgs = oldArgs.clone();
			newArgs[index] = Type.getType(newDesc);

			boolean isStatic = (original.access & Opcodes.ACC_STATIC) != 0;
			String targetName = method + "$shifuTarget";

			MethodNode bridge = new MethodNode(original.access, method,
					Type.getMethodDescriptor(returnType, newArgs), null,
					original.exceptions == null ? null : original.exceptions.toArray(new String[0]));

			bridge.visibleAnnotations = original.visibleAnnotations;
			bridge.invisibleAnnotations = original.invisibleAnnotations;
			bridge.visibleParameterAnnotations = original.visibleParameterAnnotations;
			bridge.invisibleParameterAnnotations = original.invisibleParameterAnnotations;
			bridge.visibleAnnotableParameterCount = original.visibleAnnotableParameterCount;
			bridge.invisibleAnnotableParameterCount = original.invisibleAnnotableParameterCount;
			original.visibleAnnotations = null;
			original.invisibleAnnotations = null;
			original.visibleParameterAnnotations = null;
			original.invisibleParameterAnnotations = null;
			original.visibleAnnotableParameterCount = 0;
			original.invisibleAnnotableParameterCount = 0;
			original.name = targetName;

			InsnList body = bridge.instructions;
			int slot = isStatic ? 0 : 1;
			int stack = isStatic ? 0 : 1;

			if (!isStatic) body.add(new VarInsnNode(Opcodes.ALOAD, 0));

			for (int i = 0; i < newArgs.length; i++) {
				body.add(new VarInsnNode(newArgs[i].getOpcode(Opcodes.ILOAD), slot));

				if (i == index && oldArgs[i].getSort() == Type.OBJECT) {
					body.add(new TypeInsnNode(Opcodes.CHECKCAST, oldArgs[i].getInternalName()));
				}

				slot += newArgs[i].getSize();
				stack += newArgs[i].getSize();
			}

			body.add(new MethodInsnNode(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
					node.name, targetName, original.desc, false));
			body.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));

			bridge.maxStack = Math.max(stack, returnType.getSize());
			bridge.maxLocals = slot;

			node.methods.add(bridge);

			Log.warn(LogCategory.GAME_PROVIDER,
					"[shifu] retyped parameter %d of %s#%s to %s", index, node.name, method, newDesc);
		}
	}

	/**
	 * Paper が引数を増やした static メソッドに対して、vanilla 形状のオーバーロードを復元する。
	 *
	 * <p>増えた引数は「既に渡っている引数のフィールド」から補う。CraftBukkit が足す引数は
	 * だいたい対象オブジェクト自身が持つ設定値(cook speed 倍率など)なので、この形で埋まる。
	 *
	 * <p>{@link DropInjector} と違い、**機能は失われない**。
	 */
	record RestoreOverload(String method, String shortDesc, String fullDesc,
			List<FieldArg> extras) implements Rule {
		@Override
		public void apply(ClassNode node) {
			for (MethodNode m : node.methods) {
				if (m.name.equals(method) && m.desc.equals(shortDesc)) return; // 既にある
			}

			Type[] args = Type.getArgumentTypes(shortDesc);
			Type returnType = Type.getReturnType(shortDesc);

			MethodNode overload = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
					method, shortDesc, null, null);
			InsnList body = overload.instructions;

			int slot = 0;
			int stack = 0;
			int[] slots = new int[args.length];

			for (int i = 0; i < args.length; i++) {
				slots[i] = slot;
				body.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), slot));
				slot += args[i].getSize();
				stack += args[i].getSize();
			}

			for (FieldArg extra : extras) {
				body.add(new VarInsnNode(Opcodes.ALOAD, slots[extra.argIndex()]));
				body.add(new FieldInsnNode(Opcodes.GETFIELD, extra.owner(), extra.name(), extra.desc()));
				stack += Type.getType(extra.desc()).getSize();
			}

			body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, method, fullDesc, false));
			body.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));

			overload.maxStack = stack + 1;
			overload.maxLocals = slot;

			node.methods.add(overload);

			Log.warn(LogCategory.GAME_PROVIDER,
					"[shifu] restored vanilla-shaped %s#%s%s", node.name, method, shortDesc);
		}
	}

	/** {@link RestoreOverload} が補う引数の出どころ(第 argIndex 引数のフィールド)。 */
	record FieldArg(int argIndex, String owner, String name, String desc) {
	}

	/**
	 * 注入ハンドラの {@code method} セレクタを書き換える。
	 *
	 * <p>Paper は API 型を返す共変ブリッジメソッドを足すことがあり、
	 * MOD 側が正規表現セレクタを使っていると、そのブリッジまで拾ってしまう。
	 * 例: {@code /^with/ desc=/CommandSourceStack;$/} は
	 * {@code net.minecraft.commands.CommandSourceStack} を返すメソッドだけを狙っているが、
	 * Paper が足した {@code io.papermc.paper.command.brigadier.CommandSourceStack} 版にも当たる。
	 *
	 * <p>セレクタを絞るだけなので **機能は失われない**。
	 */
	record RewriteInjectorSelector(String method, String from, String to) implements Rule {
		@Override
		public void apply(ClassNode node) {
			for (MethodNode m : node.methods) {
				if (!m.name.equals(method)) continue;

				boolean rewritten = rewrite(m.visibleAnnotations) | rewrite(m.invisibleAnnotations);

				if (rewritten) {
					Log.warn(LogCategory.GAME_PROVIDER,
							"[shifu] narrowed selector on %s#%s", node.name, method);
				} else {
					Log.error(LogCategory.GAME_PROVIDER,
							"[shifu] selector %s not found on %s#%s", from, node.name, method);
				}

				return;
			}
		}

		private boolean rewrite(List<AnnotationNode> annotations) {
			if (annotations == null) return false;

			boolean rewritten = false;

			for (AnnotationNode annotation : annotations) {
				if (annotation.values == null) continue;

				for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
					if (!"method".equals(annotation.values.get(i))) continue;
					if (!(annotation.values.get(i + 1) instanceof List<?> selectors)) continue;

					List<Object> updated = new ArrayList<>(selectors.size());

					for (Object selector : selectors) {
						if (from.equals(selector)) {
							updated.add(to);
							rewritten = true;
						} else {
							updated.add(selector);
						}
					}

					annotation.values.set(i + 1, updated);
				}
			}

			return rewritten;
		}
	}

	/**
	 * メソッドの先頭に、引数をそのまま渡す static 呼び出しを差し込む。
	 *
	 * <p>トレース用。フックの記述子は対象の引数から機械的に決める
	 * (参照型は {@code Object}、プリミティブはそのまま)ので、
	 * NMS の型に依存せずに書ける。
	 *
	 * <p>先頭に副作用のない void 呼び出しを1つ足すだけなので、
	 * 既存のスタックマップフレームには影響しない。
	 */
	record InsertTraceAtHead(String method, String methodDesc, String hookOwner, String hookName) implements Rule {
		@Override
		public void apply(ClassNode node) {
			for (MethodNode m : node.methods) {
				if (!m.name.equals(method) || !m.desc.equals(methodDesc)) continue;

				Type[] args = Type.getArgumentTypes(methodDesc);
				boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;

				InsnList head = new InsnList();
				Type[] hookArgs = new Type[args.length];
				int slot = isStatic ? 0 : 1;
				int stack = 0;

				for (int i = 0; i < args.length; i++) {
					hookArgs[i] = args[i].getSort() == Type.OBJECT || args[i].getSort() == Type.ARRAY
							? Type.getObjectType("java/lang/Object")
							: args[i];

					head.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), slot));
					slot += args[i].getSize();
					stack += args[i].getSize();
				}

				head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName,
						Type.getMethodDescriptor(Type.VOID_TYPE, hookArgs), false));

				m.instructions.insert(head);
				m.maxStack = Math.max(m.maxStack, stack);

				Log.info(LogCategory.GAME_PROVIDER, "[shifu] trace: %s#%s", node.name, method);
				return;
			}

			Log.error(LogCategory.GAME_PROVIDER, "[shifu] trace target not found: %s#%s%s",
					node.name, method, methodDesc);
		}
	}

	/** static 呼び出しの参照。 */
	record MethodRef(String owner, String name, String desc) {
	}

	/**
	 * Paper が形を変えた static 呼び出しを、vanilla 形状の呼び出し列に差し替える。
	 *
	 * <p>MOD 側の Mixin を書き換えるより、**Paper 側の呼び出しを vanilla の形に戻す**方が本筋。
	 * そうすれば MOD は無改造のまま当たる。スタックの辻褄は呼び出し側の責任。
	 */
	record ReplaceStaticCall(String method, String methodDesc, MethodRef from,
			List<MethodRef> with) implements Rule {
		@Override
		public void apply(ClassNode node) {
			for (MethodNode m : node.methods) {
				if (!m.name.equals(method) || !m.desc.equals(methodDesc)) continue;

				for (AbstractInsnNode insn : m.instructions.toArray()) {
					if (insn instanceof MethodInsnNode call
							&& call.owner.equals(from.owner())
							&& call.name.equals(from.name())
							&& call.desc.equals(from.desc())) {
						AbstractInsnNode at = call;

						for (MethodRef ref : with) {
							MethodInsnNode replacement =
									new MethodInsnNode(Opcodes.INVOKESTATIC, ref.owner(), ref.name(), ref.desc(), false);
							m.instructions.insert(at, replacement);
							at = replacement;
						}

						m.instructions.remove(call);

						Log.warn(LogCategory.GAME_PROVIDER,
								"[shifu] rewrote %s.%s call in %s#%s to vanilla shape",
								from.owner(), from.name(), node.name, method);
						return;
					}
				}

				Log.error(LogCategory.GAME_PROVIDER,
						"[shifu] could not find %s.%s%s in %s#%s",
						from.owner(), from.name(), from.desc(), node.name, method);
				return;
			}
		}
	}

	/** ある static 呼び出しの直前に、引数を1つ加工する static 呼び出しを挟む。 */
	record InsertStaticCallBefore(String method, String methodDesc, MethodRef before,
			MethodRef insert) implements Rule {
		@Override
		public void apply(ClassNode node) {
			for (MethodNode m : node.methods) {
				if (!m.name.equals(method) || !m.desc.equals(methodDesc)) continue;

				for (AbstractInsnNode insn : m.instructions.toArray()) {
					if (insn instanceof MethodInsnNode call
							&& call.owner.equals(before.owner())
							&& call.name.equals(before.name())
							&& call.desc.equals(before.desc())) {
						m.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
								insert.owner(), insert.name(), insert.desc(), false));

						Log.warn(LogCategory.GAME_PROVIDER,
								"[shifu] inserted %s.%s before %s.%s in %s#%s",
								insert.owner(), insert.name(), before.owner(), before.name(), node.name, method);
						return;
					}
				}

				Log.error(LogCategory.GAME_PROVIDER,
						"[shifu] could not find %s.%s%s in %s#%s",
						before.owner(), before.name(), before.desc(), node.name, method);
				return;
			}
		}
	}

	/**
	 * 指定メソッド内の、ある呼び出しの直後に静的フック呼び出しを差し込む。
	 *
	 * <p>スタックを消費しない void 呼び出しを1つ足すだけなので、
	 * 既存のスタックマップフレームに手を入れずに済む。
	 */
	/**
	 * 標準の Fabric と同じフックを、同じ位置に差し込む。
	 *
	 * <p>{@code Hooks.startServer(File, Object)} は MOD の初期化そのものだが、
	 * **その呼び出しが {@code Main.main} の中にあること**を当てにしている MOD がある
	 * (owo-lib の {@code MainMixin} はこの呼び出しに @Inject する)。
	 * 自前のフックだけを差し込むと、そういう MOD が「対象が無い」で落ちる。
	 *
	 * <p>引数はどちらも null を渡す。{@code startServer} は runDir が null なら
	 * カレントディレクトリを使う(標準の MinecraftGameProvider と同じ結果)。
	 */
	record InsertFabricStartServer(String method, List<String> methodDescs,
			String afterOwner, String afterName) implements Rule {
		@Override
		public void apply(ClassNode node) {
			for (MethodNode m : node.methods) {
				if (!m.name.equals(method) || !methodDescs.contains(m.desc)) continue;

				for (AbstractInsnNode insn : m.instructions.toArray()) {
					if (insn instanceof MethodInsnNode call
							&& call.owner.equals(afterOwner)
							&& call.name.equals(afterName)) {
						InsnList hook = new InsnList();
						hook.add(new InsnNode(Opcodes.ACONST_NULL));
						hook.add(new InsnNode(Opcodes.ACONST_NULL));
						hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
								"net/fabricmc/loader/impl/game/minecraft/Hooks", "startServer",
								"(Ljava/io/File;Ljava/lang/Object;)V", false));
						m.instructions.insert(call, hook);
						m.maxStack = Math.max(m.maxStack, 2);
						Log.info(LogCategory.GAME_PROVIDER,
								"[shifu] hooked %s#%s after %s.%s (Fabric の startServer)", node.name, method, afterOwner, afterName);
						return;
					}
				}

				Log.error(LogCategory.GAME_PROVIDER,
						"[shifu] could not find %s.%s in %s#%s - mods will not be initialized",
						afterOwner, afterName, node.name, method);
				return;
			}

			Log.error(LogCategory.GAME_PROVIDER,
					"[shifu] could not find %s#%s%s - mods will not be initialized", node.name, method, methodDescs);
		}
	}

	record InsertHookAfterCall(String method, String methodDesc,
			String afterOwner, String afterName,
			String hookOwner, String hookName) implements Rule {
		@Override
		public void apply(ClassNode node) {
			for (MethodNode m : node.methods) {
				if (!m.name.equals(method) || !m.desc.equals(methodDesc)) continue;

				for (AbstractInsnNode insn : m.instructions.toArray()) {
					if (insn instanceof MethodInsnNode call
							&& call.owner.equals(afterOwner)
							&& call.name.equals(afterName)) {
						m.instructions.insert(call,
								new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, "()V", false));
						Log.info(LogCategory.GAME_PROVIDER,
								"[shifu] hooked %s#%s after %s.%s", node.name, method, afterOwner, afterName);
						return;
					}
				}

				Log.error(LogCategory.GAME_PROVIDER,
						"[shifu] could not find %s.%s in %s#%s - mods will not be initialized",
						afterOwner, afterName, node.name, method);
				return;
			}

			Log.error(LogCategory.GAME_PROVIDER,
					"[shifu] could not find %s#%s%s - mods will not be initialized", node.name, method, methodDesc);
		}
	}

	/**
	 * CraftBukkit がターゲットのメソッド/コンストラクタに引数を追加したケースを吸収する。
	 *
	 * <p>Mixin はハンドラのディスクリプタがターゲットと一致することを要求するので、
	 * 引数が増えた分だけハンドラも増やさないと {@code Invalid descriptor} で落ちる。
	 *
	 * <p>元のハンドラをリネームして退避し、増えた引数を受け取って捨てるだけの
	 * ブリッジを新たに生成して注釈を移す。ブリッジは分岐を持たないのでスタックマップフレームが要らず、
	 * 既存メソッドのフレームにも触らずに済む。
	 *
	 * <p>{@link DropInjector} と違い、**機能は失われない**。
	 *
	 * @param index 追加された引数が入る位置(元のハンドラの引数リスト基準)
	 */
	record InsertInjectorParams(String method, int index, List<String> inserted) implements Rule {
		@Override
		public void apply(ClassNode node) {
			MethodNode original = null;

			for (MethodNode m : node.methods) {
				if (m.name.equals(method)) {
					original = m;
					break;
				}
			}

			if (original == null) {
				Log.error(LogCategory.GAME_PROVIDER, "[shifu] no such method %s#%s", node.name, method);
				return;
			}

			Type[] oldArgs = Type.getArgumentTypes(original.desc);
			Type returnType = Type.getReturnType(original.desc);

			Type[] newArgs = new Type[oldArgs.length + inserted.size()];
			System.arraycopy(oldArgs, 0, newArgs, 0, index);

			for (int i = 0; i < inserted.size(); i++) {
				newArgs[index + i] = Type.getType(inserted.get(i));
			}

			System.arraycopy(oldArgs, index, newArgs, index + inserted.size(), oldArgs.length - index);

			boolean isStatic = (original.access & Opcodes.ACC_STATIC) != 0;
			String targetName = method + "$shifuTarget";

			MethodNode bridge = new MethodNode(original.access, method,
					Type.getMethodDescriptor(returnType, newArgs), null,
					original.exceptions == null ? null : original.exceptions.toArray(new String[0]));

			bridge.visibleAnnotations = original.visibleAnnotations;
			bridge.invisibleAnnotations = original.invisibleAnnotations;
			original.visibleAnnotations = null;
			original.invisibleAnnotations = null;

			// MixinExtras の @Local などはパラメータ注釈なので、位置をずらして移さないと
			// Mixin がキャプチャ引数を「余分な引数」と誤認する。
			bridge.visibleParameterAnnotations =
					shiftParameterAnnotations(original.visibleParameterAnnotations, newArgs.length);
			bridge.invisibleParameterAnnotations =
					shiftParameterAnnotations(original.invisibleParameterAnnotations, newArgs.length);
			bridge.visibleAnnotableParameterCount =
					bridge.visibleParameterAnnotations == null ? 0 : bridge.visibleParameterAnnotations.length;
			bridge.invisibleAnnotableParameterCount =
					bridge.invisibleParameterAnnotations == null ? 0 : bridge.invisibleParameterAnnotations.length;
			original.visibleParameterAnnotations = null;
			original.invisibleParameterAnnotations = null;
			original.visibleAnnotableParameterCount = 0;
			original.invisibleAnnotableParameterCount = 0;

			original.name = targetName;

			InsnList body = bridge.instructions;
			int slot = isStatic ? 0 : 1;
			int stack = isStatic ? 0 : 1;

			if (!isStatic) body.add(new VarInsnNode(Opcodes.ALOAD, 0));

			for (int i = 0; i < newArgs.length; i++) {
				boolean isInserted = i >= index && i < index + inserted.size();

				if (!isInserted) {
					body.add(new VarInsnNode(newArgs[i].getOpcode(Opcodes.ILOAD), slot));
					stack += newArgs[i].getSize();
				}

				slot += newArgs[i].getSize();
			}

			body.add(new MethodInsnNode(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
					node.name, targetName, original.desc, false));
			body.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));

			bridge.maxStack = Math.max(stack, returnType.getSize());
			bridge.maxLocals = slot;

			node.methods.add(bridge);

			Log.warn(LogCategory.GAME_PROVIDER,
					"[shifu] bridged %s#%s for %d parameter(s) added by CraftBukkit at index %d",
					node.name, method, inserted.size(), index);
		}

		private List<AnnotationNode>[] shiftParameterAnnotations(List<AnnotationNode>[] source, int newLength) {
			if (source == null) return null;

			@SuppressWarnings("unchecked")
			List<AnnotationNode>[] shifted = new List[newLength];

			for (int i = 0; i < source.length; i++) {
				shifted[i < index ? i : i + inserted.size()] = source[i];
			}

			return shifted;
		}
	}

	/**
	 * メソッドの中身を「指定した引数をそのまま返す」だけに置き換える。
	 *
	 * <p>Paper が vanilla の値を受け取っておきながら捨てて独自値を返す、という形の
	 * 挙動変更を元に戻すのに使う。分岐を持たないのでスタックマップフレームが要らない。
	 */
	record ReturnArgument(String method, String desc, int argIndex, String reason) implements Rule {
		@Override
		public void apply(ClassNode node) {
			for (MethodNode m : node.methods) {
				if (!m.name.equals(method) || !m.desc.equals(desc)) continue;

				Type[] args = Type.getArgumentTypes(desc);
				boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
				int slot = isStatic ? 0 : 1;

				for (int i = 0; i < argIndex; i++) {
					slot += args[i].getSize();
				}

				m.instructions.clear();
				m.tryCatchBlocks.clear();
				m.localVariables = null;
				m.instructions.add(new VarInsnNode(args[argIndex].getOpcode(Opcodes.ILOAD), slot));
				m.instructions.add(new InsnNode(args[argIndex].getOpcode(Opcodes.IRETURN)));
				m.maxStack = args[argIndex].getSize();

				Log.warn(LogCategory.GAME_PROVIDER,
						"[shifu] vanilla parity: %s#%s now returns argument %d - %s",
						node.name, method, argIndex, reason);
				return;
			}

			Log.error(LogCategory.GAME_PROVIDER, "[shifu] no such method %s#%s%s", node.name, method, desc);
		}
	}

	/**
	 * Paper が消したフィールドを、{@code @Shadow} が解決できるように足し戻す。
	 *
	 * <p>値を維持する仕組みまでは足さないので、そのフィールドを読む注入は
	 * {@link DropInjector} で併せて落とすこと。落とさないと誤った値で動く。
	 */
	record AddField(String name, String desc) implements Rule {
		@Override
		public void apply(ClassNode node) {
			for (FieldNode f : node.fields) {
				if (f.name.equals(name)) return;
			}

			node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, name, desc, null, null));

			Log.warn(LogCategory.GAME_PROVIDER,
					"[shifu] added placeholder field %s#%s %s", node.name, name, desc);
		}
	}

	/**
	 * {@code @Shadow} フィールドの型が Paper 側で狭められた場合にディスクリプタを合わせる。
	 *
	 * <p>狭めた型は元の型のサブタイプなので、参照側のバイトコードはそのままで検証を通る。
	 */
	record RetypeShadowField(String field, String from, String to) implements Rule {
		@Override
		public void apply(ClassNode node) {
			boolean patched = false;

			for (FieldNode f : node.fields) {
				if (f.name.equals(field) && f.desc.equals(from)) {
					f.desc = to;
					f.signature = null;
					patched = true;
				}
			}

			if (!patched) return;

			for (MethodNode m : node.methods) {
				for (AbstractInsnNode insn : m.instructions) {
					if (insn instanceof FieldInsnNode fi
							&& fi.owner.equals(node.name)
							&& fi.name.equals(field)
							&& fi.desc.equals(from)) {
						fi.desc = to;
					}
				}
			}

			Log.warn(LogCategory.GAME_PROVIDER,
					"[shifu] retyped %s#%s from %s to %s", node.name, field, from, to);
		}
	}
}
