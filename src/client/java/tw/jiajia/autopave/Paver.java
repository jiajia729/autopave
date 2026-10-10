package tw.jiajia.autopave;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
	private int speed = 5; // 每秒放幾格
	private double budget;
	private long ticks;
	private long lastRefillTick = -100;
	private int placed;
	private int remainingCache = -1;
	private final Map<Long, Long> retryAt = new HashMap<>();
	private Target pending;
	private float savedYaw;
	private float savedPitch;

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
		say(mc, "§e放置速度：§f每秒 " + bps + " 格" + (bps > 8 ? "  §6（太快可能被反作弊擋）" : ""));
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

	/**
	 * 在玩家本身的 tick 之前：選好要放的格子，先把視線轉過去。
	 * 這樣原版這個 tick 送出的移動封包就會帶著正確的視角，伺服器（和反作弊）看到的是「看著那個面按右鍵」。
	 */
	void preTick(Minecraft mc) {
		pending = null;
		if (!active) return;
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || mc.gameMode == null) return;
		if (player.isSpectator() || mc.gui.screen() != null) return;

		budget = Math.min(budget + speed / 20.0, 1.5);
		if (budget < 1.0) return;

		Supply s = ensureItemInHand(mc, player);
		if (s == Supply.NONE) {
			say(mc, "§c背包裡的方塊用完了，補貨後再按 O 繼續（這次放了 " + placed + " 格，還差約 " + remainingCache + " 格）");
			active = false;
			return;
		}
		if (s == Supply.WAIT) return;

		Target t = findTarget(level, player);
		if (t == null) return;
		savedYaw = player.getYRot();
		savedPitch = player.getXRot();
		player.setYRot(t.yaw());
		player.setXRot(t.pitch());
		pending = t;
	}

	/** 玩家 tick 之後：用現在的位置和視角重新瞄一次，確定真的看著那個面才放，然後把視角還原。 */
	void tick(Minecraft mc) {
		ticks++;
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		Target t = pending;
		pending = null;
		if (t != null && player != null) {
			try {
				if (active && level != null && mc.gameMode != null && level.getBlockState(t.pos()).canBeReplaced()) {
					BlockHitResult aim = raycast(level, player, player.getEyePosition(), player.getLookAngle());
					if (aim != null && aim.getBlockPos().equals(t.support()) && aim.getDirection() == t.face()) {
						place(mc, player, t, aim);
						budget -= 1.0;
					}
				}
				retryAt.put(t.pos().asLong(), ticks + RETRY_TICKS);
			} finally {
				restoreView(player);
			}
		}
		if (!active || player == null || level == null) return;
		if (player.isSpectator()) {
			stop(mc);
			return;
		}

		if (ticks % 20 == 0) {
			remainingCache = countRemaining(level);
			if (remainingCache == 0) {
				say(mc, "§a完成！範圍內已經沒有空格了（這次放了 " + placed + " 格）");
				active = false;
				return;
			}
			retryAt.values().removeIf(v -> v < ticks);
		}
		if (ticks % 10 == 0) {
			player.sendOverlayMessage(Component.literal("§6自動鋪地 §f已放 " + placed + "  還差約 " + remainingCache
					+ "  背包 " + countItem(player)));
		}
	}

	/** 把視角還原成玩家自己的，連「上一幀」也一起還原，畫面才不會抖。 */
	private void restoreView(LocalPlayer player) {
		player.setYRot(savedYaw);
		player.setXRot(savedPitch);
		player.yRotO = savedYaw;
		player.xRotO = savedPitch;
	}

	private record Target(BlockPos pos, BlockPos support, Direction face, float yaw, float pitch) {
	}

	private record Cand(BlockPos pos, double d) {
	}

	/** 在伸手可及的範圍內，找最近、而且「從眼睛看得到旁邊那個方塊的面」的空格。 */
	private Target findTarget(ClientLevel level, LocalPlayer player) {
		Vec3 eye = player.getEyePosition();
		double reach = Math.min(player.blockInteractionRange(), 4.5) - REACH_MARGIN;
		int y = y();
		if (Math.abs(eye.y - (y + 0.5)) > reach + 1) return null;

		int x0 = Math.max(minX(), (int) Math.floor(eye.x - reach)), x1 = Math.min(maxX(), (int) Math.ceil(eye.x + reach));
		int z0 = Math.max(minZ(), (int) Math.floor(eye.z - reach)), z1 = Math.min(maxZ(), (int) Math.ceil(eye.z + reach));
		AABB body = player.getBoundingBox();

		List<Cand> cands = new ArrayList<>();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				BlockPos p = new BlockPos(x, y, z);
				double d = eye.distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
				if (d > (reach + 1) * (reach + 1)) continue;
				if (!level.getBlockState(p).canBeReplaced()) continue;
				Long due = retryAt.get(p.asLong());
				if (due != null && due > ticks) continue;
				if (body.intersects(new AABB(p))) continue;
				cands.add(new Cand(p, d));
			}
		}
		cands.sort(Comparator.comparingDouble(Cand::d));

		int tried = 0;
		for (Cand c : cands) {
			if (++tried > 40) break;
			for (Direction dir : SUPPORT_ORDER) {
				BlockPos n = c.pos().relative(dir);
				BlockState ns = level.getBlockState(n);
				if (ns.canBeReplaced() || ns.hasBlockEntity()) continue; // 不點箱子之類會開介面的方塊
				Direction face = dir.getOpposite();
				Vec3 fc = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
				// 面要朝向眼睛（背對你的面，真人也點不到）
				Vec3 toEye = eye.subtract(fc);
				if (toEye.x * face.getStepX() + toEye.y * face.getStepY() + toEye.z * face.getStepZ() <= 0.02) continue;
				for (Vec3 aimPt : aimPoints(fc, face)) {
					if (eye.distanceTo(aimPt) > reach) continue;
					Vec3 dirv = aimPt.subtract(eye).normalize();
					BlockHitResult h = raycast(level, player, eye, dirv);
					if (h == null || !h.getBlockPos().equals(n) || h.getDirection() != face) continue;
					float yaw = (float) (Math.toDegrees(Math.atan2(dirv.z, dirv.x)) - 90.0);
					float pitch = (float) -Math.toDegrees(Math.atan2(dirv.y, Math.sqrt(dirv.x * dirv.x + dirv.z * dirv.z)));
					float cur = player.getYRot();
					yaw = cur + Mth.wrapDegrees(yaw - cur);
					return new Target(c.pos(), n, face, yaw, Mth.clamp(pitch, -90f, 90f));
				}
			}
		}
		return null;
	}

	/** 面的中心，和往內縮一點的四個點（中心被擋住時換個地方瞄）。 */
	private static Vec3[] aimPoints(Vec3 center, Direction face) {
		Direction.Axis ax = face.getAxis();
		double k = 0.3;
		Vec3 u = ax == Direction.Axis.X ? new Vec3(0, k, 0) : new Vec3(k, 0, 0);
		Vec3 v = ax == Direction.Axis.Z ? new Vec3(0, k, 0) : new Vec3(0, 0, k);
		return new Vec3[] { center, center.add(u), center.subtract(u), center.add(v), center.subtract(v) };
	}

	/** 跟原版準星一樣，從眼睛沿著方向打一條線，回傳第一個碰到的方塊面。 */
	private static BlockHitResult raycast(ClientLevel level, LocalPlayer player, Vec3 eye, Vec3 dir) {
		Vec3 end = eye.add(dir.scale(6.0));
		BlockHitResult h = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (h == null || h.getType() != HitResult.Type.BLOCK) return null;
		return h;
	}

	private void place(Minecraft mc, LocalPlayer player, Target t, BlockHitResult bhr) {
		InteractionResult r = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bhr);
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
