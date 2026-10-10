package tw.jiajia.autopave;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import org.lwjgl.sdl.SDLScancode;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * 進入點：註冊按鍵、指令、每 tick 的鋪設邏輯與範圍框線。
 *
 * 按鍵（可在「選項 → 控制 → 按鍵綁定 → AutoPave」改）：
 *   [  設定角落 1（準星指著的位置，也就是你按右鍵會放方塊的那一格）
 *   ]  設定角落 2（高度跟著角落 1）
 *   O  開始／停止
 *
 * 指令：/autopave pos1|pos2|square|speed|on|off|clear|status
 */
public class AutoPaveClient implements ClientModInitializer {
	public static final String MOD_ID = "autopave";

	private static KeyMapping keyPos1;
	private static KeyMapping keyPos2;
	private static KeyMapping keyToggle;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		keyPos1 = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autopave.pos1", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_LEFTBRACKET, category));
		keyPos2 = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autopave.pos2", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_RIGHTBRACKET, category));
		keyToggle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autopave.toggle", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_O, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null || client.level == null) {
				Paver.INSTANCE.stop(null);
				return;
			}
			while (keyPos1.consumeClick()) {
				BlockPos p = aimedPlacePos(client);
				if (p == null) Paver.say(client, "§c請用準星指著一個方塊");
				else Paver.INSTANCE.setPos1(client, p);
			}
			while (keyPos2.consumeClick()) {
				BlockPos p = aimedPlacePos(client);
				if (p == null) Paver.say(client, "§c請用準星指著一個方塊");
				else Paver.INSTANCE.setPos2(client, p);
			}
			while (keyToggle.consumeClick()) {
				Paver.INSTANCE.toggle(client);
			}
			Paver.INSTANCE.tick(client);
		});

		LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(AreaRenderer::render);

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal("autopave")
						.then(ClientCommands.literal("pos1")
								.executes(ctx -> {
									Minecraft mc = ctx.getSource().getClient();
									BlockPos p = aimedPlacePos(mc);
									if (p == null) p = ctx.getSource().getPlayer().blockPosition().below();
									Paver.INSTANCE.setPos1(mc, p);
									return 1;
								})
								.then(ClientCommands.argument("x", IntegerArgumentType.integer())
										.then(ClientCommands.argument("y", IntegerArgumentType.integer())
												.then(ClientCommands.argument("z", IntegerArgumentType.integer())
														.executes(ctx -> {
															Paver.INSTANCE.setPos1(ctx.getSource().getClient(), new BlockPos(
																	IntegerArgumentType.getInteger(ctx, "x"),
																	IntegerArgumentType.getInteger(ctx, "y"),
																	IntegerArgumentType.getInteger(ctx, "z")));
															return 1;
														})))))
						.then(ClientCommands.literal("pos2")
								.executes(ctx -> {
									Minecraft mc = ctx.getSource().getClient();
									BlockPos p = aimedPlacePos(mc);
									if (p == null) p = ctx.getSource().getPlayer().blockPosition().below();
									Paver.INSTANCE.setPos2(mc, p);
									return 1;
								})
								.then(ClientCommands.argument("x", IntegerArgumentType.integer())
										.then(ClientCommands.argument("z", IntegerArgumentType.integer())
												.executes(ctx -> {
													Paver.INSTANCE.setPos2(ctx.getSource().getClient(), new BlockPos(
															IntegerArgumentType.getInteger(ctx, "x"), 0,
															IntegerArgumentType.getInteger(ctx, "z")));
													return 1;
												}))))
						.then(ClientCommands.literal("square")
								.then(ClientCommands.argument("size", IntegerArgumentType.integer(1, 512))
										.executes(ctx -> {
											Paver.INSTANCE.square(ctx.getSource().getClient(), IntegerArgumentType.getInteger(ctx, "size"));
											return 1;
										})))
						.then(ClientCommands.literal("speed")
								.then(ClientCommands.argument("blocksPerSecond", IntegerArgumentType.integer(1, 20))
										.executes(ctx -> {
											Paver.INSTANCE.setSpeed(ctx.getSource().getClient(), IntegerArgumentType.getInteger(ctx, "blocksPerSecond"));
											return 1;
										})))
						.then(ClientCommands.literal("on").executes(ctx -> {
							Paver.INSTANCE.start(ctx.getSource().getClient());
							return 1;
						}))
						.then(ClientCommands.literal("off").executes(ctx -> {
							Paver.INSTANCE.stop(ctx.getSource().getClient());
							return 1;
						}))
						.then(ClientCommands.literal("clear").executes(ctx -> {
							Paver.INSTANCE.clear(ctx.getSource().getClient());
							return 1;
						}))
						.then(ClientCommands.literal("status").executes(ctx -> {
							Paver.INSTANCE.status(ctx.getSource().getClient());
							return 1;
						}))
		));
	}

	/** 準星指著方塊時，回傳「按右鍵會放到的那一格」。 */
	static BlockPos aimedPlacePos(Minecraft mc) {
		HitResult hit = mc.hitResult;
		if (!(hit instanceof BlockHitResult bhr) || hit.getType() == HitResult.Type.MISS || mc.level == null) return null;
		BlockPos pos = bhr.getBlockPos();
		if (mc.level.getBlockState(pos).canBeReplaced()) return pos;
		return pos.relative(bhr.getDirection());
	}

	static Component text(String s) {
		return Component.literal(s);
	}
}
