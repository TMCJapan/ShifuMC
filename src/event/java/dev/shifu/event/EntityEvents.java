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
import net.minecraft.world.entity.Leashable;
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
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
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

    // ------------------------------------------------------------ 自然湧き

    /**
     * PlayerNaturallySpawnCreaturesEvent。1 tick に 1 度、湧きを始める前に
     * プレイヤーごとに出す。イベントは各 ServerPlayer に控えて、chunk を選ぶときに読む。
     *
     * <p>半径の既定は Paper と同じ min(spigot の mob-spawn-range, 視距離, 8) チャンク。
     * 8 チャンクは vanilla の判定(16384 = 128 の 2 乗)と同じなので、
     * プラグインが縮めない限り vanilla と同じ範囲になる。
     *
     * 読んだ位置: paper-server patches/sources/net/minecraft/server/level/ServerChunkCache.java.patch
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




    /**
     * HangingBreakByEntityEvent(雷が額縁や絵に当たった)。{@code thunderHit} の {@code hurtServer} を囲む。
     *
     * @return 傷つけてよいか
     */
    public static boolean lightningHanging(final Entity entity, final LightningBolt bolt) {
        if (!(entity.getBukkitEntity() instanceof org.bukkit.entity.Hanging hanging)
                || !listening(HangingBreakEvent.getHandlerList())) {
            return true;
        }

        return new HangingBreakByEntityEvent(hanging, bolt.getBukkitEntity(),
                HangingBreakEvent.RemoveCause.ENTITY).callEvent();
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

    /**
     * EntityDropItemEvent。{@code Entity.spawnAtLocation} の {@code addFreshEntity} の直前。
     *
     * <p>未対応: 死亡ドロップの控え中は Paper は出さない(EntityDeathEvent に載せるだけ)が、
     * Shifu は控えの状態を発火層の外から見られないので出す。
     *
     * <p>読んだ位置: paper-server patches/sources/net/minecraft/world/entity/Entity.java.patch(spawnAtLocation)
     */
    public static boolean dropItem(final Entity dropper, final ItemEntity item) {
        if (!listening(EntityDropItemEvent.getHandlerList())) {
            return true;
        }

        return new EntityDropItemEvent(dropper.getBukkitEntity(), (org.bukkit.entity.Item) item.getBukkitEntity()).callEvent();
    }

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




    // ------------------------------------------------------------ 空気・削除

    private static boolean settingAir;

    /**
     * EntityAirChangeEvent。{@code setAirSupply} の代入を囲む。
     *
     * <p>効かないもの: 取り消したときのクライアントへの送り直し(Paper の {@code resendPossiblyDesyncedDataValues})。
     *
     * @return vanilla の代入へ進んでよいか。量が変えられていたら自分で入れて false
     */
    public static boolean airChange(final Entity entity, final int supply) {
        if (settingAir || !entity.valid || !listening(EntityAirChangeEvent.getHandlerList())) {
            return true;
        }

        final EntityAirChangeEvent event = new EntityAirChangeEvent(entity.getBukkitEntity(), supply);
        event.callEvent();

        if (event.isCancelled() && entity.getAirSupply() != supply) {
            return false;
        }

        if (event.getAmount() == supply) {
            return true;
        }

        settingAir = true;
        entity.setAirSupply(event.getAmount());
        settingAir = false;

        return false;
    }

    private static EntityRemoveEvent.Cause removeCause;

    /** 次の {@code setRemoved} の理由を置く。アダプタ層(hand の {@code discard(cause)} など)から。 */
    public static void removeCause(final EntityRemoveEvent.Cause cause) {
        removeCause = cause;
    }

    /**
     * EntityRemoveEvent。{@code Entity.setRemoved} の先頭(Paper と同じ)。
     *
     * <p>理由は置かれていればそれ、無ければ vanilla の {@code RemovalReason} から決める:
     * KILLED → DEATH、UNLOADED_TO_CHUNK → UNLOAD、UNLOADED_WITH_PLAYER → PLAYER_QUIT、
     * DISCARDED → DESPAWN。Paper は discard の呼び出し側ごとに細かい理由(PICKUP、MERGE、HIT …)を
     * 渡しているが、vanilla の呼び出し側は理由を持たないので DESPAWN とみなす。
     * CHANGED_DIMENSION は Paper も出さない。世界に入る前の個体も出さない。
     *
     * <p>読んだ位置: paper-server patches/sources/net/minecraft/world/entity/Entity.java.patch(setRemoved)、
     * CraftEventFactory.callEntityRemoveEvent
     */
    public static void removed(final Entity entity, final Entity.RemovalReason reason) {
        final EntityRemoveEvent.Cause stashed = removeCause;
        removeCause = null;

        if (entity instanceof ServerPlayer || !entity.valid || !listening(EntityRemoveEvent.getHandlerList())) {
            return;
        }

        final EntityRemoveEvent.Cause cause = stashed != null ? stashed : switch (reason) {
            case KILLED -> EntityRemoveEvent.Cause.DEATH;
            case DISCARDED -> EntityRemoveEvent.Cause.DESPAWN;
            case UNLOADED_TO_CHUNK -> EntityRemoveEvent.Cause.UNLOAD;
            case UNLOADED_WITH_PLAYER -> EntityRemoveEvent.Cause.PLAYER_QUIT;
            case CHANGED_DIMENSION -> null;
        };

        if (cause == null) {
            return;
        }

        new EntityRemoveEvent(entity.getBukkitEntity(), cause).callEvent();
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

    /**
     * PlayerPickupExperienceEvent。{@code ExperienceOrb.playerTouch} の拾う塊を囲む。
     * Paper と同じく、拾える tick({@code takeXpDelay == 0})だけ出す。
     */
    public static boolean pickupExperience(final ServerPlayer player, final ExperienceOrb orb) {
        if (player.takeXpDelay != 0
                || !listening(com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent.getHandlerList())) {
            return true;
        }

        return new com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent(
                player.getBukkitEntity(), (org.bukkit.entity.ExperienceOrb) orb.getBukkitEntity()).callEvent();
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

    /**
     * EntityRegainHealthEvent(理由つき)。{@code setHealth(getHealth() + amount)} を囲む。
     *
     * @return vanilla の setHealth へ進んでよいか。量が変えられていたら自分で入れて false
     */
    public static boolean regain(final LivingEntity entity, final float amount, final EntityRegainHealthEvent.RegainReason reason) {
        if (!listening(EntityRegainHealthEvent.getHandlerList())) {
            return true;
        }

        final EntityRegainHealthEvent event = new EntityRegainHealthEvent(entity.getBukkitEntity(), amount, reason);

        if (!event.callEvent()) {
            return false;
        }

        if (event.getAmount() == amount) {
            return true;
        }

        entity.setHealth((float) (entity.getHealth() + event.getAmount()));

        return false;
    }

    /**
     * EntityDeathEvent(落とし物の無い死。{@code kill} から)。Paper の {@code callEntityDeathEvent(level, victim, source)}
     * と同じものを組むが、Paper が続けて鳴らす死亡音は鳴らさない(vanilla の kill は鳴らさない)。
     */
    private static boolean deathAllowed(final ServerLevel level, final LivingEntity victim, final DamageSource source) {
        if (!listening(EntityDeathEvent.getHandlerList())) {
            return true;
        }

        final EntityDeathEvent event = new EntityDeathEvent((org.bukkit.entity.LivingEntity) victim.getBukkitEntity(),
                new CraftDamageSource(source), new ArrayList<>(0), victim.getExpReward(level, source.getEntity()));
        CraftEventFactory.populateFields(victim, event);

        return event.callEvent();
    }

    /** EntityDeathEvent。{@code EnderDragon.kill} の {@code remove} の前。 */
    public static boolean dragonKill(final ServerLevel level, final LivingEntity dragon) {
        return deathAllowed(level, dragon, dragon.damageSources().genericKill());
    }

    // ------------------------------------------------------------ 防具立て

    private static DamageSource armorStandSource;
    private static List<Entity.DefaultDrop> armorStandOuter;
    private static ArmorStand armorStandDeathFired;

    /** 次の {@code ArmorStand.kill} が使う被害の元を置く(BYPASSES_INVULNERABILITY の経路)。 */
    public static void armorStandDeathSource(final DamageSource source) {
        armorStandSource = source;
    }

    /** {@code brokenByPlayer} で、本体のアイテムを落とす前から控え始める(本体も EntityDeathEvent の落とし物に載せる)。 */
    public static void armorStandBeginOuter() {
        armorStandOuter = ShifuEvents.beginDeathDrops();
    }

    /** {@code brokenByAnything} の先頭。外側で控え始めていればそれを引き継ぎ、無ければここから控える。 */
    public static List<Entity.DefaultDrop> armorStandBegin() {
        final List<Entity.DefaultDrop> outer = armorStandOuter;
        armorStandOuter = null;

        return outer != null ? outer : ShifuEvents.beginDeathDrops();
    }

    /**
     * EntityDeathEvent(防具立てが壊れた)。{@code brokenByAnything} の末尾。控えた落とし物
     * (装備と、プレイヤーが壊したときは本体)を載せて {@link ShifuEvents#entityDeath} で出す。
     *
     * <p>未対応: 取り消し(LivingEntity の死亡と同じ。落とし物は出て、装備は空になり、kill も進む)。
     *
     * <p>読んだ位置: paper-server patches/sources/net/minecraft/world/entity/decoration/ArmorStand.java.patch
     */
    public static void armorStandDeath(final ServerLevel level, final ArmorStand stand, final DamageSource source,
                                       final List<Entity.DefaultDrop> drops) {
        if (drops == null) {
            return;
        }

        armorStandDeathFired = stand;
        ShifuEvents.entityDeath(level, stand, source, drops);
    }

    /**
     * EntityDeathEvent(壊れずに消える {@code ArmorStand.kill})。直前に {@code brokenByAnything} が
     * 出していれば出さない(Paper の {@code callEvent=false})。
     */
    public static boolean armorStandKill(final ServerLevel level, final ArmorStand stand) {
        final DamageSource source = armorStandSource;
        armorStandSource = null;

        if (armorStandDeathFired == stand) {
            armorStandDeathFired = null;

            return true;
        }

        return deathAllowed(level, stand, source != null ? source : stand.damageSources().genericKill());
    }

    // ------------------------------------------------------------ 額縁・絵・アイテム

    /**
     * HangingBreakEvent。{@code BlockAttachedEntity} の {@code discard / kill} と {@code dropItem} を囲む。
     * 出したあとに消えていたら(プラグインが消した)、vanilla の分は飛ばす(Paper と同じ)。
     */
    public static boolean hangingBreak(final Entity entity, final HangingBreakEvent.RemoveCause cause) {
        if (!(entity.getBukkitEntity() instanceof org.bukkit.entity.Hanging hanging)
                || !listening(HangingBreakEvent.getHandlerList())) {
            return true;
        }

        final HangingBreakEvent event = new HangingBreakEvent(hanging, cause);
        event.callEvent();

        return !entity.isRemoved() && !event.isCancelled();
    }

    /** {@code BlockAttachedEntity.tick} 用。ブロックに埋まっていれば OBSTRUCTION、そうでなければ PHYSICS。 */
    public static boolean hangingBreakTick(final Entity entity) {
        if (!listening(HangingBreakEvent.getHandlerList())) {
            return true;
        }

        return hangingBreak(entity, entity.level().getBlockState(entity.blockPosition()).isAir()
                ? HangingBreakEvent.RemoveCause.PHYSICS
                : HangingBreakEvent.RemoveCause.OBSTRUCTION);
    }

    /**
     * PlayerItemFrameChangeEvent(PLACE)。{@code ItemFrame.interact} の {@code setItem(itemStack)} を囲む。
     *
     * @return 0 = 取り消し(FAIL)、1 = vanilla、2 = 済み(差し替えた物を入れた)
     */
    public static int frameChange(final ItemFrame frame, final Player player, final ItemStack itemStack) {
        if (!listening(io.papermc.paper.event.player.PlayerItemFrameChangeEvent.getHandlerList())) {
            return 1;
        }

        final io.papermc.paper.event.player.PlayerItemFrameChangeEvent event =
                new io.papermc.paper.event.player.PlayerItemFrameChangeEvent(
                        (org.bukkit.entity.Player) player.getBukkitEntity(), (org.bukkit.entity.ItemFrame) frame.getBukkitEntity(),
                        CraftItemStack.asBukkitCopy(itemStack),
                        io.papermc.paper.event.player.PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE);

        if (!event.callEvent()) {
            return 0;
        }

        final ItemStack chosen = CraftItemStack.asNMSCopy(event.getItemStack());

        if (ItemStack.matches(chosen, itemStack)) {
            return 1;
        }

        frame.setItem(chosen);

        return 2;
    }

    /** ItemDespawnEvent。{@code ItemEntity.tick} で消える tick に、消す前。 */
    public static boolean itemDespawn(final ItemEntity item) {
        if (!listening(ItemDespawnEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.callItemDespawnEvent(item).isCancelled();
    }

    // ------------------------------------------------------------ プレイヤーの攻撃

    /**
     * PrePlayerAttackEntityEvent。{@code Player.attack} / {@code stabAttack} で、vanilla が
     * 攻撃できると判定したあと。
     *
     * <p>未対応: 攻撃できない相手のとき Paper は {@code willAttack=false} で出す。
     * ({@code cannotAttack} には副作用があるので 2 回呼べない。)
     */
    public static boolean preAttack(final Player player, final Entity target) {
        if (!listening(io.papermc.paper.event.player.PrePlayerAttackEntityEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.player.PrePlayerAttackEntityEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(), target.getBukkitEntity(), true).callEvent();
    }

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

    /** PlayerAttackEntityCooldownResetEvent。{@code Player.attack} の {@code onAttack()} を囲む。 */
    public static boolean attackCooldownReset(final Player player, final Entity target) {
        if (!listening(com.destroystokyo.paper.event.player.PlayerAttackEntityCooldownResetEvent.getHandlerList())) {
            return true;
        }

        return new com.destroystokyo.paper.event.player.PlayerAttackEntityCooldownResetEvent(
                (org.bukkit.entity.Player) player.getBukkitEntity(), target.getBukkitEntity(),
                player.getAttackStrengthScale(0.0F)).callEvent();
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

    /** PlayerFishEvent(LURED)。{@code catchingFish} で食いつくまでの時間を決めた直後。 */
    public static boolean fishLured(final FishingHook hook) {
        final Player owner = hook.getPlayerOwner();

        if (owner == null || !listening(PlayerFishEvent.getHandlerList())) {
            return true;
        }

        return new PlayerFishEvent((org.bukkit.entity.Player) owner.getBukkitEntity(), null,
                (org.bukkit.entity.FishHook) hook.getBukkitEntity(), PlayerFishEvent.State.LURED).callEvent();
    }


    /** PlayerFishEvent(IN_GROUND / REEL_IN)。 */
    public static boolean fishState(final FishingHook hook, final Player owner, final PlayerFishEvent.State state) {
        if (!listening(PlayerFishEvent.getHandlerList())) {
            return true;
        }

        return new PlayerFishEvent((org.bukkit.entity.Player) owner.getBukkitEntity(), null,
                (org.bukkit.entity.FishHook) hook.getBukkitEntity(), fishingSlot(), state).callEvent();
    }

    public static boolean arrowPickupListening() {
        return listening(PlayerPickupArrowEvent.getHandlerList());
    }

    /**
     * PlayerPickupArrowEvent。{@code AbstractArrow.playerTouch} の {@code tryPickup} の前。
     * Paper と同じく、拾える設定で、物があって、持てる余地があるときだけ出す。
     *
     * <p>効かないもの: 拾う物の差し替え(vanilla の tryPickup は自分で {@code getPickupItem} を見る)。
     */
    public static boolean pickupArrow(final AbstractArrow arrow, final Player player, final ItemStack pickupItem) {
        if (arrow.pickup != AbstractArrow.Pickup.ALLOWED || pickupItem.isEmpty()
                || player.getInventory().canHold(pickupItem) <= 0) {
            return true;
        }

        final ItemEntity item = new ItemEntity(arrow.level(), arrow.getX(), arrow.getY(), arrow.getZ(), pickupItem);

        return new PlayerPickupArrowEvent((org.bukkit.entity.Player) player.getBukkitEntity(),
                (org.bukkit.entity.Item) item.getBukkitEntity(), (org.bukkit.entity.AbstractArrow) arrow.getBukkitEntity()).callEvent();
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

    private static org.bukkit.entity.Entity attackerOf(final DamageSource source) {
        final Entity direct = source.getDirectEntity();

        return direct == null ? null : direct.getBukkitEntity();
    }

    /**
     * VehicleDamageEvent。{@code VehicleEntity.hurtServer} で傷を付ける前。
     *
     * <p>効かないもの: ダメージの差し替え(vanilla の引数は final)。
     */
    public static boolean vehicleDamage(final VehicleEntity vehicle, final DamageSource source, final float damage) {
        if (!listening(VehicleDamageEvent.getHandlerList())) {
            return true;
        }

        return new VehicleDamageEvent((org.bukkit.entity.Vehicle) vehicle.getBukkitEntity(),
                attackerOf(source), damage).callEvent();
    }

    /** VehicleDestroyEvent。{@code discard} / {@code destroy} を囲む。取り消されたら傷を 40 にして true を返す(Paper)。 */
    public static boolean vehicleDestroy(final VehicleEntity vehicle, final DamageSource source) {
        if (!listening(VehicleDestroyEvent.getHandlerList())) {
            return true;
        }

        return new VehicleDestroyEvent((org.bukkit.entity.Vehicle) vehicle.getBukkitEntity(),
                attackerOf(source)).callEvent();
    }

    public static boolean vehicleCollideListening() {
        return listening(VehicleEntityCollisionEvent.getHandlerList());
    }

    /** VehicleEntityCollisionEvent。{@code AbstractMinecart.canCollideWith} で、vanilla がぶつかると判定したあと。 */
    public static boolean vehicleCollide(final Entity vehicle, final Entity other) {
        return new VehicleEntityCollisionEvent((org.bukkit.entity.Vehicle) vehicle.getBukkitEntity(), other.getBukkitEntity()).callEvent();
    }

    // io.papermc.paper.event.entity.EntityIgniteEvent は 26.x で入った Paper のイベントで、
    // 1.21.11 の API には無い。
}
