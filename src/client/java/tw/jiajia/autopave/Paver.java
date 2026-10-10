package tw.jiajia.autopave;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 鋪設邏輯。只做一般玩家手動也做得到的事：
 * 只放在伺服器允許的距離內、每一格都貼著已存在的方塊、從自己的背包拿方塊、限制每秒放置數。
 * 不會自動移動玩家，人要自己在範圍裡走動或飛行。
 */
public final class Paver {
	public static final Paver INSTANCE = new Paver();

	/** 要貼著放的方向順序：先往下貼地，再貼旁邊已鋪好的，最後才往上。 */
	private static final Direction[] SUPPORT_ORDER = {
			Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
	};
	/** 和原版伺服器的距離檢查比，再保守一點。 */
	private static final double REACH_MARGIN = 0.4;
	/** 放過的格子多久之後才重試（tick）；被伺服器拒絕時避免一直狂送封包。 */
	private static final int RETRY_TICKS = 30;

	BlockPos pos1;
	BlockPos pos2;
	private boolean active;
	private Item item;
	private int preferredSlot;
	private int speed = 8; // 每秒放幾格
	private double budget;
	private long ticks;
	private long lastRefillTick = -100;
	private int placed;
	private int remainingCache = -1;
	private final Map<Long, Long> retryAt = new HashMap<>();

	private Paver() {
	}

	// ───────────────────────── 範圍設定 ─────────────────────────

	void setPos1(Minecraft mc, BlockPos p) {
		pos1 = p.immutable();
		say(mc, "§e角落 1：§f" + fmt(pos1) + "  §7（鋪設高度 y = " + pos1.getY() + "）");
		describeArea(mc);
	}

	void setPos2(Minecraft mc, BlockPos p) {
		pos2 = p.immutable();
		say(mc, "§e角落 2：§f" + pos2.getX() + ", " + pos2.getZ() + "  §7（高度跟著角落 1）");
		describeArea(mc);
	}

	/** 從角落 1 往你面向的方向、和你的右手邊各延伸 size 格。 */
	void square(Minecraft mc, int size) {
		if (pos1 == null) {
			say(mc, "§c請先設定角落 1（按 [ 或 /autopave pos1）");
			return;
		}
		Direction forward = mc.player.getDirection();
		Direction right = forward.getClockWise();
		pos2 = pos1.relative(forward, size - 1).relative(right, size - 1);
		say(mc, "§e往前（" + forward.getName() + "）和右手邊各延伸 " + size + " 格");
		describeArea(mc);
	}

	void setSpeed(Minecraft mc, int bps) {
		speed = bps;
		say(mc, "§e放置速度：§f每秒 " + bps + " 格" + (bps > 10 ? "  §6（太快可能被反作弊踢）" : ""));
	}

	void clear(Minecraft mc) {
		stop(null);
		pos1 = null;
		pos2 = null;
		say(mc, "§7已清除範圍");
	}

	boolean hasArea() {
		return pos1 != null && pos2 != null;
	}

	int minX() { return Math.min(pos1.getX(), pos2.getX()); }
	int maxX() { return Math.max(pos1.getX(), pos2.getX()); }
	int minZ() { return Math.min(pos1.getZ(), pos2.getZ()); }
	int maxZ() { return Math.max(pos1.getZ(), pos2.getZ()); }
	int y() { return pos1.getY(); }

	private void describeArea(Minecraft mc) {
		if (!hasArea()) return;
		int w = maxX() - minX() + 1, l = maxZ() - minZ() + 1;
		say(mc, "§a範圍 " + w + " × " + l + " = " + (w * l) + " 格  §7按 O 開始");
	}

	// ───────────────────────── 開關 ─────────────────────────

	void toggle(Minecraft mc) {
		if (active) stop(mc);
		else start(mc);
	}

	void start(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) return;
		if (!hasArea()) {
			say(mc, "§c還沒設定範圍：用 [ 和 ] 設定兩個角落");
			return;
		}
		ItemStack hand = player.getMainHandItem();
		if (!(hand.getItem() instanceof BlockItem)) {
			say(mc, "§c請把要鋪的方塊（例如鵝卵石）拿在主手再開始");
			return;
		}
		item = hand.getItem();
		preferredSlot = player.getInventory().getSelectedSlot();
		active = true;
		budget = 0;
		placed = 0;
		retryAt.clear();
		remainingCache = countRemaining(mc.level);
		say(mc, "§a開始鋪設 " + hand.getHoverName().getString() + "：還差 " + remainingCache + " 格，背包有 " + countItem(player) + " 個");
	}

	void stop(Minecraft mc) {
		if (!active) return;
		active = false;
		if (mc != null && mc.player != null) say(mc, "§7已停止（這次放了 " + placed + " 格）");
	}

	void status(Minecraft mc) {
		if (!hasArea()) {
			say(mc, "§7尚未設定範圍");
			return;
		}
		int w = maxX() - minX() + 1, l = maxZ() - minZ() + 1;
		say(mc, "§e範圍 " + w + " × " + l + "，y = " + y() + "；還差 " + countRemaining(mc.level)
				+ " 格（只算已載入的區塊）；速度每秒 " + speed + " 格；" + (active ? "§a鋪設中" : "§7停止中"));
	}

	// ───────────────────────── 每 tick ─────────────────────────

	void tick(Minecraft mc) {
		ticks++;
		if (!active) return;
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || mc.gameMode == null) return;
		if (player.isSpectator()) {
			stop(mc);
			return;
		}
		if (mc.gui.screen() != null) return; // 開著背包或聊天室時暫停

		if (ticks % 20 == 0) {
			remainingCache = countRemaining(level);
			if (remainingCache == 0) {
				say(mc, "§a完成！範圍內已經沒有空格了（這次放了 " + placed + " 格）");
				active = false;
				return;
			}
			retryAt.values().removeIf(t -> t < ticks);
		}

		budget = Math.min(budget + speed / 20.0, Math.max(1.0, speed / 20.0) + 1.0);
		while (budget >= 1.0) {
			Supply s = ensureItemInHand(mc, player);
			if (s == Supply.NONE) {
				say(mc, "§c背包裡的方塊用完了，補貨後再按 O 繼續（這次放了 " + placed + " 格，還差約 " + remainingCache + " 格）");
				active = false;
				return;
			}
			if (s == Supply.WAIT) return;

			Target t = findTarget(level, player);
			if (t == null) break;
			place(mc, player, t);
			budget -= 1.0;
		}
		if (ticks % 10 == 0) {
			player.sendOverlayMessage(Component.literal("§6自動鋪地 §f已放 " + placed + "  還差約 " + remainingCache
					+ "  背包 " + countItem(player)));
		}
	}

	private record Target(BlockPos pos, BlockPos support, Direction face) {
	}

	/** 在伸手可及的範圍內，找最近、而且旁邊有方塊可以貼的空格。 */
	private Target findTarget(ClientLevel level, LocalPlayer player) {
		Vec3 eye = player.getEyePosition();
		double reach = Math.min(player.blockInteractionRange(), 4.5) - REACH_MARGIN;
		int y = y();
		if (Math.abs(eye.y - (y + 0.5)) > reach) return null;

		int x0 = Math.max(minX(), (int) Math.floor(eye.x - reach)), x1 = Math.min(maxX(), (int) Math.ceil(eye.x + reach));
		int z0 = Math.max(minZ(), (int) Math.floor(eye.z - reach)), z1 = Math.min(maxZ(), (int) Math.ceil(eye.z + reach));
		AABB body = player.getBoundingBox();

		Target best = null;
		double bestD = Double.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				m.set(x, y, z);
				double d = eye.distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
				if (d > reach * reach || d >= bestD) continue;
				if (!level.getBlockState(m).canBeReplaced()) continue;
				Long due = retryAt.get(m.asLong());
				if (due != null && due > ticks) continue;
				if (body.intersects(new AABB(m))) continue;
				for (Direction dir : SUPPORT_ORDER) {
					BlockPos n = m.relative(dir);
					BlockState ns = level.getBlockState(n);
					if (ns.canBeReplaced() || ns.hasBlockEntity()) continue; // 不點箱子之類會開介面的方塊
					best = new Target(m.immutable(), n, dir.getOpposite());
					bestD = d;
					break;
				}
			}
		}
		return best;
	}

	private void place(Minecraft mc, LocalPlayer player, Target t) {
		Direction f = t.face();
		Vec3 hit = Vec3.atCenterOf(t.support()).add(f.getStepX() * 0.5, f.getStepY() * 0.5, f.getStepZ() * 0.5);
		BlockHitResult bhr = new BlockHitResult(hit, f, t.support(), false);
		InteractionResult r = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bhr);
		retryAt.put(t.pos().asLong(), ticks + RETRY_TICKS);
		if (r instanceof InteractionResult.Success ok && ok.swingSource() == InteractionResult.SwingSource.PREDICTED) {
			player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false); // 跟原版右鍵一樣揮手
		}
		if (r.consumesAction()) {
			placed++;
			if (remainingCache > 0) remainingCache--;
		}
	}

	// ───────────────────────── 背包 ─────────────────────────

	private enum Supply { HAVE, WAIT, NONE }

	/** 確保主手拿著要鋪的方塊：先找快捷欄，沒有就從背包換一組到原本那格。 */
	private Supply ensureItemInHand(Minecraft mc, LocalPlayer player) {
		Inventory inv = player.getInventory();
		if (inv.getItem(inv.getSelectedSlot()).is(item)) return Supply.HAVE;
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).is(item)) {
				inv.setSelectedSlot(i);
				return Supply.HAVE;
			}
		}
		for (int i = 9; i < 36; i++) {
			if (inv.getItem(i).is(item)) {
				if (ticks - lastRefillTick < 4) return Supply.WAIT;
				// 背包選單裡，背包 9–35 格的編號剛好也是 9–35；按鍵 0–8 = 換到快捷欄第幾格
				mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, i, preferredSlot, ContainerInput.SWAP, player);
				inv.setSelectedSlot(preferredSlot);
				lastRefillTick = ticks;
				return Supply.WAIT;
			}
		}
		return Supply.NONE;
	}

	private int countItem(LocalPlayer player) {
		if (item == null) return 0;
		Inventory inv = player.getInventory();
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(item)) n += s.getCount();
		}
		return n;
	}

	private int countRemaining(ClientLevel level) {
		if (level == null || !hasArea()) return 0;
		int n = 0;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int x = minX(); x <= maxX(); x++) {
			for (int z = minZ(); z <= maxZ(); z++) {
				m.set(x, y(), z);
				if (!level.hasChunkAt(m)) continue;
				if (level.getBlockState(m).canBeReplaced()) n++;
			}
		}
		return n;
	}

	// ───────────────────────── 小工具 ─────────────────────────

	static void say(Minecraft mc, String s) {
		if (mc != null && mc.player != null) mc.player.sendSystemMessage(Component.literal(s));
	}

	private static String fmt(BlockPos p) {
		return p.getX() + ", " + p.getY() + ", " + p.getZ();
	}

	boolean isActive() {
		return active;
	}
}
