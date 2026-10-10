package tw.jiajia.autopave;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/** 用半透明的細長方塊把範圍框起來（琥珀色＝停止，綠色＝鋪設中），角落 1 另外標一個小方塊。 */
final class AreaRenderer {
	private static final double T = 0.06; // 框線粗細

	private AreaRenderer() {
	}

	static void render(LevelRenderContext context) {
		Paver p = Paver.INSTANCE;
		if (p.pos1 == null) return;
		Vec3 cam = context.levelState().cameraRenderState.pos;
		PoseStack pose = context.poseStack();
		pose.pushPose();
		pose.translate(-cam.x, -cam.y, -cam.z);

		int color = p.isActive() ? ARGB.colorFromFloat(0.55f, 0.3f, 1.0f, 0.4f) : ARGB.colorFromFloat(0.55f, 1.0f, 0.72f, 0.2f);
		int corner = ARGB.colorFromFloat(0.5f, 1.0f, 0.9f, 0.3f);
		double y0 = p.pos1.getY() - 0.01, y1 = p.pos1.getY() + 1.01;

		context.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.debugFilledBox(), (pp, buf) -> {
			double cx = p.pos1.getX(), cz = p.pos1.getZ();
			box(pp, buf, new AABB(cx + 0.3, y1, cz + 0.3, cx + 0.7, y1 + 0.4, cz + 0.7), corner);
			if (!p.hasArea()) return;
			double x0 = p.minX(), x1 = p.maxX() + 1, z0 = p.minZ(), z1 = p.maxZ() + 1;
			box(pp, buf, new AABB(x0 - T, y0, z0 - T, x1 + T, y1, z0 + T), color);
			box(pp, buf, new AABB(x0 - T, y0, z1 - T, x1 + T, y1, z1 + T), color);
			box(pp, buf, new AABB(x0 - T, y0, z0 - T, x0 + T, y1, z1 + T), color);
			box(pp, buf, new AABB(x1 - T, y0, z0 - T, x1 + T, y1, z1 + T), color);
		});
		pose.popPose();
	}

	private static void box(PoseStack.Pose pose, VertexConsumer v, AABB b, int c) {
		float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ;
		float x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
		quad(pose, v, c, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
		quad(pose, v, c, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1);
		quad(pose, v, c, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1);
		quad(pose, v, c, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
		quad(pose, v, c, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
		quad(pose, v, c, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer v, int c,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz) {
		v.addVertex(pose, ax, ay, az).setColor(c);
		v.addVertex(pose, bx, by, bz).setColor(c);
		v.addVertex(pose, cx, cy, cz).setColor(c);
		v.addVertex(pose, dx, dy, dz).setColor(c);
	}
}
