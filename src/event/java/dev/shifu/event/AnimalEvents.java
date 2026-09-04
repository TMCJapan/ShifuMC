// SPDX-License-Identifier: GPL-3.0-only
package dev.shifu.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.turtle.Turtle;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.entity.CraftVillager;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;

/**
 * 動物・怪物・AI のイベント(entity-animal)。{@link ShifuEvents} と同じ作り。
 *
 * <p>すべて static。先頭で登録の有無を見て、無ければ何も作らずに「vanilla を続けてよい」を返す。
 * 返り値が boolean のものは「vanilla の処理を続けてよいか」。値の差し替えが要るものは
 * 差し替え後の値、または {@link #KEEP} / {@link #CANCEL} / {@link #ERASE} / {@link #REPLACE} の符号を返す。
 */
public final class AnimalEvents {

    private AnimalEvents() {
    }

    /** vanilla の行をそのまま走らせる。 */
    public static final int KEEP = 0;
    /** 取り消し。 */
    public static final int CANCEL = 1;
    /** 記憶を消す({@link #tempt})。 */
    public static final int ERASE = 2;
    /** 差し替える。差し替え後の値は別のメソッドで取り出す。 */
    public static final int REPLACE = 3;

    private static boolean listening(final org.bukkit.event.HandlerList handlers) {
        return ShifuEvents.listening(handlers);
    }

    // ------------------------------------------------------------ カメ

    /**
     * TurtleGoHomeEvent。TurtleGoHomeGoal.canUse() の判定が全て通ったあと。
     *
     * <p>vanilla の判定は {@code getRandom().nextInt()} を消費するので、発火を前に出すと
     * 乱数の消費が変わる。式の末尾に置いて、通ったときだけ出す(Paper と同じ位置)。
     *
     * @return 巣へ帰ってよいか
     *
     * 読んだ位置: paper-server patches/sources/net/minecraft/world/entity/animal/turtle/Turtle.java.patch
     */
    public static boolean turtleGoHome(final Turtle turtle) {
        if (!listening(com.destroystokyo.paper.event.entity.TurtleGoHomeEvent.getHandlerList())) {
            return true;
        }

        return new com.destroystokyo.paper.event.entity.TurtleGoHomeEvent(
                (org.bukkit.entity.Turtle) turtle.getBukkitEntity()).callEvent();
    }

    // ------------------------------------------------------------ 座る

    /**
     * EntityToggleSitEvent。{@code TamableAnimal.setInSittingPose}、{@code Fox.setSitting}、
     * {@code Panda.sit}、{@code Camel.sitDown / standUp / standUpInstantly} の先頭。Paper と同じ位置。
     *
     * @return 座り(立ち)状態を書き換えてよいか
     */
    public static boolean toggleSit(final Entity entity, final boolean sitting) {
        if (!listening(io.papermc.paper.event.entity.EntityToggleSitEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.entity.EntityToggleSitEvent(entity.getBukkitEntity(), sitting).callEvent();
    }

    // ------------------------------------------------------------ 飼われている動物

    /**
     * TameableDeathMessageEvent。{@code TamableAnimal.die} で飼い主に死亡メッセージを送る直前。
     * 文が差し替えられていればここで送り、vanilla の送信は飛ばす。
     *
     * @return vanilla の送信へ進んでよいか
     */
    public static boolean tameableDeathMessage(final TamableAnimal animal, final ServerPlayer owner) {
        if (!listening(io.papermc.paper.event.entity.TameableDeathMessageEvent.getHandlerList())) {
            return true;
        }

        final Component original = PaperAdventure.asAdventure(animal.getCombatTracker().getDeathMessage());
        final io.papermc.paper.event.entity.TameableDeathMessageEvent event = new io.papermc.paper.event.entity.TameableDeathMessageEvent(
                (org.bukkit.entity.Tameable) animal.getBukkitEntity(), original);

        if (!event.callEvent()) {
            return false;
        }

        if (event.deathMessage().equals(original)) {
            return true;
        }

        owner.sendSystemMessage(PaperAdventure.asVanilla(event.deathMessage()));

        return false;
    }

    /**
     * EntityTeleportEvent。{@code TamableAnimal.maybeTeleportTo} で飼い主のそばへ飛ぶ直前。
     *
     * @return null なら取り消し(vanilla は false を返す)。TRUE なら vanilla の snapTo へ。
     *         FALSE なら差し替え先へ飛ばし済み(vanilla の snapTo は飛ばす)
     */
    public static Boolean teleport(final Entity entity, final double x, final double y, final double z) {
        if (!listening(org.bukkit.event.entity.EntityTeleportEvent.getHandlerList())) {
            return Boolean.TRUE;
        }

        final org.bukkit.event.entity.EntityTeleportEvent event = CraftEventFactory.callEntityTeleportEvent(entity, x, y, z);

        if (event.isCancelled() || event.getTo() == null) {
            return null;
        }

        final org.bukkit.Location to = event.getTo();

        if (to.getX() == x && to.getY() == y && to.getZ() == z
                && to.getYaw() == entity.getYRot() && to.getPitch() == entity.getXRot()) {
            return Boolean.TRUE;
        }

        entity.snapTo(to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch());

        return Boolean.FALSE;
    }

    // ------------------------------------------------------------ 村人

    /**
     * VillagerCareerChangeEvent。{@code AssignProfessionFromJobSite}(EMPLOYED)と
     * {@code ResetProfession}(LOSING_JOB)で職業を書き換える直前。
     *
     * @return 書き込む職業。取り消されたら null
     */
    public static Holder<VillagerProfession> careerChange(final Villager villager, final Holder<VillagerProfession> profession,
                                                         final VillagerCareerChangeEvent.ChangeReason reason) {
        if (!listening(VillagerCareerChangeEvent.getHandlerList())) {
            return profession;
        }

        final VillagerCareerChangeEvent event = CraftEventFactory.callVillagerCareerChangeEvent(
                villager, CraftVillager.CraftProfession.minecraftHolderToBukkit(profession), reason);

        if (event.isCancelled()) {
            return null;
        }

        return CraftVillager.CraftProfession.bukkitToMinecraftHolder(event.getProfession());
    }

    /**
     * VillagerReplenishTradeEvent。{@code Villager.restock} と {@code catchUpDemand} で
     * 取引の使用回数を戻す直前(取引ごと)。
     *
     * @return 戻してよいか
     */
    public static boolean replenishTrade(final Villager villager, final MerchantOffer offer) {
        if (!listening(org.bukkit.event.entity.VillagerReplenishTradeEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.entity.VillagerReplenishTradeEvent(
                (org.bukkit.entity.Villager) villager.getBukkitEntity(), offer.asBukkit()).callEvent();
    }

    /**
     * ItemTransportingEntityValidateTargetEvent。{@code TransportItemsBetweenContainers.isTargetValidToPick}
     * で vanilla の判定が通ったあと。
     *
     * @return その容器を目標にしてよいか
     */
    public static boolean transporterValidateTarget(final PathfinderMob body, final Level level, final BlockPos pos) {
        if (!listening(io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent.getHandlerList())) {
            return true;
        }

        return CraftEventFactory.callTransporterValidateTarget(body, level, pos);
    }

    // ------------------------------------------------------------ 繁殖・手懐け

    /** EntityBreedEvent に登録があるか。{@code VillagerMakeLove} が年齢の控えを取るかどうかに使う。 */
    public static boolean breedListening() {
        return listening(org.bukkit.event.entity.EntityBreedEvent.getHandlerList());
    }

    /**
     * EntityBreedEvent(村人)。{@code VillagerMakeLove.breed} で子を世界に置く直前。
     * Paper は親の年齢を書き換える前に発火するが、Shifu は書き換えたあとに発火して、
     * 取り消されたら呼ぶ側が控えに戻す。
     *
     * @return 子を置いてよいか
     */
    public static boolean villagerBreed(final Villager child, final Villager source, final Villager target) {
        if (!breedListening()) {
            return true;
        }

        return !CraftEventFactory.callEntityBreedEvent(child, source, target, null, null, 0).isCancelled();
    }

    /**
     * EntityBreedEvent(動物)。{@code Animal.spawnChildFromBreeding} と {@code Fox.FoxBreedGoal.breed}
     * で、統計・年齢・経験値の処理に入る直前。Paper と同じ位置。
     *
     * <p>効かないもの: {@code setExperience}(経験値は vanilla が自分で引く)。{@code getBredWith()} は null。
     * 登録があると、この動物の乱数を 1 回余分に引く(イベントに載せる経験値の値)。
     *
     * @return 続けてよいか。取り消しの後始末(resetLove)は呼ぶ側
     */
    public static boolean breed(final Animal animal, final Animal partner, final AgeableMob offspring) {
        if (!breedListening()) {
            return true;
        }

        ServerPlayer breeder = animal.getLoveCause();

        if (breeder == null) {
            breeder = partner.getLoveCause();
        }

        return !CraftEventFactory.callEntityBreedEvent(offspring, animal, partner, breeder, null,
                animal.getRandom().nextInt(7) + 1).isCancelled();
    }

    /**
     * EntityFertilizeEggEvent。{@code Frog.spawnChildFromBreeding} と {@code Turtle.TurtleBreedGoal.breed}
     * の先頭。取り消されたら両親の love を戻す(CraftEventFactory がやる)。
     *
     * <p>効かないもの: {@code setExperience}。登録があると、この動物の乱数を 1 回余分に引く。
     *
     * @return 続けてよいか
     */
    public static boolean fertilizeEgg(final Animal breeding, final Animal partner) {
        if (!listening(io.papermc.paper.event.entity.EntityFertilizeEggEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.callEntityFertilizeEggEvent(breeding, partner).isCancelled();
    }

    /**
     * EntityEnterLoveModeEvent。{@code Animal.setInLove} の先頭。
     *
     * @return love の tick 数。取り消されたら -1
     */
    public static int enterLove(final Animal animal, final Player player) {
        if (!listening(org.bukkit.event.entity.EntityEnterLoveModeEvent.getHandlerList())) {
            return 600;
        }

        final org.bukkit.event.entity.EntityEnterLoveModeEvent event = CraftEventFactory.callEntityEnterLoveModeEvent(player, animal, 600);

        return event.isCancelled() ? -1 : event.getTicksInLove();
    }

    /**
     * EntityTameEvent。手懐けの乱数が当たった直後(Cat / Wolf / Parrot / Ocelot / AbstractNautilus /
     * RunAroundLikeCrazyGoal)。取り消されたら呼ぶ側が「外れたとき」と同じ処理をする。
     *
     * @return 手懐けてよいか
     */
    public static boolean tame(final Mob mob, final Player player) {
        if (!listening(org.bukkit.event.entity.EntityTameEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.callEntityTameEvent(mob, player).isCancelled();
    }

    // ------------------------------------------------------------ 誘い

    private static Player temptTarget;

    /**
     * EntityTargetLivingEntityEvent(TEMPT)。{@code TemptingSensor.doTick} で誘う相手を記憶する直前。
     *
     * @return {@link #KEEP}(vanilla のまま)/ {@link #CANCEL}(記憶を触らない)/
     *         {@link #ERASE}(相手がプレイヤー以外に差し替えられた。記憶を消す)/
     *         {@link #REPLACE}(別のプレイヤー。{@link #temptTarget()} で取り出す)
     */
    public static int tempt(final PathfinderMob body, final Player player) {
        if (!listening(org.bukkit.event.entity.EntityTargetLivingEntityEvent.getHandlerList())) {
            return KEEP;
        }

        final org.bukkit.event.entity.EntityTargetLivingEntityEvent event = CraftEventFactory.callEntityTargetLivingEvent(
                body, player, EntityTargetEvent.TargetReason.TEMPT);

        if (event.isCancelled()) {
            return CANCEL;
        }

        if (!(event.getTarget() instanceof org.bukkit.craftbukkit.entity.CraftHumanEntity human)) {
            return ERASE;
        }

        if (human.getHandle() == player) {
            return KEEP;
        }

        temptTarget = human.getHandle();

        return REPLACE;
    }

    /** {@link #tempt} が {@link #REPLACE} を返したときの相手。 */
    public static Player temptTarget() {
        final Player target = temptTarget;
        temptTarget = null;

        return target;
    }

    // ------------------------------------------------------------ 刈る・拾う・落とす

    /**
     * PlayerShearEntityEvent。{@code mobInteract} で {@code shear} を呼ぶ直前
     * (Sheep / MushroomCow / SnowGolem / CopperGolem / Bogged / SulfurCube)。
     *
     * <p>効かないもの: {@code getDrops()} の中身と差し替え。落とし物は vanilla の {@code shear} が
     * ルートテーブルから自分で引くので、空のリストを載せる。
     *
     * @return 刈ってよいか
     */
    public static boolean shear(final Player player, final Entity sheared, final ItemStack shears, final InteractionHand hand) {
        if (!listening(org.bukkit.event.player.PlayerShearEntityEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.handlePlayerShearEntityEvent(player, sheared, shears, hand, new ArrayList<>()).isCancelled();
    }

    /** EntityPickupItemEvent に登録が無いか。{@code Fox.pickUpItem} が拾う物を読み直すかどうかに使う。 */
    public static boolean silentPickup() {
        return !listening(org.bukkit.event.entity.EntityPickupItemEvent.getHandlerList());
    }

    /**
     * EntityPickupItemEvent。{@code Fox.pickUpItem} / {@code Panda.pickUpItem} で拾えると判定した直後。
     *
     * <p>効かないもの: 拾えないときに発火して {@code setCancelled(false)} で拾わせること
     * (Paper はそうしているが、vanilla の判定は通せない)。
     *
     * @return 拾ってよいか
     */
    public static boolean pickupItem(final Mob mob, final ItemEntity item, final int remaining) {
        if (silentPickup()) {
            return true;
        }

        return !CraftEventFactory.callEntityPickupItemEvent(mob, item, remaining, false).isCancelled();
    }

    /**
     * EntityDropItemEvent。{@code Fox.spitOutItem} / {@code Fox.dropItemStack} で落とし物を世界に置く直前。
     * Paper は {@code spawnAtLocation} の中で発火する。
     *
     * @return 置いてよいか
     */
    public static boolean dropItem(final Entity entity, final ItemEntity item) {
        if (!listening(org.bukkit.event.entity.EntityDropItemEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.entity.EntityDropItemEvent(entity.getBukkitEntity(), (org.bukkit.entity.Item) item.getBukkitEntity()).callEvent();
    }

    // ------------------------------------------------------------ 個別の動物

    /**
     * PigZapEvent。{@code Pig.thunderHit} で変換に入る直前。
     *
     * <p>Paper は変換したあと、世界に置く前に発火する。Shifu の {@code convertTo} は中で置いて
     * 豚を消すので、その前に発火する。イベントに載せる ZombifiedPiglin は仮に作ったもの
     * (世界には置かない)で、実際に変換される個体ではない。
     *
     * <p>効かないもの: {@code getPigZombie()} への変更。登録があると entity の id を 1 つ消費する。
     *
     * @return 変換してよいか
     */
    public static boolean pigZap(final Pig pig, final LightningBolt bolt, final ServerLevel level) {
        if (!listening(org.bukkit.event.entity.PigZapEvent.getHandlerList())) {
            return true;
        }

        final ZombifiedPiglin sample = EntityTypes.ZOMBIFIED_PIGLIN.create(level, EntitySpawnReason.CONVERSION);

        if (sample == null) {
            return true;
        }

        return !CraftEventFactory.callPigZapEvent(pig, bolt, sample).isCancelled();
    }

    /**
     * TurtleStartDiggingEvent。{@code Turtle.TurtleLayEggGoal.tick} で掘り始める直前。
     *
     * @return 掘り始めてよいか。取り消されたら呼ぶ側が {@code setLayingEgg(false)} にする(Paper と同じ)
     */
    public static boolean turtleStartDigging(final Turtle turtle, final BlockPos pos) {
        if (!listening(com.destroystokyo.paper.event.entity.TurtleStartDiggingEvent.getHandlerList())) {
            return true;
        }

        return new com.destroystokyo.paper.event.entity.TurtleStartDiggingEvent(
                (org.bukkit.entity.Turtle) turtle.getBukkitEntity(), CraftLocation.toBukkit(pos, turtle.level())).callEvent();
    }

    /**
     * CreeperIgniteEvent。{@code Creeper.ignite} の先頭。すでに点火していれば発火しない(Paper と同じ)。
     *
     * @return 点火状態を true にしてよいか(取り消し、または {@code setIgnited(false)} なら false)
     */
    public static boolean creeperIgnite(final Creeper creeper) {
        if (!listening(com.destroystokyo.paper.event.entity.CreeperIgniteEvent.getHandlerList())) {
            return true;
        }

        if (creeper.isIgnited()) {
            return true;
        }

        final com.destroystokyo.paper.event.entity.CreeperIgniteEvent event = new com.destroystokyo.paper.event.entity.CreeperIgniteEvent(
                (org.bukkit.entity.Creeper) creeper.getBukkitEntity(), true);

        return event.callEvent() && event.isIgnited();
    }

    /**
     * ElderGuardianAppearanceEvent。{@code ElderGuardian.customServerAiStep} で採掘速度低下を
     * かけたあと、ジャンプスケアの packet を送る前。プレイヤーごとに発火する。
     *
     * <p>Paper は効果をかける前に発火して、取り消されたプレイヤーには効果もかけない。Shifu は
     * vanilla がかけたあとに発火し、取り消されたプレイヤーからは効果を外して packet も送らない。
     * そのプレイヤーが元から採掘速度低下(残り 1200 tick 未満)を持っていた場合、それも消える。
     */
    public static void elderGuardianAppearance(final ElderGuardian guardian, final List<ServerPlayer> affected) {
        if (!listening(io.papermc.paper.event.entity.ElderGuardianAppearanceEvent.getHandlerList())) {
            return;
        }

        for (final Iterator<ServerPlayer> it = affected.iterator(); it.hasNext();) {
            final ServerPlayer player = it.next();
            final io.papermc.paper.event.entity.ElderGuardianAppearanceEvent event = new io.papermc.paper.event.entity.ElderGuardianAppearanceEvent(
                    (org.bukkit.entity.ElderGuardian) guardian.getBukkitEntity(), player.getBukkitEntity());

            if (!event.callEvent()) {
                player.removeEffect(MobEffects.MINING_FATIGUE);
                it.remove();
            }
        }
    }

    /**
     * ShulkerDuplicateEvent。{@code Shulker.hitByShulkerBullet} で子を世界に置く直前。Paper と同じ位置。
     *
     * @return 置いてよいか
     */
    public static boolean shulkerDuplicate(final Shulker child, final Shulker parent) {
        if (!listening(io.papermc.paper.event.entity.ShulkerDuplicateEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.entity.ShulkerDuplicateEvent(
                (org.bukkit.entity.Shulker) child.getBukkitEntity(), (org.bukkit.entity.Shulker) parent.getBukkitEntity()).callEvent();
    }

    /**
     * StriderTemperatureChangeEvent。{@code Strider.tick} で震えの状態が変わるときだけ発火する
     * (変わらないときは呼ぶ側が短絡させる)。
     *
     * @return 書き換えてよいか
     */
    public static boolean striderTemperature(final Strider strider, final boolean shivering) {
        if (!listening(org.bukkit.event.entity.StriderTemperatureChangeEvent.getHandlerList())) {
            return true;
        }

        return CraftEventFactory.callStriderTemperatureChangeEvent(strider, shivering);
    }

    /**
     * EntityCombustByEntityEvent。{@code Zombie.doHurtTarget} で相手に火を付ける直前。
     *
     * @return 燃やす秒数。取り消されたら -1。vanilla の値と同じならそのまま vanilla の行へ
     */
    public static float combustByEntity(final Entity combuster, final Entity target, final int seconds) {
        if (!listening(org.bukkit.event.entity.EntityCombustByEntityEvent.getHandlerList())) {
            return seconds;
        }

        final org.bukkit.event.entity.EntityCombustByEntityEvent event = new org.bukkit.event.entity.EntityCombustByEntityEvent(
                combuster.getBukkitEntity(), target.getBukkitEntity(), (float) seconds);

        return event.callEvent() ? event.getDuration() : -1.0F;
    }

    /** PigZombieAngerEvent に登録が無いか。 */
    public static boolean silentPigZombieAnger() {
        return !listening(org.bukkit.event.entity.PigZombieAngerEvent.getHandlerList());
    }

    /**
     * PigZombieAngerEvent。{@code ZombifiedPiglin.startPersistentAngerTimer}。怒りの長さは
     * 呼ぶ側が vanilla と同じ式で 1 回だけ引いて渡す。
     *
     * @return 怒りの長さ。取り消されたら -1(呼ぶ側が怒りの相手を消す)
     */
    public static int pigZombieAnger(final ZombifiedPiglin piglin, final int anger) {
        final Entity target = net.minecraft.world.entity.EntityReference.getLivingEntity(piglin.getPersistentAngerTarget(), piglin.level());
        final org.bukkit.event.entity.PigZombieAngerEvent event = new org.bukkit.event.entity.PigZombieAngerEvent(
                (org.bukkit.entity.PigZombie) piglin.getBukkitEntity(), target == null ? null : target.getBukkitEntity(), anger);

        return event.callEvent() ? event.getNewAnger() : -1;
    }

    /**
     * RaidTriggerEvent に登録があるか。{@code Raids.createOrExtendRaid} が、この呼び出しで
     * 襲撃を登録したかどうかを控えるのに使う。
     */
    public static boolean raidTriggerListening() {
        return listening(org.bukkit.event.raid.RaidTriggerEvent.getHandlerList());
    }

    /**
     * RaidTriggerEvent。{@code Raids.createOrExtendRaid} で不吉な予感を吸わせる直前。
     *
     * <p>Paper は襲撃の登録をイベントのあとに回している。Shifu は vanilla が先に登録するので、
     * 取り消されたら呼ぶ側が登録を外す。
     *
     * @return 続けてよいか
     */
    public static boolean raidTrigger(final ServerLevel level, final Raid raid, final ServerPlayer player) {
        if (!raidTriggerListening()) {
            return true;
        }

        return CraftEventFactory.callRaidTriggerEvent(level, raid, player);
    }

    /** PiglinBarterEvent に登録が無いか。 */
    public static boolean silentBarter() {
        return !listening(org.bukkit.event.entity.PiglinBarterEvent.getHandlerList());
    }

    /**
     * PiglinBarterEvent。{@code PiglinAi.stopHoldingOffHandItem} で見返りを投げる直前。
     * 見返りは呼ぶ側が vanilla と同じ式で 1 回だけ引いて渡す。
     *
     * @return 投げる物。取り消されたら null
     */
    public static List<ItemStack> piglinBarter(final Piglin piglin, final List<ItemStack> outcome, final ItemStack input) {
        final org.bukkit.event.entity.PiglinBarterEvent event = CraftEventFactory.callPiglinBarterEvent(piglin, outcome, input);

        if (event.isCancelled()) {
            return null;
        }

        final List<ItemStack> result = new ArrayList<>(event.getOutcome().size());

        for (final org.bukkit.inventory.ItemStack item : event.getOutcome()) {
            result.add(CraftItemStack.asNMSCopy(item));
        }

        return result;
    }

    // ------------------------------------------------------------ スライム

    /**
     * SlimeSplitEvent。{@code AbstractCubeMob.remove} で分裂する直前。
     *
     * @return 分裂する数。取り消されたら 0(分裂しない)
     */
    public static int slimeSplit(final AbstractCubeMob cube, final int count) {
        if (!listening(org.bukkit.event.entity.SlimeSplitEvent.getHandlerList())) {
            return count;
        }

        final org.bukkit.event.entity.SlimeSplitEvent event = new org.bukkit.event.entity.SlimeSplitEvent(
                (org.bukkit.entity.AbstractCubeMob) cube.getBukkitEntity(), count);

        return event.callEvent() && event.getCount() > 0 ? event.getCount() : 0;
    }

    private static AbstractCubeMob splitting;
    private static List<LivingEntity> splitCubes;

    /** 分裂の前。分裂で出来る個体を {@link #splitCube} で集め始める。 */
    public static void splitBegin(final AbstractCubeMob cube) {
        if (!listening(org.bukkit.event.entity.EntityTransformEvent.getHandlerList())) {
            return;
        }

        splitting = cube;
        splitCubes = new ArrayList<>();
    }

    /** {@code setUpSplitCube} の末尾。convertTo が世界に置く前に呼ばれる。 */
    public static void splitCube(final AbstractCubeMob cube, final AbstractCubeMob piece) {
        if (splitting == cube) {
            splitCubes.add(piece);
        }
    }

    /**
     * EntityTransformEvent(SPLIT)。分裂の直後。Paper は世界に置く前に発火するが、Shifu の
     * {@code convertTo} は中で置くので、置いたあとに発火して、取り消されたら全部消す。
     * 同じ tick の中なので、クライアントには出現と消滅が一緒に届く。
     */
    public static void splitEnd(final AbstractCubeMob cube) {
        if (splitting != cube) {
            return;
        }

        final List<LivingEntity> pieces = splitCubes;
        splitting = null;
        splitCubes = null;

        if (pieces.isEmpty()) {
            return;
        }

        if (CraftEventFactory.callEntityTransformEvent(cube, pieces, org.bukkit.event.entity.EntityTransformEvent.TransformReason.SPLIT).isCancelled()) {
            for (final LivingEntity piece : pieces) {
                piece.discard();
            }
        }
    }

    /** SlimeTargetLivingEntityEvent に登録があるか。Goal の判定を 2 度評価しないための短絡に使う。 */
    public static boolean slimeTargetListening() {
        return listening(com.destroystokyo.paper.event.entity.SlimeTargetLivingEntityEvent.getHandlerList());
    }

    /**
     * SlimeTargetLivingEntityEvent。{@code CubeMobAttackGoal.canUse / canContinueToUse} で、
     * vanilla が true を返す条件が揃っているときだけ発火する。
     *
     * @return 狙ってよいか
     */
    public static boolean slimeTarget(final AbstractCubeMob cube, final LivingEntity target) {
        return new com.destroystokyo.paper.event.entity.SlimeTargetLivingEntityEvent(
                (org.bukkit.entity.AbstractCubeMob) cube.getBukkitEntity(), (org.bukkit.entity.LivingEntity) target.getBukkitEntity()).callEvent();
    }

    /** SlimeSwimEvent に登録があるか。 */
    public static boolean slimeSwimListening() {
        return listening(com.destroystokyo.paper.event.entity.SlimeSwimEvent.getHandlerList());
    }

    /** SlimeSwimEvent。{@code CubeMobFloatGoal.canUse} で vanilla が true を返す条件が揃っているとき。 */
    public static boolean slimeSwim(final AbstractCubeMob cube) {
        return new com.destroystokyo.paper.event.entity.SlimeSwimEvent((org.bukkit.entity.AbstractCubeMob) cube.getBukkitEntity()).callEvent();
    }

    /** SlimeWanderEvent に登録があるか。 */
    public static boolean slimeWanderListening() {
        return listening(com.destroystokyo.paper.event.entity.SlimeWanderEvent.getHandlerList());
    }

    /** SlimeWanderEvent。{@code CubeMobKeepOnJumpingGoal.canUse} で vanilla が true を返す条件が揃っているとき。 */
    public static boolean slimeWander(final AbstractCubeMob cube) {
        return new com.destroystokyo.paper.event.entity.SlimeWanderEvent((org.bukkit.entity.AbstractCubeMob) cube.getBukkitEntity()).callEvent();
    }

    // ------------------------------------------------------------ 硫黄キューブ

    /** ExplosionPrimeEvent に登録が無いか。 */
    public static boolean silentExplosionPrime() {
        return !listening(org.bukkit.event.entity.ExplosionPrimeEvent.getHandlerList());
    }

    /**
     * ExplosionPrimeEvent。{@code SulfurCube.tickFuse} で導火線が尽きたとき、リードを外す前。
     * 呼ぶ側が Paper と同じ条件(ServerLevel かつ TNT_EXPLODES)を見てから呼ぶ。
     *
     * <p>効かないもの: {@code setRadius} / {@code setFire}(爆発の値は vanilla が自分の欄から読む)。
     *
     * @return 爆発してよいか。取り消されたら呼ぶ側が導火線を止める
     */
    public static boolean sulfurPrime(final SulfurCube cube, final float power, final boolean fire) {
        return !CraftEventFactory.callExplosionPrimeEvent(cube, power, fire).isCancelled();
    }

    /**
     * EntityUnleashEvent(LEASHED_GONE)。{@code SulfurCube.tickFuse} で爆発の直前にリードを外すとき。
     *
     * @return vanilla の {@code dropLeash} へ進んでよいか。落とさない指定なら外すだけにして false
     */
    public static boolean unleashOnExplode(final SulfurCube cube) {
        if (!listening(EntityUnleashEvent.getHandlerList()) || !cube.isLeashed()) {
            return true;
        }

        final EntityUnleashEvent event = new EntityUnleashEvent(cube.getBukkitEntity(), EntityUnleashEvent.UnleashReason.LEASHED_GONE, true);
        event.callEvent();

        if (event.isDropLeash()) {
            return true;
        }

        cube.removeLeash();

        return false;
    }

    /**
     * EntityIgniteEvent。{@code SulfurCube.primeTime} で導火線の長さが決まった直後。
     *
     * @return 導火線の長さ。取り消されたら {@code PrimedTnt.NO_FUSE}
     */
    public static int sulfurIgnite(final SulfurCube cube, final int fuseTime) {
        return CraftEventFactory.callEntityIgniteEvent(cube, fuseTime);
    }

    /**
     * SulfurCubeSwallowItemEvent。{@code SulfurCube.mobInteract} で持ち物を飲み込ませる直前。
     *
     * <p>効かないもの: {@code setNewItem}(飲み込む物の差し替え)。
     *
     * @return 飲み込ませてよいか
     */
    public static boolean sulfurSwallow(final SulfurCube cube, final Player player, final ItemStack held, final InteractionHand hand) {
        if (!listening(io.papermc.paper.event.entity.SulfurCubeSwallowItemEvent.getHandlerList())) {
            return true;
        }

        final io.papermc.paper.event.entity.SulfurCubeSwallowItemEvent event = new io.papermc.paper.event.entity.SulfurCubeSwallowItemEvent(
                (org.bukkit.entity.SulfurCube) cube.getBukkitEntity(), (org.bukkit.entity.Player) player.getBukkitEntity(),
                CraftItemStack.asCraftMirror(cube.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.BODY)),
                CraftItemStack.asCraftMirror(held));

        if (event.callEvent()) {
            return true;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.containerMenu.sendAllDataToRemote();
        }

        return false;
    }

    private static Vec3 knockbackReplacement;

    /**
     * EntityKnockbackEvent。{@code SulfurCube.knockback} で速度を書き換える直前。
     * 押した者と理由は Paper の {@code LivingEntity.hurtServer} と同じ取り方
     * (直接の攻撃者。いなければ DAMAGE、いれば ENTITY_ATTACK)。
     *
     * @return {@link #KEEP} / {@link #CANCEL} / {@link #REPLACE}({@link #knockbackReplacement()} で取り出す)
     */
    public static int sulfurKnockback(final SulfurCube cube, final DamageSource source, final double power,
                                      final double kx, final double ky, final double kz) {
        if (!listening(io.papermc.paper.event.entity.EntityKnockbackEvent.getHandlerList())) {
            return KEEP;
        }

        final Entity attacker = source.getDirectEntity();
        final Vec3 knockback = new Vec3(kx, ky, kz);
        final io.papermc.paper.event.entity.EntityKnockbackEvent event = CraftEventFactory.callEntityKnockbackEvent(
                (CraftLivingEntity) cube.getBukkitEntity(), attacker, attacker,
                attacker == null ? io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.DAMAGE
                        : io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.ENTITY_ATTACK,
                power, knockback);

        if (event.isCancelled()) {
            return CANCEL;
        }

        final org.bukkit.util.Vector result = event.getKnockback();

        if (result.getX() == kx && result.getY() == ky && result.getZ() == kz) {
            return KEEP;
        }

        knockbackReplacement = new Vec3(result.getX(), result.getY(), result.getZ());

        return REPLACE;
    }

    /** {@link #sulfurKnockback} が {@link #REPLACE} を返したときの、速度に足す量。 */
    public static Vec3 knockbackReplacement() {
        final Vec3 result = knockbackReplacement;
        knockbackReplacement = null;

        return result;
    }
}
