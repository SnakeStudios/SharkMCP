package net.snakestudios.sharkmcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.tree.CommandNode;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class SharkMcpClient implements ClientModInitializer {
	static SharkMcpClient instance;

	private final ActionQueue actions = new ActionQueue();
	private final ArrayDeque<String> chatOut = new ArrayDeque<>();
	private final ArrayDeque<String> chatLog = new ArrayDeque<>();
	private final HeldInput held = new HeldInput();
	private volatile String lastAddress;
	private Motion motion;
	private Input savedInput;
	private HttpServer server;
	private long nextBindAttempt;
	private long lastBindLog;
	private long lastTickError;
	private boolean bindWarned;

	@Override
	public void onInitializeClient() {
		instance = this;
		String init = McpServer.respond("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
		String list = McpServer.respond("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
		if (init == null || !init.contains("\"name\":\"sharkmcp\"") || list == null || !list.contains("\"screenshot\"")) {
			throw new IllegalStateException("mcp self-check failed");
		}
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> log(message.getString()));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) log(message.getString());
		});
		ClientTickEvents.START_CLIENT_TICK.register(this::onStartTick);
		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
		tryBind();
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			actions.close();
			if (server != null) server.stop(0);
		});
	}

	private void tryBind() {
		try {
			server = McpServer.start();
			bindWarned = false;
		} catch (Exception | LinkageError e) {
			long now = System.currentTimeMillis();
			// A missing jdk.httpserver (a stripped game runtime) will not fix itself.
			nextBindAttempt = e instanceof LinkageError ? Long.MAX_VALUE : now + 10_000L;
			if (!bindWarned) {
				bindWarned = true;
				lastBindLog = now;
				McpServer.LOGGER.error("SharkMCP could not bind 127.0.0.1:{}. Is another client already using it? Retrying.", McpServer.PORT, e);
			} else if (now - lastBindLog > 60_000L) {
				lastBindLog = now;
				McpServer.LOGGER.warn("SharkMCP still could not bind 127.0.0.1:{}", McpServer.PORT);
			}
		}
	}

	String enqueue(Supplier<String> work) throws Exception {
		return actions.enqueue(work);
	}

	byte[] enqueueImage(Supplier<byte[]> work) throws Exception {
		return actions.enqueueValue(work);
	}

	String stop() {
		return actions.stop(() -> onClient(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			cancelConnecting(client);
			release();
			if (motion != null) {
				motion.done.complete("stopped");
				motion = null;
			}
			return null;
		}));
	}

	String enqueueChat(String message) {
		if (message.length() > 256) throw new IllegalArgumentException("chat message is longer than 256");
		synchronized (chatOut) {
			if (chatOut.size() >= 50) throw new IllegalStateException("chat queue full");
			chatOut.add(message);
			return "queued " + chatOut.size();
		}
	}

	private void onStartTick(MinecraftClient client) {
		try {
			if (server == null && System.currentTimeMillis() >= nextBindAttempt) tryBind();
			closeOrphanScreen(client);
			remember(client);
			if (motion != null && motion.look) stepLook(client);
			if (held.active && client.player != null) {
				if (client.player.input != held) {
					savedInput = client.player.input;
					client.player.input = held;
				}
			}
			if (motion != null && motion.holdUse && client.options != null) {
				client.options.useKey.setPressed(true);
			}
			if (motion != null && motion.attack) attackTick(client);
		} catch (Throwable t) {
			logTickError(t);
		}
	}

	private void onEndTick(MinecraftClient client) {
		try {
			closeOrphanScreen(client);
			flushChat(client);
			if (motion == null) return;
			motion.ticks--;
			if (motion.ticks <= 0) {
				Motion finished = motion;
				motion = null;
				releaseHold(client, finished);
				finished.done.complete("ok");
			}
		} catch (Throwable t) {
			logTickError(t);
		}
	}

	/**
	 * A disconnect or server transfer can null the player while an inventory or chest
	 * screen is still open. Vanilla's HandledScreen.tick() then dereferences the player
	 * and crashes the game, so drop that screen before it is ticked.
	 */
	private static void closeOrphanScreen(MinecraftClient client) {
		if (client.player == null && client.currentScreen instanceof HandledScreen<?>) {
			client.setScreen(new TitleScreen());
		}
	}

	private void logTickError(Throwable t) {
		long now = System.currentTimeMillis();
		if (now - lastTickError < 5000L) return;
		lastTickError = now;
		McpServer.LOGGER.error("SharkMCP tick failed", t);
	}

	private void flushChat(MinecraftClient client) {
		String message;
		synchronized (chatOut) {
			message = chatOut.poll();
		}
		ClientPlayNetworkHandler handler = client.getNetworkHandler();
		if (message == null || handler == null) {
			if (message != null) {
				synchronized (chatOut) {
					chatOut.addFirst(message);
				}
			}
			return;
		}
		if (message.startsWith("/")) handler.sendChatCommand(message.substring(1));
		else handler.sendChatMessage(message);
	}

	private void remember(MinecraftClient client) {
		ServerInfo info = client.getCurrentServerEntry();
		if (info != null && info.address != null && !info.address.isBlank()) lastAddress = info.address;
	}

	String state() {
		return onClient(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			JsonObject json = new JsonObject();
			ClientPlayerEntity player = client.player;
			json.addProperty("connected", player != null && client.getNetworkHandler() != null);
			json.addProperty("hudHidden", client.options.hudHidden);
			Screen screen = client.currentScreen;
			if (screen != null) json.addProperty("screen", screen.getTitle().getString());
			if (client.getCurrentServerEntry() != null) json.addProperty("server", client.getCurrentServerEntry().address);
			else if (client.isInSingleplayer()) json.addProperty("server", "singleplayer");
			if (player == null) return json.toString();
			json.addProperty("x", player.getX());
			json.addProperty("y", player.getY());
			json.addProperty("z", player.getZ());
			json.addProperty("yaw", player.getYaw());
			json.addProperty("pitch", player.getPitch());
			json.addProperty("health", player.getHealth());
			json.addProperty("maxHealth", player.getMaxHealth());
			json.addProperty("food", player.getHungerManager().getFoodLevel());
			json.addProperty("saturation", player.getHungerManager().getSaturationLevel());
			json.addProperty("air", player.getAir());
			json.addProperty("level", player.experienceLevel);
			json.addProperty("xp", player.experienceProgress);
			json.addProperty("totalXp", player.totalExperience);
			json.addProperty("onGround", player.isOnGround());
			json.addProperty("selectedSlot", player.getInventory().getSelectedSlot());
			if (client.world != null) json.addProperty("dimension", client.world.getRegistryKey().getValue().toString());
			if (client.interactionManager != null) json.addProperty("gameMode", client.interactionManager.getCurrentGameMode().getId());
			return json.toString();
		});
	}

	String inventory() {
		return onClient(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			ClientPlayerEntity player = client.player;
			if (player == null) return "{\"connected\":false}";
			PlayerInventory inventory = player.getInventory();
			JsonObject json = new JsonObject();
			json.addProperty("selectedSlot", inventory.getSelectedSlot());

			JsonArray hotbar = new JsonArray();
			JsonArray main = new JsonArray();
			List<ItemStack> stacks = inventory.getMainStacks();
			int hotbarSize = PlayerInventory.getHotbarSize();
			for (int i = 0; i < stacks.size(); i++) {
				JsonObject entry = stack(stacks.get(i), client, player);
				if (entry == null) continue;
				boolean inHotbar = i < hotbarSize;
				entry.addProperty("slot", i);
				entry.addProperty("section", inHotbar ? "hotbar" : "main");
				(inHotbar ? hotbar : main).add(entry);
			}
			json.add("hotbar", hotbar);
			json.add("main", main);

			JsonObject armor = new JsonObject();
			for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
				JsonObject entry = stack(player.getEquippedStack(slot), client, player);
				if (entry == null) continue;
				entry.addProperty("slot", slot.getName());
				entry.addProperty("section", "armor");
				armor.add(slot.getName(), entry);
			}
			json.add("armor", armor);

			JsonObject offhand = stack(player.getOffHandStack(), client, player);
			if (offhand != null) {
				offhand.addProperty("slot", EquipmentSlot.OFFHAND.getName());
				offhand.addProperty("section", "offhand");
				json.add("offhand", offhand);
			}

			ScreenHandler handler = player.currentScreenHandler;
			JsonObject cursor = stack(handler.getCursorStack(), client, player);
			if (cursor != null) {
				cursor.addProperty("section", "cursor");
				json.add("cursor", cursor);
			}

			if (client.currentScreen instanceof HandledScreen<?> screen && !(screen instanceof InventoryScreen)) {
				JsonObject open = new JsonObject();
				open.addProperty("title", screen.getTitle().getString());
				open.addProperty("syncId", handler.syncId);
				JsonArray slots = new JsonArray();
				for (Slot slot : handler.slots) {
					if (slot.inventory instanceof PlayerInventory) continue;
					JsonObject entry = stack(slot.getStack(), client, player);
					if (entry == null) continue;
					entry.addProperty("slot", slot.id);
					entry.addProperty("containerSlot", slot.getIndex());
					entry.addProperty("section", "container");
					slots.add(entry);
				}
				open.add("slots", slots);
				json.add("openContainer", open);
			}
			return json.toString();
		});
	}

	String chat(int limit) {
		int n = Math.max(1, Math.min(limit, 200));
		synchronized (chatLog) {
			JsonArray lines = new JsonArray();
			int skip = Math.max(0, chatLog.size() - n);
			int i = 0;
			for (String line : chatLog) {
				if (i++ >= skip) lines.add(line);
			}
			JsonObject json = new JsonObject();
			json.add("lines", lines);
			return json.toString();
		}
	}

	String scoreboard() {
		return onClient(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.world == null) return "{\"connected\":false}";
			Scoreboard board = client.world.getScoreboard();
			JsonArray slots = new JsonArray();
			for (ScoreboardDisplaySlot slot : ScoreboardDisplaySlot.values()) {
				ScoreboardObjective objective = board.getObjectiveForSlot(slot);
				if (objective == null) continue;
				JsonObject entry = new JsonObject();
				entry.addProperty("slot", slot.name());
				entry.addProperty("name", objective.getName());
				entry.addProperty("display", objective.getDisplayName().getString());
				JsonArray rows = new JsonArray();
				for (ScoreboardEntry row : board.getScoreboardEntries(objective)) {
					if (row.hidden()) continue;
					JsonObject line = new JsonObject();
					line.addProperty("owner", row.owner());
					line.addProperty("value", row.value());
					line.addProperty("name", row.name().getString());
					rows.add(line);
				}
				entry.add("rows", rows);
				slots.add(entry);
			}
			JsonObject json = new JsonObject();
			json.add("slots", slots);
			return json.toString();
		});
	}

	String commands() {
		return onClient(() -> {
			ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
			JsonArray list = new JsonArray();
			if (handler == null) return list.toString();
			var dispatcher = handler.getCommandDispatcher();
			for (CommandNode<net.minecraft.client.network.ClientCommandSource> node : dispatcher.getRoot().getChildren()) {
				JsonObject entry = new JsonObject();
				entry.addProperty("name", node.getName());
				list.add(entry);
			}
			return list.toString();
		});
	}

	String players() {
		return onClient(() -> {
			ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
			JsonArray list = new JsonArray();
			if (handler == null) return list.toString();
			for (PlayerListEntry entry : handler.getPlayerList()) {
				JsonObject player = new JsonObject();
				player.addProperty("name", entry.getProfile().name());
				player.addProperty("latency", entry.getLatency());
				if (entry.getGameMode() != null) player.addProperty("gameMode", entry.getGameMode().getId());
				list.add(player);
			}
			return list.toString();
		});
	}

	String crosshair() {
		return onClient(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			HitResult hit = client.crosshairTarget;
			JsonObject json = new JsonObject();
			if (hit == null) {
				json.addProperty("type", "none");
				return json.toString();
			}
			json.addProperty("type", hit.getType().name());
			json.addProperty("x", hit.getPos().x);
			json.addProperty("y", hit.getPos().y);
			json.addProperty("z", hit.getPos().z);
			if (hit instanceof BlockHitResult block && client.world != null) {
				json.addProperty("block", Registries.BLOCK.getId(client.world.getBlockState(block.getBlockPos()).getBlock()).toString());
				json.addProperty("pos", block.getBlockPos().toShortString());
				json.addProperty("side", block.getSide().name());
			} else if (hit instanceof EntityHitResult entity) {
				json.addProperty("entity", entity.getEntity().getName().getString());
				json.addProperty("entityType", Registries.ENTITY_TYPE.getId(entity.getEntity().getType()).toString());
			}
			return json.toString();
		});
	}

	String look(float yaw, float pitch, double seconds) {
		MinecraftClient client = MinecraftClient.getInstance();
		float[] now = onClient(() -> {
			ClientPlayerEntity player = client.player;
			if (player == null) return null;
			return new float[] {player.getYaw(), player.getPitch()};
		});
		if (now == null) return "not connected";
		float targetPitch = MathHelper.clamp(pitch, -90, 90);
		float yawDelta = MathHelper.wrapDegrees(yaw - now[0]);
		float pitchDelta = targetPitch - now[1];
		if (seconds < 0) seconds = Math.max(Math.abs(yawDelta), Math.abs(pitchDelta)) / 90.0;
		seconds = Math.min(seconds, 15);
		int ticks = seconds < 0.05 ? 1 : Math.max(1, (int) Math.ceil(seconds * 20));
		return awaitMotion(new Motion(ticks, yaw, targetPitch, false, false, true));
	}

	String move(boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint, double seconds) {
		seconds = Math.min(Math.max(seconds, 0.05), 30);
		int ticks = Math.max(1, (int) Math.ceil(seconds * 20));
		onClient(() -> {
			held.set(forward, back, left, right, jump, sneak, sprint);
			ClientPlayerEntity player = MinecraftClient.getInstance().player;
			if (player != null && player.input != held) {
				savedInput = player.input;
				player.input = held;
			}
			held.active = true;
			if (player != null) player.setSprinting(sprint && forward);
			return null;
		});
		String result = awaitMotion(new Motion(ticks, 0, 0, false, false, false));
		onClient(() -> {
			release();
			return null;
		});
		return result;
	}

	String attack(double seconds) {
		seconds = Math.min(Math.max(seconds, 0), 20);
		int ticks = Math.max(1, (int) Math.ceil(seconds * 20));
		return awaitMotion(new Motion(ticks, 0, 0, true, false, false));
	}

	String use(double seconds) {
		seconds = Math.min(Math.max(seconds, 0), 30);
		int ticks = Math.max(1, (int) Math.ceil(seconds * 20));
		onClient(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player != null && client.interactionManager != null) {
				clickUse(client);
			}
			return null;
		});
		String result = awaitMotion(new Motion(ticks, 0, 0, false, true, false));
		onClient(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			client.options.useKey.setPressed(false);
			if (client.player != null && client.interactionManager != null && client.player.isUsingItem()) {
				client.interactionManager.stopUsingItem(client.player);
			}
			return null;
		});
		return result;
	}

	String hotbar(int slot) {
		if (slot < 0 || slot > 8) throw new IllegalArgumentException("slot must be 0..8");
		return onClient(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player == null || client.getNetworkHandler() == null) return "not connected";
			client.player.getInventory().setSelectedSlot(slot);
			client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
			return "slot " + slot;
		});
	}

	String command(String command) {
		String stripped = command.startsWith("/") ? command.substring(1) : command;
		return onClient(() -> {
			ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
			if (handler == null) return "not connected";
			handler.sendChatCommand(stripped);
			return "sent";
		});
	}

	String connect(String address) {
		String target = address == null ? "" : address.trim();
		if (target.isEmpty()) throw new IllegalArgumentException("missing address");
		if (target.length() > 256) throw new IllegalArgumentException("address too long");
		ServerAddress parsed;
		try {
			parsed = ServerAddress.parse(target);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("invalid address: " + target);
		}

		int state = onClient(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			boolean connecting = client.currentScreen instanceof ConnectScreen;
			boolean connected = client.player != null || client.getNetworkHandler() != null || client.world != null;
			cancelConnecting(client);
			closeOrphanScreen(client);
			if (connected) client.disconnect(new TitleScreen(), false);
			return connecting ? 1 : connected ? 2 : 0;
		});
		String disconnected = waitForDisconnect(15);
		if (!"disconnected".equals(disconnected)) return disconnected;

		// Give a cancelled connector, or the proxy, a moment to release the old session.
		if (state != 0 && !sleep(500)) return "stopped";
		if (actions.cancelRequested()) return "stopped";

		onClient(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			cancelConnecting(client);
			ServerInfo info = new ServerInfo("SharkMCP", target, ServerInfo.ServerType.OTHER);
			// Same as choosing "Enabled" for server resource packs in the server list.
			info.setResourcePackPolicy(ServerInfo.ResourcePackPolicy.ENABLED);
			ConnectScreen.connect(new TitleScreen(), client, parsed, info, false, null);
			return null;
		});
		String result = waitForConnect(60);
		if ("connected".equals(result)) lastAddress = target;
		return result;
	}

	String disconnect() {
		boolean active = onClient(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			return client.player != null || client.getNetworkHandler() != null || client.world != null
					|| client.currentScreen instanceof ConnectScreen;
		});
		if (!active) return "not connected";
		onClient(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			cancelConnecting(client);
			closeOrphanScreen(client);
			if (client.player != null || client.getNetworkHandler() != null || client.world != null) {
				client.disconnect(new TitleScreen(), false);
			}
			return null;
		});
		return waitForDisconnect(20);
	}

	String reconnect() {
		String address = onClient(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			ServerInfo info = client.getCurrentServerEntry();
			if (info != null && info.address != null && !info.address.isBlank()) return info.address;
			return lastAddress;
		});
		if (address == null || address.isBlank()) return "no server to reconnect to";
		return connect(address);
	}

	String closeScreen() {
		return onClient(() -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.currentScreen == null) return "no screen";
			if (client.player == null) client.setScreen(new TitleScreen());
			else client.setScreen(null);
			return "closed";
		});
	}

	String setHud(boolean hidden) {
		return onClient(() -> {
			MinecraftClient.getInstance().options.hudHidden = hidden;
			return hidden ? "hidden" : "shown";
		});
	}

	String waitSeconds(double seconds) {
		seconds = Math.min(Math.max(seconds, 0), 5);
		int ticks = Math.max(1, (int) Math.ceil(seconds * 20));
		return awaitMotion(new Motion(ticks, 0, 0, false, false, false));
	}

	byte[] screenshot(boolean hideHud) {
		boolean[] previous = new boolean[1];
		if (hideHud) {
			onClient(() -> {
				previous[0] = MinecraftClient.getInstance().options.hudHidden;
				MinecraftClient.getInstance().options.hudHidden = true;
				return null;
			});
			awaitMotion(new Motion(2, 0, 0, false, false, false));
		}
		try {
			return capture();
		} finally {
			if (hideHud) {
				onClient(() -> {
					MinecraftClient.getInstance().options.hudHidden = previous[0];
					return null;
				});
			}
		}
	}

	private byte[] capture() {
		CompletableFuture<byte[]> done = new CompletableFuture<>();
		MinecraftClient.getInstance().execute(() -> {
			try {
				ScreenshotRecorder.takeScreenshot(MinecraftClient.getInstance().getFramebuffer(), image -> {
					try {
						done.complete(toJpeg(image));
					} catch (Throwable t) {
						done.completeExceptionally(t);
					} finally {
						image.close();
					}
				});
			} catch (Throwable t) {
				done.completeExceptionally(t);
			}
		});
		try {
			return done.get(30, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException("screenshot failed: " + e.getMessage(), e);
		}
	}

	private static byte[] toJpeg(NativeImage image) throws Exception {
		return Frames.toJpeg(image.getWidth(), image.getHeight(), image::getColorArgb);
	}

	private void stepLook(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || motion == null) return;
		int left = Math.max(motion.ticks, 1);
		float yawDelta = MathHelper.wrapDegrees(motion.yaw - player.getYaw());
		float pitchDelta = motion.pitch - player.getPitch();
		player.setYaw(player.getYaw() + yawDelta / left);
		player.setPitch(MathHelper.clamp(player.getPitch() + pitchDelta / left, -90, 90));
	}

	private void attackTick(MinecraftClient client) {
		if (client.player == null || client.interactionManager == null || client.crosshairTarget == null) return;
		HitResult hit = client.crosshairTarget;
		if (hit.getType() == HitResult.Type.MISS) return;
		if (hit instanceof EntityHitResult entity) {
			if (motion != null && motion.ticks == motion.total) {
				client.interactionManager.attackEntity(client.player, entity.getEntity());
			}
		} else if (hit instanceof BlockHitResult block) {
			if (motion != null && motion.ticks == motion.total) {
				client.interactionManager.attackBlock(block.getBlockPos(), block.getSide());
			} else {
				client.interactionManager.updateBlockBreakingProgress(block.getBlockPos(), block.getSide());
			}
		}
	}

	private static void clickUse(MinecraftClient client) {
		HitResult hit = client.crosshairTarget;
		if (hit instanceof EntityHitResult entity && hit.getType() != HitResult.Type.MISS) {
			client.interactionManager.interactEntityAtLocation(client.player, entity.getEntity(), entity, Hand.MAIN_HAND);
		} else if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, block);
		} else {
			client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
		}
	}

	private void releaseHold(MinecraftClient client, Motion finished) {
		if (finished.attack && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
		if (finished.holdUse) {
			client.options.useKey.setPressed(false);
			if (client.player != null && client.interactionManager != null && client.player.isUsingItem()) {
				client.interactionManager.stopUsingItem(client.player);
			}
		}
	}

	private void release() {
		held.clear();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null && savedInput != null && client.player.input == held) {
			client.player.input = savedInput;
			client.player.setSprinting(false);
		}
		savedInput = null;
		if (client.options != null) client.options.useKey.setPressed(false);
	}

	private String awaitMotion(Motion next) {
		CompletableFuture<String> done = next.done;
		onClient(() -> {
			if (motion != null) motion.done.complete("replaced");
			motion = next;
			return null;
		});
		try {
			while (!done.isDone()) {
				if (actions.cancelRequested()) {
					onClient(() -> {
						release();
						if (motion == next) {
							releaseHold(MinecraftClient.getInstance(), motion);
							motion = null;
						}
						return null;
					});
					if (!done.isDone()) done.complete("stopped");
					return "stopped";
				}
				Thread.sleep(20);
			}
			return done.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	private String waitForDisconnect(int timeoutSeconds) {
		long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
		while (true) {
			if (actions.cancelRequested()) return "stopped";
			boolean done = onClient(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				return client.player == null && client.getNetworkHandler() == null && client.world == null;
			});
			if (done) return "disconnected";
			if (System.currentTimeMillis() >= deadline) return "disconnect timed out";
			if (!sleep(50)) return "stopped";
		}
	}

	private String waitForConnect(int timeoutSeconds) {
		long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
		while (true) {
			if (actions.cancelRequested()) return "stopped";
			String status = onClient(() -> {
				MinecraftClient client = MinecraftClient.getInstance();
				if (client.player != null && client.getNetworkHandler() != null && client.world != null) {
					return "connected";
				}
				if (client.currentScreen instanceof DisconnectedScreen) return "connect failed";
				return null;
			});
			if (status != null) return status;
			if (System.currentTimeMillis() >= deadline) return "connect timed out";
			if (!sleep(100)) return "stopped";
		}
	}

	private static void cancelConnecting(MinecraftClient client) {
		if (!(client.currentScreen instanceof ConnectScreen screen)) return;
		for (var element : screen.children()) {
			if (element instanceof ButtonWidget button
					&& ScreenTexts.CANCEL.getString().equals(button.getMessage().getString())) {
				button.onPress(new AbstractInput() {
					@Override
					public int getKeycode() {
						return 0;
					}

					@Override
					public int modifiers() {
						return 0;
					}
				});
				return;
			}
		}
		client.setScreen(new TitleScreen());
	}

	private static boolean sleep(long millis) {
		try {
			Thread.sleep(millis);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void log(String text) {
		synchronized (chatLog) {
			chatLog.add(text);
			while (chatLog.size() > 200) chatLog.removeFirst();
		}
	}

	private static <T> T onClient(Supplier<T> supplier) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.isOnThread()) return supplier.get();
		try {
			return client.submit(supplier::get).get(60, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	private static JsonObject stack(ItemStack stack, MinecraftClient client, ClientPlayerEntity player) {
		if (stack == null || stack.isEmpty()) return null;
		JsonObject json = new JsonObject();
		json.addProperty("id", Registries.ITEM.getId(stack.getItem()).toString());
		json.addProperty("count", stack.getCount());
		json.addProperty("name", stack.getName().getString());
		Text customName = stack.getCustomName();
		if (customName != null) json.addProperty("customName", customName.getString());
		json.addProperty("itemName", stack.getItemName().getString());
		json.addProperty("maxCount", stack.getMaxCount());
		json.addProperty("rarity", stack.getRarity().asString());
		JsonArray tags = new JsonArray();
		stack.streamTags().forEach(tag -> tags.add(tag.id().toString()));
		if (!tags.isEmpty()) json.add("tags", tags);
		if (stack.isDamageable()) {
			json.addProperty("damage", stack.getDamage());
			json.addProperty("maxDamage", stack.getMaxDamage());
			json.addProperty("durability", stack.getMaxDamage() - stack.getDamage());
		}
		if (stack.contains(DataComponentTypes.UNBREAKABLE)) json.addProperty("unbreakable", true);
		if (stack.hasEnchantments()) json.add("enchantments", enchantments(stack.getEnchantments()));

		ItemEnchantmentsComponent stored = stack.get(DataComponentTypes.STORED_ENCHANTMENTS);
		if (stored != null && !stored.isEmpty()) json.add("storedEnchantments", enchantments(stored));

		LoreComponent lore = stack.get(DataComponentTypes.LORE);
		if (lore != null && !lore.lines().isEmpty()) {
			JsonArray lines = new JsonArray();
			for (Text line : lore.lines()) lines.add(line.getString());
			json.add("lore", lines);
		}

		AttributeModifiersComponent attributes = stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
		if (attributes != null && !attributes.modifiers().isEmpty()) {
			JsonArray list = new JsonArray();
			for (AttributeModifiersComponent.Entry entry : attributes.modifiers()) {
				JsonObject modifier = new JsonObject();
				modifier.addProperty("attribute", entry.attribute().getIdAsString());
				modifier.addProperty("id", entry.modifier().id().toString());
				modifier.addProperty("value", entry.modifier().value());
				modifier.addProperty("operation", entry.modifier().operation().asString());
				modifier.addProperty("slot", entry.slot().asString());
				list.add(modifier);
			}
			json.add("attributes", list);
		}

		NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (customData != null && !customData.isEmpty()) {
			json.addProperty("customData", customData.copyNbt().toString());
		}

		try {
			Item.TooltipContext context = client.world == null
					? Item.TooltipContext.DEFAULT : Item.TooltipContext.create(client.world);
			JsonArray tooltip = new JsonArray();
			for (Text line : stack.getTooltip(context, player, TooltipType.BASIC)) tooltip.add(line.getString());
			json.add("tooltip", tooltip);
		} catch (RuntimeException ignored) {
			// A broken component on one item must not take the whole inventory down.
		}

		String nbt = nbt(stack, client);
		if (nbt != null) json.addProperty("nbt", nbt);
		return json;
	}

	private static JsonArray enchantments(ItemEnchantmentsComponent component) {
		JsonArray list = new JsonArray();
		for (var entry : component.getEnchantmentEntries()) {
			JsonObject enchantment = new JsonObject();
			enchantment.addProperty("id", entry.getKey().getIdAsString());
			enchantment.addProperty("level", entry.getIntValue());
			list.add(enchantment);
		}
		return list;
	}

	private static String nbt(ItemStack stack, MinecraftClient client) {
		try {
			DynamicRegistryManager registries = client.getNetworkHandler() != null
					? client.getNetworkHandler().getRegistryManager()
					: client.world != null ? client.world.getRegistryManager() : DynamicRegistryManager.EMPTY;
			RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, registries);
			return ItemStack.CODEC.encodeStart(ops, stack).result().map(Object::toString).orElse(null);
		} catch (RuntimeException e) {
			return stack.getComponentChanges().toString();
		}
	}

	private static final class Motion {
		final int total;
		int ticks;
		final float yaw;
		final float pitch;
		final boolean look;
		final boolean attack;
		final boolean holdUse;
		final CompletableFuture<String> done = new CompletableFuture<>();

		Motion(int ticks, float yaw, float pitch, boolean attack, boolean holdUse, boolean look) {
			this.total = ticks;
			this.ticks = ticks;
			this.yaw = yaw;
			this.pitch = pitch;
			this.look = look;
			this.attack = attack;
			this.holdUse = holdUse;
		}
	}

	private static final class HeldInput extends Input {
		volatile boolean active;

		void set(boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint) {
			this.playerInput = new PlayerInput(forward, back, left, right, jump, sneak, sprint);
			this.movementVector = new Vec2f(axis(left, right), axis(forward, back));
		}

		void clear() {
			active = false;
			playerInput = PlayerInput.DEFAULT;
			movementVector = Vec2f.ZERO;
		}

		@Override
		public void tick() {
			if (!active) return;
			PlayerInput input = this.playerInput;
			this.movementVector = new Vec2f(axis(input.left(), input.right()), axis(input.forward(), input.backward()));
		}

		@Override
		public void jump() {
			PlayerInput input = this.playerInput;
			set(input.forward(), input.backward(), input.left(), input.right(), true, input.sneak(), input.sprint());
		}

		private static float axis(boolean positive, boolean negative) {
			if (positive == negative) return 0;
			return positive ? 1f : -1f;
		}
	}
}
