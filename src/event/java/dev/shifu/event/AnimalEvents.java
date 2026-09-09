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
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Turtle;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
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


    // ------------------------------------------------------------ 座る


    // ------------------------------------------------------------ 飼われている動物



    // ------------------------------------------------------------ 村人




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
     * (Sheep / MushroomCow / SnowGolem / Bogged)。
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


    // ------------------------------------------------------------ 個別の動物






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


    /** PigZombieAngerEvent に登録が無いか。 */
    public static boolean silentPigZombieAnger() {
        return !listening(org.bukkit.event.entity.PigZombieAngerEvent.getHandlerList());
    }


    /**
     * RaidTriggerEvent に登録があるか。{@code Raids.createOrExtendRaid} が、この呼び出しで
     * 襲撃を登録したかどうかを控えるのに使う。
     */
    public static boolean raidTriggerListening() {
        return listening(org.bukkit.event.raid.RaidTriggerEvent.getHandlerList());
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


    private static Slime splitting;
    private static List<LivingEntity> splitCubes;

    /** 分裂の前。分裂で出来る個体を {@link #splitCube} で集め始める。 */
    public static void splitBegin(final Slime cube) {
        if (!listening(org.bukkit.event.entity.EntityTransformEvent.getHandlerList())) {
            return;
        }

        splitting = cube;
        splitCubes = new ArrayList<>();
    }

    /** {@code setUpSplitCube} の末尾。convertTo が世界に置く前に呼ばれる。 */
    public static void splitCube(final Slime cube, final Slime piece) {
        if (splitting == cube) {
            splitCubes.add(piece);
        }
    }

    /**
     * EntityTransformEvent(SPLIT)。分裂の直後。Paper は世界に置く前に発火するが、Shifu の
     * {@code convertTo} は中で置くので、置いたあとに発火して、取り消されたら全部消す。
     * 同じ tick の中なので、クライアントには出現と消滅が一緒に届く。
     */
    public static void splitEnd(final Slime cube) {
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


    /** SlimeSwimEvent に登録があるか。 */
    public static boolean slimeSwimListening() {
        return listening(com.destroystokyo.paper.event.entity.SlimeSwimEvent.getHandlerList());
    }


    /** SlimeWanderEvent に登録があるか。 */
    public static boolean slimeWanderListening() {
        return listening(com.destroystokyo.paper.event.entity.SlimeWanderEvent.getHandlerList());
    }


    /**
     * SlimeSplitEvent。{@code Slime.remove} で分裂する直前。
     *
     * @return 分裂する数。取り消されたら 0(分裂しない)
     */
    public static int slimeSplit(final net.minecraft.world.entity.monster.Slime slime, final int count) {
        if (!listening(org.bukkit.event.entity.SlimeSplitEvent.getHandlerList())) {
            return count;
        }

        final org.bukkit.event.entity.SlimeSplitEvent event = new org.bukkit.event.entity.SlimeSplitEvent(
                (org.bukkit.entity.Slime) slime.getBukkitEntity(), count);

        return event.callEvent() && event.getCount() > 0 ? event.getCount() : 0;
    }

    /**
     * VillagerReplenishTradeEvent。取引の使用回数を戻す直前(取引ごと)。
     *
     * @return 戻してよいか
     */
    public static boolean replenishTrade(final net.minecraft.world.entity.npc.Villager villager,
                                         final net.minecraft.world.item.trading.MerchantOffer offer) {
        if (!listening(org.bukkit.event.entity.VillagerReplenishTradeEvent.getHandlerList())) {
            return true;
        }

        return new org.bukkit.event.entity.VillagerReplenishTradeEvent(
                (org.bukkit.entity.Villager) villager.getBukkitEntity(), offer.asBukkit()).callEvent();
    }

    /**
     * EntityToggleSitEvent。座り(立ち)を書き換える先頭。Paper と同じ位置。
     *
     * @return 書き換えてよいか
     */
    public static boolean toggleSit(final net.minecraft.world.entity.Entity entity, final boolean sitting) {
        if (!listening(io.papermc.paper.event.entity.EntityToggleSitEvent.getHandlerList())) {
            return true;
        }

        return new io.papermc.paper.event.entity.EntityToggleSitEvent(entity.getBukkitEntity(), sitting).callEvent();
    }

    /**
     * PigZapEvent。{@code Pig.thunderHit} で変換に入る直前。
     *
     * <p>イベントに載せる ZombifiedPiglin は vanilla が作ったものをそのまま渡す。
     *
     * @return 変換してよいか
     */
    public static boolean pigZap(final net.minecraft.world.entity.animal.Pig pig,
                                 final net.minecraft.world.entity.LightningBolt bolt,
                                 final net.minecraft.world.entity.monster.ZombifiedPiglin piglin) {
        if (!listening(org.bukkit.event.entity.PigZapEvent.getHandlerList())) {
            return true;
        }

        return !CraftEventFactory.callPigZapEvent(pig, bolt, piglin).isCancelled();
    }

}
