// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;

import org.bukkit.craftbukkit.CraftEquipmentSlot;
import org.bukkit.craftbukkit.damage.CraftDamageSource;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.ArrowBodyCountChangeEvent;
import org.bukkit.event.entity.BatToggleSleepEvent;
import org.bukkit.event.entity.EntityAirChangeEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerExpCooldownChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;

/**
 * エンティティまわり(entity-core: Entity、LivingEntity、Mob、飛び道具、乗り物など)のイベント発火。
 *
 * <p>作りは {@link ShifuEvents} と同じ。登録が無ければ何も作らず「vanilla を続けてよい」を返す。
 * 返り値の向きは「vanilla の処理を続けてよいか」。差し込む位置は
 * {@code patches/events/entity-core.rules}。
 *
 * <p>Paper が呼び出し側の署名を変えて運んでいる理由(Cause / Reason / 手)は、
 * 呼ぶ直前に置く方式(stash)で受ける。置いていない経路は UNKNOWN など既定の値になる。
 */
public final class EntityEvents {
    private EntityEvents() {
    }

    private static boolean listening(final HandlerList handlers) {
        return ShifuEvents.listening(handlers);
    }

    /** PlayerExpChangeEvent。Paper と同じく、残りが正のときだけ出す。 */
    public static int expChange(final Player player, final int amount) {
        if (amount <= 0 || !listening(org.bukkit.event.player.PlayerExpChangeEvent.getHandlerList())) {
            return amount;
        }

        return CraftEventFactory.callPlayerExpChangeEvent(player, amount).getAmount();
    }

    // ------------------------------------------------------------ 自然湧き


    /**
     * PlayerNaturallySpawnCreaturesEvent。1 tick に 1 度、湧きを始める前に
     * プレイヤーごとに出す。イベントは各 ServerPlayer に控えて、chunk を選ぶときに読む。
     *
     * <p>半径の既定は Paper と同じ min(spigot の mob-spawn-range, 視距離, 8) チャンク。
     * 8 チャンクは vanilla の判定(16384 = 128 の 2 乗)と同じなので、
     * プラグインが縮めない限り vanilla と同じ範囲になる。
     *
     * 読んだ位置: patches/server/Optimize-Spigot-s-mob-spawning.patch と
     *            Paper-Server src/main/java/net/minecraft/server/level/ServerChunkCache.java
     */
    public static void naturallySpawnCreatures(final ServerLevel level) {
        if (!listening(com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent.getHandlerList())) {
            return;
        }

        for (final ServerPlayer player : level.players()) {
            int chunkRange = Math.min(level.spigotConfig.mobSpawnRange, player.getBukkitEntity().getViewDistance());
            chunkRange = Math.min(chunkRange, 8);
            player.shifuNaturallySpawnedEvent =
                    new com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent(
                            player.getBukkitEntity(), (byte) chunkRange);
            player.shifuNaturallySpawnedEvent.callEvent();
        }
    }

    /** 発火に登録があるか。ChunkMap が vanilla の判定と切り替えるのに使う。 */
    public static boolean naturallySpawnCreaturesListening() {
        return listening(com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent.getHandlerList());
    }

    /**
     * そのプレイヤーの周りで湧かせてよい範囲(ブロックの 2 乗)。
     * 取り消されていれば 0。控えたイベントが無ければ vanilla と同じ 16384。
     */
    public static double spawnRangeSquared(final ServerPlayer player) {
        final com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent event =
                player.shifuNaturallySpawnedEvent;

        if (event == null) {
            return 16384.0;
        }

        if (event.isCancelled()) {
            return 0.0;
        }

        final int blocks = event.getSpawnRadius() << 4;

        return (double) blocks * blocks;
    }

    // ------------------------------------------------------------ 燃焼

    /**
     * 着火の秒数がイベントで変えられていたら自分で点火して false、同じなら vanilla の
     * {@code igniteForSeconds} へ進んでよいので true。
     *
     * <p>{@code Entity.igniteForSeconds} そのものは囲まない。Paper は中で総称の
     * EntityCombustEvent を出すが、そうすると個別の発火(溶岩・矢・火の玉・雷・ゾンビの炎)と
     * 同じ着火で 2 回出る。個別の発火はそれぞれの呼び出し側で囲む形にしてある。
     *
     * <p>未対応: 個別の発火が無い経路(火のブロック、焚き火など)の EntityCombustEvent。
     */
    /** イベントの秒数が vanilla と違えば自分で点火して false。同じなら旗を立てて vanilla に任せる。 */
    /**
     * EntityCombustByEntityEvent(矢、小さな火の玉)。{@code igniteForSeconds(5)} を囲む。
     *
     * @return vanilla の行へ進んでよいか
     */
    public static boolean combustByEntity(final Entity combuster, final Entity entity, final int seconds) {
        if (!listening(EntityCombustEvent.getHandlerList())) {
            return true;
        }

        // 1.20.6 の構築子は秒数を int で取る(26.2 は float)
        final org.bukkit.event.entity.EntityCombustByEntityEvent event =
                new org.bukkit.event.entity.EntityCombustByEntityEvent(
                        combuster.getBukkitEntity(), entity.getBukkitEntity(), seconds);

        if (!event.callEvent()) {
            return false;
        }

        return ignite(entity, event.getDuration(), seconds);
    }

    private static boolean ignite(final Entity entity, final float duration, final float vanilla) {
        if (duration != vanilla) {
            entity.igniteForTicks(Mth.floor(duration * 20.0F));

            return false;
        }

        return true;
    }

    /**
     * EntityCombustByBlockEvent(溶岩)。{@code Entity.lavaIgnite} の {@code igniteForSeconds(15.0F)} を囲む。
     * Paper と同じく、生き物で、まだ燃えていないときだけ出す。それ以外は EntityCombustEvent も出さない。
     *
     * <p>読んだ位置: paper-server patches/sources/net/minecraft/world/entity/Entity.java.patch(lavaIgnite)
     */
    public static void lavaContact(final Entity entity, final net.minecraft.world.level.material.FluidState fluidState,
                                   final net.minecraft.core.BlockPos pos) {
        if (!listening(EntityCombustEvent.getHandlerList()) || !fluidState.is(net.minecraft.tags.FluidTags.LAVA)) {
            return;
        }

        entity.shifuLastLavaContact = pos.immutable();
    }





    // ------------------------------------------------------------ 押し

    private static Entity pusher;
    private static Entity pushee;

    /**
     * 次の {@code push(xa, ya, za)} の主を置く。Paper は push に主の引数を足して呼び出し側
     * (剣の薙ぎ払い、矢のノックバック、ドラゴンの体当たりなど)から渡している。
     */
    public static void pushedBy(final Entity pushingEntity, final Entity pushed) {
        if (!listening(io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent.getHandlerList())) {
            return;
        }

        pusher = pushingEntity;
        pushee = pushed;
    }


    // ------------------------------------------------------------ 落とし物


    // ------------------------------------------------------------ リード




    private static InteractionHand shearHand;
    private static boolean unleashHandled;

    /** ハサミで全部外すとき、{@code dropAllLeashConnections} に渡す手を置く。 */
    public static void shearHand(final InteractionHand hand) {
        if (listening(EntityUnleashEvent.getHandlerList())) {
            shearHand = hand;
        }
    }



    /** 直前の {@link #unleashSelf} / {@link #unleashEach} で、落とさずに外した個体があったか。 */
    public static boolean takeUnleashHandled() {
        final boolean handled = unleashHandled;
        unleashHandled = false;

        return handled;
    }

    /**
     * PlayerUnleashEntityEvent(持ち主が手で外す)。1.20.6 は Mob.interact が外す。
     *
     * @return 縄を落とすか。取り消されたら null(呼ぶ側は繋いだままにして packet を送り直す)
     */
    public static Boolean unleashByPlayer(final Mob mob, final net.minecraft.world.entity.player.Player player,
                                          final InteractionHand hand, final boolean dropLeash) {
        if (!listening(org.bukkit.event.player.PlayerUnleashEntityEvent.getHandlerList())) {
            return dropLeash;
        }

        final org.bukkit.event.player.PlayerUnleashEntityEvent event =
                CraftEventFactory.callPlayerUnleashEntityEvent(mob, player, hand, dropLeash);

        return event.isCancelled() ? null : event.isDropLeash();
    }




    // ------------------------------------------------------------ 空気・削除

    private static boolean settingAir;


    private static EntityRemoveEvent.Cause removeCause;

    /** 次の {@code setRemoved} の理由を置く。アダプタ層(hand の {@code discard(cause)} など)から。 */
    public static void removeCause(final EntityRemoveEvent.Cause cause) {
        removeCause = cause;
    }


    // ------------------------------------------------------------ 経験値オーブ

    private static boolean targetCancelled;

    public static boolean targetListening() {
        return listening(EntityTargetEvent.getHandlerList());
    }

    /**
     * EntityTargetLivingEntityEvent(オーブが追うプレイヤーが変わった)。
     * {@code ExperienceOrb.followNearbyPlayer} で、vanilla が決めたあとに出す。
     *
     * @return 追う相手。取り消されたら前の相手({@link #takeTargetCancelled} が true になる)
     */
    public static Player orbTarget(final ExperienceOrb orb, final Player previous, final Player now) {
        targetCancelled = false;

        final EntityTargetLivingEntityEvent event = CraftEventFactory.callEntityTargetLivingEvent(orb, now,
                now != null ? EntityTargetEvent.TargetReason.CLOSEST_PLAYER : EntityTargetEvent.TargetReason.FORGOT_TARGET);

        if (event.isCancelled()) {
            targetCancelled = true;

            return previous;
        }

        final LivingEntity target = event.getTarget() == null ? null : ((CraftLivingEntity) event.getTarget()).getHandle();

        return target instanceof Player player ? player : null;
    }

    public static boolean takeTargetCancelled() {
        final boolean cancelled = targetCancelled;
        targetCancelled = false;

        return cancelled;
    }


    /** PlayerExpCooldownChangeEvent。vanilla が入れた直後に、イベントの値で入れ直す。 */
    public static int xpCooldown(final Player player, final int newCooldown, final PlayerExpCooldownChangeEvent.ChangeReason reason) {
        if (!listening(PlayerExpCooldownChangeEvent.getHandlerList())) {
            return newCooldown;
        }

        return CraftEventFactory.callPlayerXpCooldownEvent(player, newCooldown, reason).getNewCooldown();
    }


    // ------------------------------------------------------------ ポーション効果

    private static EntityPotionEffectEvent.Cause effectCause;
    private static boolean effectCauseKeep;

    /** 次の効果の変化の理由を置く(hand の {@code removeEffect(effect, cause)} など)。 */
    public static void effectCause(final EntityPotionEffectEvent.Cause cause) {
        if (!listening(EntityPotionEffectEvent.getHandlerList())) {
            return;
        }

        effectCause = cause;
        effectCauseKeep = false;
    }

    /**
     * 理由を置いて、{@code effectCauseDone} まで消えないようにする。
     * 1 つの文が効果を何度も付けるところ(ポーションの効果の並び、周りのプレイヤー全員)で使う。
     */
    public static void effectCauseUntilDone(final EntityPotionEffectEvent.Cause cause) {
        if (!listening(EntityPotionEffectEvent.getHandlerList())) {
            return;
        }

        effectCause = cause;
        effectCauseKeep = true;
    }

    /** {@code effectCauseUntilDone} で置いた理由を外す。 */
    public static void effectCauseDone() {
        effectCause = null;
        effectCauseKeep = false;
    }

    private static EntityPotionEffectEvent.Cause takeEffectCause() {
        final EntityPotionEffectEvent.Cause cause = effectCause;

        if (!effectCauseKeep) {
            effectCause = null;
        }

        return cause == null ? EntityPotionEffectEvent.Cause.UNKNOWN : cause;
    }

    /**
     * EntityPotionEffectEvent(CLEARED)。{@code removeAllEffects} で消す前に 1 つずつ出す。
     *
     * @return 取り消されて残す効果
     */
    public static List<MobEffectInstance> clearEffects(final LivingEntity entity, final Collection<MobEffectInstance> effects) {
        final EntityPotionEffectEvent.Cause cause = takeEffectCause();
        final List<MobEffectInstance> kept = new ArrayList<>();

        if (!listening(EntityPotionEffectEvent.getHandlerList())) {
            return kept;
        }

        for (MobEffectInstance effect : effects) {
            if (CraftEventFactory.callEntityPotionEffectChangeEvent(entity, effect, null, cause,
                    EntityPotionEffectEvent.Action.CLEARED).isCancelled()) {
                kept.add(effect);
            }
        }

        return kept;
    }

    /**
     * EntityPotionEffectEvent(ADDED / CHANGED)。{@code addEffect(newEffect, source)} で入れる直前。
     *
     * <p>効かないもの: {@code setOverride}(上書きするかは vanilla の {@code update} が決める)。
     */
    public static boolean addEffect(final LivingEntity entity, final MobEffectInstance old,
                                    final MobEffectInstance added, final Entity source) {
        final EntityPotionEffectEvent.Cause cause = takeEffectCause();

        if (!listening(EntityPotionEffectEvent.getHandlerList())) {
            return true;
        }

        final boolean override = old != null && new MobEffectInstance(old).update(added);

        return !CraftEventFactory.callEntityPotionEffectChangeEvent(entity, old, added, cause, null, override).isCancelled();
    }

    /** EntityPotionEffectEvent(REMOVED)。{@code removeEffectNoUpdate} で消す直前。 */
    public static boolean removeEffect(final LivingEntity entity, final MobEffectInstance instance) {
        final EntityPotionEffectEvent.Cause cause = takeEffectCause();

        if (instance == null || !listening(EntityPotionEffectEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.callEntityPotionEffectChangeEvent(entity, instance, null, cause).isCancelled();
    }

    private static boolean settingArrows;

    /**
     * ArrowBodyCountChangeEvent。{@code setArrowCount} の代入を囲む。
     *
     * <p>効かないもの: {@code isReset}(Paper の overload。常に false)。
     */
    public static boolean arrowCount(final LivingEntity entity, final int count) {
        if (settingArrows || !listening(ArrowBodyCountChangeEvent.getHandlerList())) {
            return true;
        }

        final ArrowBodyCountChangeEvent event = CraftEventFactory.callArrowBodyCountChangeEvent(entity, entity.getArrowCount(), count, false);

        if (event.isCancelled()) {
            return false;
        }

        if (event.getNewAmount() == count) {
            return true;
        }

        settingArrows = true;
        entity.setArrowCount(event.getNewAmount());
        settingArrows = false;

        return false;
    }

    // ------------------------------------------------------------ Mob

    private static EntityTargetEvent.TargetReason targetReason;
    private static boolean targetReasonKeep;
    private static boolean settingTarget;

    /** 次の {@code setTarget} の理由を置く(hand の {@code setTarget(target, reason)})。 */
    public static void targetReason(final EntityTargetEvent.TargetReason reason) {
        if (!listening(EntityTargetEvent.getHandlerList())) {
            return;
        }

        targetReason = reason;
        targetReasonKeep = false;
    }

    /**
     * 理由を置いて、{@code targetReasonDone} まで消えないようにする。
     * 1 つの文が {@code setTarget} を何度も呼ぶところ(ループ、stream の forEach)で使う。
     */
    public static void targetReasonUntilDone(final EntityTargetEvent.TargetReason reason) {
        if (!listening(EntityTargetEvent.getHandlerList())) {
            return;
        }

        targetReason = reason;
        targetReasonKeep = true;
    }

    /** {@code targetReasonUntilDone} で置いた理由を外す。 */
    public static void targetReasonDone() {
        targetReason = null;
        targetReasonKeep = false;
    }

    /**
     * 標的を忘れた理由。1.21.11 の {@code CraftEventFactory} には無いので同じ判定をここに置く
     * (26.2 では {@code getForgotTargetReason})。
     */
    private static EntityTargetEvent.TargetReason forgotTargetReason(final Mob mob, final LivingEntity previous) {
        if (previous != null && !previous.isAlive()) {
            return EntityTargetEvent.TargetReason.TARGET_DIED;
        }

        if (previous != null && !mob.canAttack(previous)) {
            return EntityTargetEvent.TargetReason.TARGET_INVALID;
        }

        return EntityTargetEvent.TargetReason.FORGOT_TARGET;
    }

    /**
     * EntityTargetLivingEntityEvent。{@code Mob.setTarget} の代入を囲む。
     * 同じ相手なら出さない(Paper と同じ)。理由が置かれていなければ、外すときは
     * {@code getForgotTargetReason}、それ以外は UNKNOWN。
     *
     * <p>読んだ位置: paper-server patches/sources/net/minecraft/world/entity/Mob.java.patch(setTarget)
     *
     * @return vanilla の代入へ進んでよいか。相手が差し替えられていたら自分で入れて false
     */
    public static boolean mobTarget(final Mob mob, final LivingEntity target) {
        final EntityTargetEvent.TargetReason stashed = targetReason;

        if (!targetReasonKeep) {
            targetReason = null;
        }

        if (settingTarget || !listening(EntityTargetEvent.getHandlerList())) {
            return true;
        }

        final LivingEntity current = mob.getTarget();

        if (Objects.equals(current, target)) {
            return true;
        }

        EntityTargetEvent.TargetReason reason = stashed;

        if (target == null && (reason == null || reason == EntityTargetEvent.TargetReason.FORGOT_TARGET
                || reason == EntityTargetEvent.TargetReason.UNKNOWN)) {
            reason = forgotTargetReason(mob, current);
        } else if (reason == null) {
            reason = EntityTargetEvent.TargetReason.UNKNOWN;
        }

        final EntityTargetLivingEntityEvent event = CraftEventFactory.callEntityTargetLivingEvent(mob, target, reason);

        if (event.isCancelled()) {
            return false;
        }

        final LivingEntity chosen = event.getTarget() == null ? null : ((CraftLivingEntity) event.getTarget()).getHandle();

        if (chosen == target) {
            return true;
        }

        settingTarget = true;
        mob.setTarget(chosen);
        settingTarget = false;

        return false;
    }

    public static boolean pickupListening() {
        return listening(EntityPickupItemEvent.getHandlerList());
    }

    /**
     * EntityPickupItemEvent。{@code Mob.pickUpItem} で装備する前。Paper は装備できないときも
     * 取り消し済みで出し、プラグインが解除すると無理に持たせる。
     *
     * <p>効かないもの: 取り消しの解除(vanilla が持てないと判定した物は持たない)。
     *
     * <p>読んだ位置: paper-server patches/sources/net/minecraft/world/entity/Mob.java.patch(equipItemIfPossible)
     */
    public static boolean mobPickupItem(final Mob mob, final ItemEntity item, final boolean canPickup) {
        return !CraftEventFactory.callEntityPickupItemEvent(mob, item, 0, !canPickup).isCancelled();
    }

    private static EntityTransformEvent.TransformReason transformReason;

    /** 次の {@code convertTo} の理由を置く。登録が無ければ何もしない。 */
    public static void transformReason(final EntityTransformEvent.TransformReason reason) {
        if (!listening(EntityTransformEvent.getHandlerList())) {
            return;
        }

        transformReason = reason;
    }

    /**
     * EntityTransformEvent。{@code Mob.convertTo} で {@code finalizeConversion} のあと、世界に入れる前。
     * 理由が置かれていなければ UNKNOWN(Paper は vanilla の呼び出し側ごとに DROWNED などを渡す)。
     *
     * <p>読んだ位置: paper-server patches/sources/net/minecraft/world/entity/Mob.java.patch(convertTo)
     */
    public static boolean transform(final Mob original, final Mob converted) {
        final EntityTransformEvent.TransformReason stashed = transformReason;
        transformReason = null;

        if (!listening(EntityTransformEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.callEntityTransformEvent(original, converted,
                stashed == null ? EntityTransformEvent.TransformReason.UNKNOWN : stashed).isCancelled();
    }

    /** BatToggleSleepEvent。{@code Bat} の {@code setResting} を囲む。 */
    public static boolean batToggleSleep(final Bat bat, final boolean awake) {
        if (!listening(BatToggleSleepEvent.getHandlerList())) {
            return true;
        }

        return CraftEventFactory.handleBatToggleSleepEvent(bat, awake);
    }

    // ------------------------------------------------------------ 生き物でないものの被害・爆発

    /**
     * EntityDamageEvent(生き物でないもの)。Paper の {@code handleNonLivingEntityDamageEvent} と同じ向きで、
     * true なら取り消し。登録が無ければ false(取り消さない)。
     */
    public static boolean damageCancelled(final Entity entity, final DamageSource source, final double damage,
                                          final boolean cancelOnZeroDamage, final boolean cancelled) {
        if (!listening(EntityDamageEvent.getHandlerList())) {
            return false;
        }

        return CraftEventFactory.handleNonLivingEntityDamageEvent(entity, source, damage, cancelOnZeroDamage, cancelled);
    }

    /**
     * ExplosionPrimeEvent。登録があれば発火して返す。呼ぶ側は null なら vanilla の explode を、
     * そうでなければ取り消しを見て、イベントの半径と火で explode する。
     */
    public static ExplosionPrimeEvent explosionPrime(final Entity entity, final float radius, final boolean fire) {
        if (!listening(ExplosionPrimeEvent.getHandlerList())) {
            return null;
        }

        return CraftEventFactory.callExplosionPrimeEvent(entity, radius, fire);
    }




    // ------------------------------------------------------------ 防具立て

    private static DamageSource armorStandSource;
    private static ArmorStand armorStandDeathFired;

    /** 次の {@code ArmorStand.kill} が使う被害の元を置く(BYPASSES_INVULNERABILITY の経路)。 */
    public static void armorStandDeathSource(final DamageSource source) {
        armorStandSource = source;
    }





    // ------------------------------------------------------------ 額縁・絵・アイテム




    /** ItemDespawnEvent。{@code ItemEntity.tick} で消える tick に、消す前。 */
    public static boolean itemDespawn(final ItemEntity item) {
        if (!listening(ItemDespawnEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.callItemDespawnEvent(item).isCancelled();
    }

    // ------------------------------------------------------------ プレイヤーの攻撃


    private static Entity attackTarget;
    private static DamageSource attackSource;
    private static float attackDamage;

    /** 次の {@code deflectProjectile} に渡す、攻撃の元と魔法の追加ダメージを置く。 */
    public static void attacking(final Entity target, final DamageSource source, final float damage) {
        if (!listening(EntityDamageEvent.getHandlerList())) {
            return;
        }

        attackTarget = target;
        attackSource = source;
        attackDamage = damage;
    }

    public static boolean deflectListening() {
        return listening(EntityDamageEvent.getHandlerList());
    }

    /**
     * EntityDamageEvent(飛び道具を弾く)。{@code Player.deflectProjectile} で弾く前(Paper と同じ)。
     *
     * @return 弾いてよいか
     */
    public static boolean deflectAllowed(final Player player, final Entity projectile) {
        final Entity target = attackTarget;
        final DamageSource source = attackSource;
        final float damage = attackDamage;
        attackTarget = null;
        attackSource = null;

        if (target != projectile || source == null) {
            return true;
        }

        return !CraftEventFactory.handleNonLivingEntityDamageEvent(projectile, source, damage, false);
    }

    private static EntityExhaustionEvent.ExhaustionReason exhaustionReason;

    /** 次の {@code causeFoodExhaustion} の理由を置く。登録が無ければ何もしない。 */
    public static void exhaustionReason(final EntityExhaustionEvent.ExhaustionReason reason) {
        if (!listening(EntityExhaustionEvent.getHandlerList())) {
            return;
        }

        exhaustionReason = reason;
    }

    /**
     * EntityExhaustionEvent。{@code causeFoodExhaustion} の {@code addExhaustion} を囲む。
     *
     * <p>効かないもの: 理由(Paper は呼び出し側ごとに渡す。常に UNKNOWN)。
     *
     * @return vanilla へ進んでよいか。量が変えられていたら自分で足して false
     */
    public static boolean exhaustion(final Player player, final float amount) {
        final EntityExhaustionEvent.ExhaustionReason stashed = exhaustionReason;
        exhaustionReason = null;

        if (!listening(EntityExhaustionEvent.getHandlerList())) {
            return true;
        }

        final EntityExhaustionEvent event = CraftEventFactory.callPlayerExhaustionEvent(player,
                stashed == null ? EntityExhaustionEvent.ExhaustionReason.UNKNOWN : stashed, amount);

        if (event.isCancelled()) {
            return false;
        }

        if (event.getExhaustion() == amount) {
            return true;
        }

        player.getFoodData().addExhaustion(event.getExhaustion());

        return false;
    }


    // ------------------------------------------------------------ 花火・釣り・矢


    private static InteractionHand fishingHand;

    /** 釣りのイベントに載せる手を置く(hand の {@code retrieve(rod, hand)} など)。 */
    public static void fishingHand(final InteractionHand hand) {
        fishingHand = hand;
    }

    private static org.bukkit.inventory.EquipmentSlot fishingSlot() {
        return fishingHand == null ? null : CraftEquipmentSlot.getHand(fishingHand);
    }

    /** PlayerFishEvent(LURED)。かかるまでの間合いを決めた直後。 */
    public static boolean fishLured(final FishingHook hook) {
        final net.minecraft.world.entity.player.Player owner = hook.getPlayerOwner();

        if (owner == null || !listening(PlayerFishEvent.getHandlerList())) {
            return true;
        }

        return new PlayerFishEvent((org.bukkit.entity.Player) owner.getBukkitEntity(), null,
                (org.bukkit.entity.FishHook) hook.getBukkitEntity(), PlayerFishEvent.State.LURED).callEvent();
    }

    /**
     * PlayerFishEvent(CAUGHT_FISH)。{@code retrieve} で釣った物を作った直後。
     * 経験値は Paper と同じくここで乱数を引く(vanilla がオーブを作る式と同じ回数)。
     *
     * @return 発火したイベント。登録が無ければ null(vanilla の経験値オーブへ)
     */
    public static PlayerFishEvent fishCaught(final FishingHook hook,
                                             final net.minecraft.world.entity.player.Player owner,
                                             final net.minecraft.world.entity.item.ItemEntity caught) {
        if (!listening(PlayerFishEvent.getHandlerList())) {
            return null;
        }

        final PlayerFishEvent event = new PlayerFishEvent((org.bukkit.entity.Player) owner.getBukkitEntity(),
                caught.getBukkitEntity(), (org.bukkit.entity.FishHook) hook.getBukkitEntity(),
                PlayerFishEvent.State.CAUGHT_FISH);
        event.setExpToDrop(hook.random.nextInt(6) + 1);
        event.callEvent();

        return event;
    }




    public static boolean arrowPickupListening() {
        return listening(PlayerPickupArrowEvent.getHandlerList());
    }


    // ------------------------------------------------------------ ポーションの飛沫

    private static HitResult splashHit;

    /** 割れた位置を置く({@code onHit} から。水の飛沫のイベントに載せる)。 */
    public static void splashHit(final HitResult hitResult) {
        splashHit = hitResult;
    }


    public static boolean potionSplashListening() {
        return listening(org.bukkit.event.entity.PotionSplashEvent.getHandlerList());
    }


    // ------------------------------------------------------------ 乗り物




    public static boolean vehicleCollideListening() {
        return listening(VehicleEntityCollisionEvent.getHandlerList());
    }

    /**
     * VehicleEntityCollisionEvent。取り消されたら true。
     *
     * <p>vanilla のメソッドの中で組み立てると局所変数が増え、公式の変数の slot が動く。
     * トロッコの {@code tick} は Paper の差し込みだけで 5 つ増えていて、
     * 公式とずれた変数が 11 個あった。ここへ寄せると 0 になる。
     */
    public static boolean vehicleCollisionCancelled(final Entity vehicle, final Entity other) {
        if (!vehicleCollideListening()) {
            return false;
        }

        return !new VehicleEntityCollisionEvent(
                (org.bukkit.entity.Vehicle) vehicle.getBukkitEntity(), other.getBukkitEntity()).callEvent();
    }

    /** VehicleUpdateEvent か VehicleMoveEvent を聞いている登録があるか。 */
    private static boolean vehicleMoveListening() {
        return listening(org.bukkit.event.vehicle.VehicleUpdateEvent.getHandlerList())
                || listening(org.bukkit.event.vehicle.VehicleMoveEvent.getHandlerList());
    }

    /**
     * 動く前の位置を控える。{@code AbstractMinecart.tick} の頭から呼ぶ。
     * 登録が無ければ控えない({@code Location} も作らない)。
     */
    public static void vehicleMoveBefore(final net.minecraft.world.entity.vehicle.AbstractMinecart cart) {
        cart.shifuLastLocation = vehicleMoveListening()
                ? org.bukkit.craftbukkit.util.CraftLocation.toBukkit(
                        cart.position(), cart.level().getWorld(), cart.getYRot(), cart.getXRot())
                : null;
    }

    /**
     * VehicleUpdateEvent と VehicleMoveEvent。控えた位置が無ければ何もしない。
     *
     * <p>Paper は tick のたびに Location を 2 つ作って必ず発火するが、
     * 登録が無ければ観測できる違いは無いので、控える側で止めている。
     */
    public static void vehicleMoveAfter(final net.minecraft.world.entity.vehicle.AbstractMinecart cart) {
        final org.bukkit.Location from = cart.shifuLastLocation;

        if (from == null) {
            return;
        }

        cart.shifuLastLocation = null;

        final org.bukkit.Location to = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(
                cart.position(), cart.level().getWorld(), cart.getYRot(), cart.getXRot());
        final org.bukkit.entity.Vehicle vehicle = (org.bukkit.entity.Vehicle) cart.getBukkitEntity();

        new org.bukkit.event.vehicle.VehicleUpdateEvent(vehicle).callEvent();

        if (!from.equals(to)) {
            new org.bukkit.event.vehicle.VehicleMoveEvent(vehicle, from, to).callEvent();
        }
    }


    // io.papermc.paper.event.entity.EntityIgniteEvent は 26.x で入った Paper のイベントで、
    // 1.21.11 の API には無い。

    /**
     * EntityAirChangeEvent。空気の残りを書き込む直前。
     *
     * @return 書き込む値。取り消されたら今のまま
     */
    /** 空気の変化を聞いている登録があるか。 */
    public static boolean airChangeListening() {
        return listening(org.bukkit.event.entity.EntityAirChangeEvent.getHandlerList());
    }

    public static int airChange(final Entity entity, final int amount) {
        if (!listening(org.bukkit.event.entity.EntityAirChangeEvent.getHandlerList())) {
            return amount;
        }

        final org.bukkit.event.entity.EntityAirChangeEvent event =
                new org.bukkit.event.entity.EntityAirChangeEvent(entity.getBukkitEntity(), amount);

        return event.callEvent() ? event.getAmount() : entity.getAirSupply();
    }

    /**
     * HorseJumpEvent。乗り手の跳躍を実行する直前。
     *
     * @return 跳んでよいか
     */
    public static boolean horseJump(final net.minecraft.world.entity.animal.horse.AbstractHorse horse,
                                    final float power) {
        if (!listening(org.bukkit.event.entity.HorseJumpEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.entity.HorseJumpEvent(
                (org.bukkit.entity.AbstractHorse) horse.getBukkitEntity(), power).callEvent();
    }


    /** FireworkExplodeEvent。{@code explode()} を囲む。取り消されたら爆発しない。 */
    public static boolean fireworkExplode(final net.minecraft.world.entity.projectile.FireworkRocketEntity firework) {
        if (!listening(org.bukkit.event.entity.FireworkExplodeEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.callFireworkExplodeEvent(firework).isCancelled();
    }

    /**
     * EntityPortalEnterEvent。ネザーポータルの中に入った。
     *
     * @return 続けてよいか
     */
    public static boolean portalEnter(final Entity entity, final net.minecraft.world.level.Level level,
                                      final net.minecraft.core.BlockPos pos) {
        if (!listening(org.bukkit.event.entity.EntityPortalEnterEvent.getHandlerList())) {
            return true;
        }

        // 1.20.6 の構築子は PortalType を取らない(26.2 の追加)
        return new org.bukkit.event.entity.EntityPortalEnterEvent(entity.getBukkitEntity(),
                org.bukkit.craftbukkit.util.CraftLocation.toBukkit(pos, level)).callEvent();
    }


    /**
     * EntityResurrectEvent。トーテムを見つけて、まだ減らす前。
     *
     * <p>Paper はトーテムが無いときも(取り消し済みの形で)出して、プラグインが
     * 取り消しを外せば蘇生する。vanilla は「トーテムが無ければ何もしない」ので、
     * <b>その向きは通していない。</b>出すのはトーテムがあるときだけ。
     *
     * @return 蘇生してよいか。取り消されたら false(トーテムは減らない)
     */
    public static boolean resurrect(final LivingEntity entity, final net.minecraft.world.InteractionHand hand) {
        if (!listening(org.bukkit.event.entity.EntityResurrectEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.entity.EntityResurrectEvent(
                (org.bukkit.entity.LivingEntity) entity.getBukkitEntity(),
                org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand)).callEvent();
    }


    /** EntityZapEvent。雷に打たれた村人が魔女に変わる直前。 */
    public static boolean entityZap(final Entity entity, final Entity lightning, final Entity changed) {
        if (!listening(com.destroystokyo.paper.event.entity.EntityZapEvent.getHandlerList())) {
            return true;
        }

        return !org.bukkit.craftbukkit.event.CraftEventFactory.callEntityZapEvent(
                entity, lightning, changed).isCancelled();
    }

    /**
     * PlayerEggThrowEvent と ThrownEggHatchEvent。卵が割れて雛が出ると決まった直後。
     *
     * <p>vanilla は 8 分の 1 の判定を通ったときだけこの中に来る。Paper は
     * 通らなかったときも出して、プラグインが孵化させられる。<b>その向きは通していない。</b>
     * 出す種類({@code getHatchingType})も vanilla の分岐がニワトリ固定なので使っていない。
     *
     * @return 出す数。取り消されたら 0
     */
    public static int eggHatch(final net.minecraft.world.entity.projectile.ThrownEgg egg, final int hatches) {
        final Entity shooter = egg.getOwner();
        final boolean player = shooter instanceof net.minecraft.server.level.ServerPlayer;

        if (!listening(com.destroystokyo.paper.event.entity.ThrownEggHatchEvent.getHandlerList())
                && !(player && listening(org.bukkit.event.player.PlayerEggThrowEvent.getHandlerList()))) {
            return hatches;
        }

        final org.bukkit.entity.Egg bukkitEgg = (org.bukkit.entity.Egg) egg.getBukkitEntity();
        boolean hatching = true;
        byte count = (byte) hatches;

        if (player) {
            final org.bukkit.event.player.PlayerEggThrowEvent event = new org.bukkit.event.player.PlayerEggThrowEvent(
                    (org.bukkit.entity.Player) shooter.getBukkitEntity(), bukkitEgg, hatching, count,
                    org.bukkit.entity.EntityType.CHICKEN);
            event.callEvent();
            hatching = event.isHatching();
            count = hatching ? event.getNumHatches() : 0;
        }

        final com.destroystokyo.paper.event.entity.ThrownEggHatchEvent hatch =
                new com.destroystokyo.paper.event.entity.ThrownEggHatchEvent(bukkitEgg, hatching, count,
                        org.bukkit.entity.EntityType.CHICKEN);
        hatch.callEvent();

        return hatch.isHatching() ? hatch.getNumHatches() : 0;
    }


    /**
     * VehicleDamageEvent。乗り物が傷つく直前。
     *
     * <p>Paper はプラグインが直した傷の量を使う。vanilla の
     * {@code setDamage} の引数は変えられないので、<b>渡しているのは取り消しだけ。</b>
     */
    public static boolean vehicleDamage(final net.minecraft.world.entity.vehicle.VehicleEntity vehicle,
                                        final net.minecraft.world.damagesource.DamageSource source,
                                        final float amount) {
        if (!listening(org.bukkit.event.vehicle.VehicleDamageEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.vehicle.VehicleDamageEvent(
                (org.bukkit.entity.Vehicle) vehicle.getBukkitEntity(),
                source.getEntity() == null ? null : source.getEntity().getBukkitEntity(),
                (double) amount).callEvent();
    }

    /** VehicleDestroyEvent。乗り物が壊れる直前。 */
    public static boolean vehicleDestroy(final net.minecraft.world.entity.vehicle.VehicleEntity vehicle,
                                         final net.minecraft.world.damagesource.DamageSource source) {
        if (!listening(org.bukkit.event.vehicle.VehicleDestroyEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.vehicle.VehicleDestroyEvent(
                (org.bukkit.entity.Vehicle) vehicle.getBukkitEntity(),
                source.getEntity() == null ? null : source.getEntity().getBukkitEntity()).callEvent();
    }

    /**
     * VehicleEnterEvent と EntityMountEvent。乗る直前。
     *
     * <p>世界生成の途中では出さない(Paper と同じ)。
     */
    public static boolean mount(final Entity passenger, final Entity vehicle) {
        if (!passenger.valid) {
            return true;
        }

        if (vehicle.getBukkitEntity() instanceof org.bukkit.entity.Vehicle bukkitVehicle
                && listening(org.bukkit.event.vehicle.VehicleEnterEvent.getHandlerList())
                && !new org.bukkit.event.vehicle.VehicleEnterEvent(
                        bukkitVehicle, passenger.getBukkitEntity()).callEvent()) {
            return false;
        }

        if (!listening(org.bukkit.event.entity.EntityMountEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.entity.EntityMountEvent(
                passenger.getBukkitEntity(), vehicle.getBukkitEntity()).callEvent();
    }


    /** EntityToggleSwimEvent。泳ぐ・やめるの切り替え直前。 */
    public static boolean toggleSwim(final LivingEntity entity, final boolean swimming) {
        if (!listening(org.bukkit.event.entity.EntityToggleSwimEvent.getHandlerList())) {
            return true;
        }

        return !org.bukkit.craftbukkit.event.CraftEventFactory.callToggleSwimEvent(entity, swimming).isCancelled();
    }


    /** EnderDragonChangePhaseEvent。次の段階に移る直前。 */
    public static boolean dragonChangePhase(final net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon,
                                            final net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase<?> from,
                                            final net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase<?> to) {
        if (!listening(org.bukkit.event.entity.EnderDragonChangePhaseEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.entity.EnderDragonChangePhaseEvent(
                (org.bukkit.craftbukkit.entity.CraftEnderDragon) dragon.getBukkitEntity(),
                from == null ? null : org.bukkit.craftbukkit.entity.CraftEnderDragon.getBukkitPhase(from),
                org.bukkit.craftbukkit.entity.CraftEnderDragon.getBukkitPhase(to)).callEvent();
    }

    /** EnderDragonFireballHitEvent。ドラゴンの火の玉が雲を置く直前。 */
    public static boolean dragonFireballHit(final net.minecraft.world.entity.projectile.DragonFireball fireball,
                                            final java.util.List<LivingEntity> hit,
                                            final net.minecraft.world.entity.AreaEffectCloud cloud) {
        if (!listening(com.destroystokyo.paper.event.entity.EnderDragonFireballHitEvent.getHandlerList())) {
            return true;
        }

        final java.util.List<org.bukkit.entity.LivingEntity> targets = new java.util.ArrayList<>();

        for (final LivingEntity one : hit) {
            targets.add(one.getBukkitLivingEntity());
        }

        return new com.destroystokyo.paper.event.entity.EnderDragonFireballHitEvent(
                (org.bukkit.entity.DragonFireball) fireball.getBukkitEntity(), targets,
                (org.bukkit.entity.AreaEffectCloud) cloud.getBukkitEntity()).callEvent();
    }


    /** SpawnerSpawnEvent。スポナーが出したものを世界に置く直前。 */
    public static boolean spawnerSpawn(final Entity entity, final net.minecraft.core.BlockPos pos) {
        if (!listening(org.bukkit.event.entity.SpawnerSpawnEvent.getHandlerList())) {
            return true;
        }

        return !org.bukkit.craftbukkit.event.CraftEventFactory.callSpawnerSpawnEvent(entity, pos).isCancelled();
    }


    /** HangingPlaceEvent。額縁や絵を掛ける直前。 */
    public static boolean hangingPlace(final net.minecraft.world.entity.decoration.HangingEntity hanging,
                                       final net.minecraft.world.entity.player.Player player,
                                       final net.minecraft.core.BlockPos clicked,
                                       final net.minecraft.core.Direction face,
                                       final net.minecraft.world.InteractionHand hand,
                                       final net.minecraft.world.item.ItemStack stack) {
        if (!listening(org.bukkit.event.hanging.HangingPlaceEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.hanging.HangingPlaceEvent(
                (org.bukkit.entity.Hanging) hanging.getBukkitEntity(),
                player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                        ? serverPlayer.getBukkitEntity() : null,
                org.bukkit.craftbukkit.block.CraftBlock.at(hanging.level(), clicked),
                org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(face),
                org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(stack)).callEvent();
    }

    /** HangingBreakEvent。支えが無くなって落ちる直前。 */
    public static boolean hangingBreak(final net.minecraft.world.entity.decoration.HangingEntity hanging) {
        if (!listening(org.bukkit.event.hanging.HangingBreakEvent.getHandlerList())) {
            return true;
        }

        final org.bukkit.event.hanging.HangingBreakEvent.RemoveCause cause =
                hanging.level().getBlockState(hanging.blockPosition()).isAir()
                        ? org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.PHYSICS
                        : org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.OBSTRUCTION;

        return new org.bukkit.event.hanging.HangingBreakEvent(
                (org.bukkit.entity.Hanging) hanging.getBukkitEntity(), cause).callEvent();
    }


    /** RaidFinishEvent。襲撃が終わったとき。 */
    public static void raidFinish(final net.minecraft.world.entity.raid.Raid raid,
                                  final java.util.Collection<java.util.UUID> heroes) {
        if (!listening(org.bukkit.event.raid.RaidFinishEvent.getHandlerList())) {
            return;
        }

        final java.util.List<org.bukkit.entity.Player> winners = new java.util.ArrayList<>();

        for (final java.util.UUID id : heroes) {
            final org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(id);

            if (player != null) {
                winners.add(player);
            }
        }

        org.bukkit.craftbukkit.event.CraftEventFactory.callRaidFinishEvent(raid, winners);
    }

    /** RaidSpawnWaveEvent。1 波が出たあと。 */
    public static void raidSpawnWave(final net.minecraft.world.entity.raid.Raid raid,
                                     final net.minecraft.world.entity.raid.Raider leader,
                                     final java.util.List<net.minecraft.world.entity.raid.Raider> raiders) {
        if (!listening(org.bukkit.event.raid.RaidSpawnWaveEvent.getHandlerList())) {
            return;
        }

        org.bukkit.craftbukkit.event.CraftEventFactory.callRaidSpawnWaveEvent(raid, leader, raiders);
    }

    /**
     * EntityTeleportEvent。{@code /teleport} で プレイヤー以外を飛ばす直前。
     *
     * <p>プレイヤーは {@code PlayerTeleportEvent} の側(teleportCause)で出している。
     *
     * @return 飛ばしてよいか
     */
    public static boolean entityTeleportCommand(final Entity target, final ServerLevel level,
                                                final double x, final double y, final double z) {
        if (target instanceof net.minecraft.server.level.ServerPlayer
                || !listening(org.bukkit.event.entity.EntityTeleportEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.entity.EntityTeleportEvent(target.getBukkitEntity(),
                target.getBukkitEntity().getLocation(),
                new org.bukkit.Location(level.getWorld(), x, y, z)).callEvent();
    }

    /**
     * PlayerTeleportEndGatewayEvent と EntityTeleportEndGatewayEvent。
     * エンドゲートウェイで飛ばす直前。
     */
    public static boolean endGatewayTeleport(final Entity entity,
                                             final net.minecraft.server.level.ServerLevel level,
                                             final net.minecraft.core.BlockPos to,
                                             final net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity gateway) {
        final org.bukkit.entity.Entity bukkit = entity.getBukkitEntity();
        final org.bukkit.Location from = bukkit.getLocation();
        final org.bukkit.Location target = new org.bukkit.Location(level.getWorld(),
                to.getX() + 0.5, to.getY(), to.getZ() + 0.5, from.getYaw(), from.getPitch());

        if (bukkit instanceof org.bukkit.entity.Player player) {
            if (!listening(com.destroystokyo.paper.event.player.PlayerTeleportEndGatewayEvent.getHandlerList())) {
                return true;
            }

            return new com.destroystokyo.paper.event.player.PlayerTeleportEndGatewayEvent(player, from, target,
                    new org.bukkit.craftbukkit.block.CraftEndGateway(level.getWorld(), gateway)).callEvent();
        }

        if (!listening(com.destroystokyo.paper.event.entity.EntityTeleportEndGatewayEvent.getHandlerList())) {
            return true;
        }

        return new com.destroystokyo.paper.event.entity.EntityTeleportEndGatewayEvent(bukkit, from, target,
                new org.bukkit.craftbukkit.block.CraftEndGateway(level.getWorld(), gateway)).callEvent();
    }



    /** EntityPortalReadyEvent。ポータルで飛ぶ用意ができた直後。 */
    public static boolean portalReady(final Entity entity, final ServerLevel destination) {
        if (!listening(io.papermc.paper.event.entity.EntityPortalReadyEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.entity.EntityPortalReadyEvent(entity.getBukkitEntity(),
                destination == null ? null : destination.getWorld(), org.bukkit.PortalType.NETHER).callEvent();
    }


    /** EnderDragonFlameEvent。ドラゴンが炎の雲を置く直前。 */
    public static boolean dragonFlame(final net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon,
                                      final net.minecraft.world.entity.AreaEffectCloud flame) {
        if (!listening(com.destroystokyo.paper.event.entity.EnderDragonFlameEvent.getHandlerList())) {
            return true;
        }

        return new com.destroystokyo.paper.event.entity.EnderDragonFlameEvent(
                (org.bukkit.entity.EnderDragon) dragon.getBukkitEntity(),
                (org.bukkit.entity.AreaEffectCloud) flame.getBukkitEntity()).callEvent();
    }


    /** VehicleBlockCollisionEvent。乗り物がブロックにぶつかった直後。 */
    public static void vehicleBlockCollision(final Entity entity,
                                            final net.minecraft.world.phys.Vec3 wanted,
                                            final net.minecraft.world.phys.Vec3 moved) {
        if (!(entity.getBukkitEntity() instanceof org.bukkit.entity.Vehicle vehicle)
                || !listening(org.bukkit.event.vehicle.VehicleBlockCollisionEvent.getHandlerList())) {
            return;
        }

        org.bukkit.block.Block block = entity.level().getWorld().getBlockAt(
                net.minecraft.util.Mth.floor(entity.getX()),
                net.minecraft.util.Mth.floor(entity.getY()),
                net.minecraft.util.Mth.floor(entity.getZ()));

        if (wanted.x > moved.x) {
            block = block.getRelative(org.bukkit.block.BlockFace.EAST);
        } else if (wanted.x < moved.x) {
            block = block.getRelative(org.bukkit.block.BlockFace.WEST);
        } else if (wanted.z > moved.z) {
            block = block.getRelative(org.bukkit.block.BlockFace.SOUTH);
        } else if (wanted.z < moved.z) {
            block = block.getRelative(org.bukkit.block.BlockFace.NORTH);
        }

        if (block.getType().isAir()) {
            return;
        }

        new org.bukkit.event.vehicle.VehicleBlockCollisionEvent(vehicle, block,
                org.bukkit.craftbukkit.util.CraftVector.toBukkit(wanted)).callEvent();
    }


    /**
     * PhantomPreSpawnEvent。ファントムを 1 匹作る直前。
     *
     * <p>{@code shouldAbortSpawn} は「その群れを打ち切る」印。vanilla の
     * ループを外から止められないので、<b>1 匹ずつ飛ばすだけにしている。</b>
     */
    public static boolean phantomPreSpawn(final ServerLevel level, final net.minecraft.core.BlockPos pos,
                                          final net.minecraft.server.level.ServerPlayer player) {
        if (!listening(com.destroystokyo.paper.event.entity.PhantomPreSpawnEvent.getHandlerList())) {
            return true;
        }

        return new com.destroystokyo.paper.event.entity.PhantomPreSpawnEvent(
                org.bukkit.craftbukkit.util.CraftLocation.toBukkit(pos, level),
                player.getBukkitEntity(),
                org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL).callEvent();
    }


    /** PreSpawnerSpawnEvent。スポナーが中身を作る直前。 */
    public static boolean preSpawnerSpawn(final net.minecraft.world.level.Level level,
                                          final net.minecraft.world.entity.EntityType<?> type,
                                          final double x, final double y, final double z,
                                          final net.minecraft.core.BlockPos spawner) {
        if (!listening(com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent.getHandlerList())) {
            return true;
        }

        return new com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent(
                new org.bukkit.Location(level.getWorld(), x, y, z),
                org.bukkit.craftbukkit.entity.CraftEntityType.minecraftToBukkit(type),
                org.bukkit.craftbukkit.util.CraftLocation.toBukkit(spawner, level)).callEvent();
    }


    /** HangingBreakByEntityEvent。雷が額縁や絵に当たったとき。 */
    public static boolean hangingBreakByLightning(final Entity entity,
                                                  final net.minecraft.world.entity.LightningBolt lightning) {
        if (!(entity.getBukkitEntity() instanceof org.bukkit.entity.Hanging hanging)
                || !listening(org.bukkit.event.hanging.HangingBreakByEntityEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.hanging.HangingBreakByEntityEvent(hanging,
                lightning.getBukkitEntity()).callEvent();
    }

    /** ElderGuardianAppearanceEvent。エルダーガーディアンの幻を見せる直前。 */
    public static boolean elderGuardianAppearance(final Entity source,
                                                  final net.minecraft.server.level.ServerPlayer player) {
        if (!(source != null && source.getBukkitEntity() instanceof org.bukkit.entity.ElderGuardian guardian)
                || !listening(io.papermc.paper.event.entity.ElderGuardianAppearanceEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.entity.ElderGuardianAppearanceEvent(
                guardian, player.getBukkitEntity()).callEvent();
    }

}
