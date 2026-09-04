// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.bootstrap;

import java.nio.file.Paths;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.impl.FabricLoaderImpl;

/**
 * Paper のバイトコードから呼び返されるフック。
 *
 * <p>Knot にロードされた {@code net.minecraft.server.Main} から参照されるが、
 * shifu-bootstrap.jar は起動クラスパス上にあり validParentClassPath に含まれるので、
 * app クラスローダ側の同一クラスに解決される。
 */
public final class ShifuHooks {
	private static boolean modsInitialized;

	private ShifuHooks() {
	}

	/**
	 * {@code Bootstrap.validate()} の直後に呼ばれる。
	 *
	 * <p>Fabric の MOD 初期化はレジストリのブートストラップ後でなければ成立しない
	 * (先に呼ぶと {@code BuiltInRegistries} の初期化が "Not bootstrapped" で落ちる)。
	 * 標準の MinecraftGameProvider が EntrypointPatch でこの位置にフックを注入するのと同じ意図。
	 */
	public static void onGameBootstrapped() {
		if (modsInitialized) return;

		modsInitialized = true;

		FabricLoaderImpl.INSTANCE.prepareModInit(Paths.get("."), null);
		FabricLoaderImpl.INSTANCE.invokeEntrypoints("main", ModInitializer.class, ModInitializer::onInitialize);
		FabricLoaderImpl.INSTANCE.invokeEntrypoints("server", DedicatedServerModInitializer.class,
				DedicatedServerModInitializer::onInitializeServer);
	}

	private static Runnable paperRegistryHook;

	/**
	 * Paper が {@code BuiltInRegistries.bootStrap(Runnable)} に渡していた Runnable を預かる。
	 *
	 * <p>Paper は vanilla の {@code bootStrap()} ではなく Runnable 版を直接呼ぶため、
	 * Fabric の registry-sync が張っている {@code bootStrap()} への @Redirect が当たらない。
	 * 呼び出しを vanilla 形状に戻した上で、Paper の Runnable はここで預かって
	 * 実際の凍結時に合流させる。
	 */
	public static void stashRegistryHook(Runnable hook) {
		paperRegistryHook = hook;
	}

	/** 実際に {@code BuiltInRegistries.bootStrap(Runnable)} が呼ばれる直前に、預かった Runnable を合流させる。 */
	public static Runnable withStashedRegistryHook(Runnable vanilla) {
		Runnable paper = paperRegistryHook;

		if (paper == null) return vanilla;

		paperRegistryHook = null;

		return () -> {
			paper.run();
			vanilla.run();
		};
	}

	static boolean modsInitialized() {
		return modsInitialized;
	}
}
