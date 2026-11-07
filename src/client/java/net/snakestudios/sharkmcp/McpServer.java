package net.snakestudios.sharkmcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.Executors;

/** JSON-RPC MCP on 127.0.0.1:8765. POST /mcp, one JSON response, no SSE. */
public final class McpServer {
	static final Logger LOGGER = LoggerFactory.getLogger("sharkmcp");
	static final int PORT = 8765;
	static final Set<String> TOOLS = Set.of(
			"get_state", "get_inventory", "get_chat", "get_scoreboard", "list_commands", "get_players",
			"get_crosshair", "look", "move", "attack", "use", "hotbar", "chat", "command", "connect",
			"disconnect", "reconnect", "close_screen", "set_hud", "screenshot", "wait", "stop");

	private McpServer() {}

	static HttpServer start() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
		server.createContext("/mcp", McpServer::handle);
		server.setExecutor(Executors.newFixedThreadPool(4, r -> {
			Thread thread = new Thread(r, "sharkmcp-http");
			thread.setDaemon(true);
			return thread;
		}));
		server.start();
		LOGGER.info("SharkMCP listening on http://127.0.0.1:{}/mcp", PORT);
		return server;
	}

	private static void handle(HttpExchange exchange) throws IOException {
		if (!"POST".equals(exchange.getRequestMethod())) {
			write(exchange, 405, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"POST only\"}}");
			return;
		}
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		String response = respond(body);
		if (response == null) {
			exchange.sendResponseHeaders(202, -1);
			exchange.close();
			return;
		}
		write(exchange, 200, response);
	}

	private static void write(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	/** @return null for a notification (no body) */
	static String respond(String body) {
		JsonObject request;
		try {
			request = JsonParser.parseString(body).getAsJsonObject();
		} catch (RuntimeException e) {
			return error(null, -32700, "parse error");
		}
		JsonElement idElement = request.get("id");
		boolean notification = !request.has("id");
		String method = request.has("method") ? request.get("method").getAsString() : "";
		JsonObject params = request.has("params") && request.get("params").isJsonObject()
				? request.getAsJsonObject("params") : new JsonObject();
		try {
			JsonObject result = switch (method) {
				case "initialize" -> initialize(params);
				case "notifications/initialized", "notifications/cancelled" -> null;
				case "ping" -> new JsonObject();
				case "tools/list" -> tools();
				case "tools/call" -> call(params);
				default -> null;
			};
			if ("notifications/initialized".equals(method) || "notifications/cancelled".equals(method)) return null;
			if (result == null) {
				if (notification) return null;
				return error(idElement, -32601, "method not found: " + method);
			}
			if (notification) return null;
			JsonObject response = new JsonObject();
			response.addProperty("jsonrpc", "2.0");
			response.add("id", idElement);
			response.add("result", result);
			return response.toString();
		} catch (Exception e) {
			if (notification) return null;
			return error(idElement, -32603, e.getMessage() == null ? e.toString() : e.getMessage());
		}
	}

	private static JsonObject initialize(JsonObject params) {
		JsonObject result = new JsonObject();
		String protocol = "2024-11-05";
		if (params.has("protocolVersion")) protocol = params.get("protocolVersion").getAsString();
		result.addProperty("protocolVersion", protocol);
		JsonObject capabilities = new JsonObject();
		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		capabilities.add("tools", tools);
		result.add("capabilities", capabilities);
		JsonObject info = new JsonObject();
		info.addProperty("name", "sharkmcp");
		info.addProperty("version", "1.0.1");
		result.add("serverInfo", info);
		result.addProperty("instructions",
				"World actions run one at a time. chat is a separate queue and does not wait. "
						+ "look moves the camera smoothly. screenshot is at most 1280x720 jpeg. "
						+ "move uses WASD booleans for a duration. Commands omit the leading slash.");
		return result;
	}

	private static JsonObject tools() {
		JsonArray tools = new JsonArray();
		tool(tools, "get_state", "Position, look, health, hunger, xp, server, whether the HUD is hidden.", obj());
		tool(tools, "get_inventory", "Every inventory slot: hotbar, main, armor, offhand, cursor and any open container. Each item includes its id, count, names, lore, enchantments, attributes, tags, durability, tooltip and full NBT/components.", obj());
		JsonObject chat = obj();
		chat.add("limit", num("How many recent lines.", 50));
		tool(tools, "get_chat", "Recent chat and game messages.", chat);
		tool(tools, "get_scoreboard", "Scoreboard objectives currently displayed.", obj());
		tool(tools, "list_commands", "Server commands this client may use.", obj());
		tool(tools, "get_players", "Players on the tab list.", obj());
		tool(tools, "get_crosshair", "What the crosshair is pointing at.", obj());
		JsonObject look = obj();
		look.add("yaw", num("Target yaw in degrees.", null));
		look.add("pitch", num("Target pitch in degrees, -90 to 90.", null));
		look.add("seconds", num("How long the turn takes. Default is about 90 degrees per second.", null));
		tool(tools, "look", "Turn the camera smoothly to a yaw and pitch.", look);
		JsonObject move = obj();
		move.add("forward", bool("Walk forward."));
		move.add("back", bool("Walk backward."));
		move.add("left", bool("Strafe left."));
		move.add("right", bool("Strafe right."));
		move.add("jump", bool("Hold jump."));
		move.add("sneak", bool("Hold sneak."));
		move.add("sprint", bool("Hold sprint."));
		move.add("seconds", num("How long to hold the keys. Required.", null));
		tool(tools, "move", "Hold movement inputs, then release them. Queued.", move);
		JsonObject attack = obj();
		attack.add("seconds", num("How long to hold attack. Default one click.", 0));
		tool(tools, "attack", "Attack the crosshair target. Holding it keeps breaking a block.", attack);
		JsonObject use = obj();
		use.add("seconds", num("How long to hold use. Default one click.", 0));
		tool(tools, "use", "Use the item in hand, or the block or entity under the crosshair.", use);
		JsonObject hotbar = obj();
		hotbar.add("slot", num("Hotbar slot 0 through 8.", null));
		tool(tools, "hotbar", "Select a hotbar slot.", hotbar);
		JsonObject say = obj();
		say.add("message", str("Chat text. A leading slash sends a command."));
		tool(tools, "chat", "Queue a chat message. Does not wait for other actions.", say);
		JsonObject command = obj();
		command.add("command", str("Command without the leading slash."));
		tool(tools, "command", "Run a server command after the current action finishes.", command);
		JsonObject connect = obj();
		connect.add("address", str("Host or host:port."));
		tool(tools, "connect", "Connect to a server. Disconnects first if already in a world. Server resource packs are accepted automatically.", connect);
		tool(tools, "disconnect", "Leave the current world or server.", obj());
		tool(tools, "reconnect", "Disconnect and connect again to the last server.", obj());
		tool(tools, "close_screen", "Close the open screen, like pressing Escape. Does nothing if no screen is open.", obj());
		JsonObject hud = obj();
		hud.add("hidden", bool("True hides the HUD."));
		tool(tools, "set_hud", "Hide or show the HUD. The previous value is restored after a screenshot that hid it.", hud);
		JsonObject shot = obj();
		shot.add("hide_hud", bool("Hide the HUD for this shot, then restore it."));
		tool(tools, "screenshot", "Capture the framebuffer as a 1280x720-or-smaller jpeg.", shot);
		JsonObject wait = obj();
		wait.add("seconds", num("Seconds to wait. Required.", null));
		tool(tools, "wait", "Wait, blocking the action queue.", wait);
		tool(tools, "stop", "Release inputs and drop queued actions. Chat is left alone.", obj());
		JsonObject result = new JsonObject();
		result.add("tools", tools);
		return result;
	}

	private static JsonObject call(JsonObject params) throws Exception {
		String name = params.get("name").getAsString();
		JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments") : new JsonObject();
		SharkMcpClient mod = SharkMcpClient.instance;
		if (mod == null) throw new IllegalStateException("client is still starting");
		String text;
		String jpeg = null;
		switch (name) {
			case "get_state" -> text = mod.state();
			case "get_inventory" -> text = mod.inventory();
			case "get_chat" -> text = mod.chat(optInt(args, "limit", 50));
			case "get_scoreboard" -> text = mod.scoreboard();
			case "list_commands" -> text = mod.commands();
			case "get_players" -> text = mod.players();
			case "get_crosshair" -> text = mod.crosshair();
			case "look" -> text = mod.enqueue(() -> mod.look(reqFloat(args, "yaw"), reqFloat(args, "pitch"), optDouble(args, "seconds", -1)));
			case "move" -> text = mod.enqueue(() -> mod.move(
					optBool(args, "forward"), optBool(args, "back"), optBool(args, "left"), optBool(args, "right"),
					optBool(args, "jump"), optBool(args, "sneak"), optBool(args, "sprint"), reqDouble(args, "seconds")));
			case "attack" -> text = mod.enqueue(() -> mod.attack(optDouble(args, "seconds", 0)));
			case "use" -> text = mod.enqueue(() -> mod.use(optDouble(args, "seconds", 0)));
			case "hotbar" -> text = mod.enqueue(() -> mod.hotbar(reqInt(args, "slot")));
			case "chat" -> text = mod.enqueueChat(reqString(args, "message"));
			case "command" -> text = mod.enqueue(() -> mod.command(reqString(args, "command")));
			case "connect" -> text = mod.enqueue(() -> mod.connect(reqString(args, "address")));
			case "disconnect" -> text = mod.enqueue(mod::disconnect);
			case "reconnect" -> text = mod.enqueue(mod::reconnect);
			case "close_screen" -> text = mod.enqueue(mod::closeScreen);
			case "set_hud" -> text = mod.enqueue(() -> mod.setHud(reqBool(args, "hidden")));
			case "screenshot" -> {
				byte[] image = mod.enqueueImage(() -> mod.screenshot(optBool(args, "hide_hud")));
				jpeg = Base64.getEncoder().encodeToString(image);
				text = "screenshot";
			}
			case "wait" -> text = mod.enqueue(() -> mod.waitSeconds(reqDouble(args, "seconds")));
			case "stop" -> text = mod.stop();
			default -> throw new IllegalArgumentException("unknown tool: " + name);
		}
		JsonArray content = new JsonArray();
		JsonObject textPart = new JsonObject();
		textPart.addProperty("type", "text");
		textPart.addProperty("text", text);
		content.add(textPart);
		if (jpeg != null) {
			JsonObject imagePart = new JsonObject();
			imagePart.addProperty("type", "image");
			imagePart.addProperty("data", jpeg);
			imagePart.addProperty("mimeType", "image/jpeg");
			content.add(imagePart);
		}
		JsonObject result = new JsonObject();
		result.add("content", content);
		result.addProperty("isError", false);
		return result;
	}

	private static String error(JsonElement id, int code, String message) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		if (id == null) response.add("id", null);
		else response.add("id", id);
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		response.add("error", err);
		return response.toString();
	}

	private static void tool(JsonArray tools, String name, String description, JsonObject properties) {
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name);
		tool.addProperty("description", description);
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		tool.add("inputSchema", schema);
		tools.add(tool);
	}

	private static JsonObject obj() {
		return new JsonObject();
	}

	private static JsonObject num(String description, Number def) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "number");
		property.addProperty("description", description);
		if (def != null) property.addProperty("default", def);
		return property;
	}

	private static JsonObject bool(String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "boolean");
		property.addProperty("description", description);
		return property;
	}

	private static JsonObject str(String description) {
		JsonObject property = new JsonObject();
		property.addProperty("type", "string");
		property.addProperty("description", description);
		return property;
	}

	private static boolean optBool(JsonObject args, String key) {
		return args.has(key) && args.get(key).getAsBoolean();
	}

	private static boolean reqBool(JsonObject args, String key) {
		if (!args.has(key)) throw new IllegalArgumentException("missing " + key);
		return args.get(key).getAsBoolean();
	}

	private static int optInt(JsonObject args, String key, int def) {
		return args.has(key) ? args.get(key).getAsInt() : def;
	}

	private static int reqInt(JsonObject args, String key) {
		if (!args.has(key)) throw new IllegalArgumentException("missing " + key);
		return args.get(key).getAsInt();
	}

	private static double optDouble(JsonObject args, String key, double def) {
		return args.has(key) ? args.get(key).getAsDouble() : def;
	}

	private static double reqDouble(JsonObject args, String key) {
		if (!args.has(key)) throw new IllegalArgumentException("missing " + key);
		return args.get(key).getAsDouble();
	}

	private static float reqFloat(JsonObject args, String key) {
		if (!args.has(key)) throw new IllegalArgumentException("missing " + key);
		return args.get(key).getAsFloat();
	}

	private static String reqString(JsonObject args, String key) {
		if (!args.has(key) || args.get(key).getAsString().isBlank()) throw new IllegalArgumentException("missing " + key);
		return args.get(key).getAsString();
	}
}
