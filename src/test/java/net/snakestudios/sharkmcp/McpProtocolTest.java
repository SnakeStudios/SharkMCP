package net.snakestudios.sharkmcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON-RPC surface. No Minecraft client, so tools/call stops at "still starting". */
class McpProtocolTest {
	@Test
	void initializeEchoesProtocolAndNamesItself() {
		JsonObject result = ok(call("initialize", "{\"protocolVersion\":\"2025-06-18\"}"));
		assertEquals("2025-06-18", result.get("protocolVersion").getAsString());
		assertEquals("sharkmcp", result.getAsJsonObject("serverInfo").get("name").getAsString());
		assertEquals("1.0.1", result.getAsJsonObject("serverInfo").get("version").getAsString());
		assertFalse(result.getAsJsonObject("capabilities").getAsJsonObject("tools").get("listChanged").getAsBoolean());
		assertTrue(result.get("instructions").getAsString().contains("screenshot"));
	}

	@Test
	void initializeDefaultsProtocolWhenOmitted() {
		JsonObject result = ok(call("initialize", "{}"));
		assertEquals("2024-11-05", result.get("protocolVersion").getAsString());
	}

	@Test
	void toolsListMatchesTheDispatcher() {
		JsonArray tools = ok(call("tools/list", null)).getAsJsonArray("tools");
		Set<String> names = new HashSet<>();
		for (JsonElement tool : tools) {
			JsonObject entry = tool.getAsJsonObject();
			names.add(entry.get("name").getAsString());
			assertFalse(entry.get("description").getAsString().isBlank());
			assertEquals("object", entry.getAsJsonObject("inputSchema").get("type").getAsString());
		}
		assertEquals(McpServer.TOOLS, names);
		assertEquals(22, names.size());
	}

	@Test
	void everyToolRequiresARunningClient() {
		for (String tool : McpServer.TOOLS) {
			if ("stop".equals(tool)) continue;
			JsonObject error = error(call("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":{}}"));
			assertEquals(-32603, error.get("code").getAsInt(), tool);
			assertTrue(error.get("message").getAsString().contains("still starting"), tool + " " + error);
		}
	}

	@Test
	void unknownToolIsAnError() {
		JsonObject error = error(call("tools/call", "{\"name\":\"nope\",\"arguments\":{}}"));
		assertEquals(-32603, error.get("code").getAsInt());
		assertTrue(error.get("message").getAsString().contains("still starting"));
	}

	@Test
	void missingToolNameIsAnError() {
		JsonObject error = error(call("tools/call", "{}"));
		assertEquals(-32603, error.get("code").getAsInt());
	}

	@Test
	void notificationsHaveNoBody() {
		assertNull(McpServer.respond("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
		assertNull(McpServer.respond("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\"}"));
	}

	@Test
	void unknownMethodAndParseErrors() {
		JsonObject missing = error(McpServer.respond("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"nope\"}"));
		assertEquals(-32601, missing.get("code").getAsInt());
		assertEquals(7, id(McpServer.respond("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"nope\"}")));
		JsonObject parse = error(McpServer.respond("not json"));
		assertEquals(-32700, parse.get("code").getAsInt());
		assertTrue(error(McpServer.respond("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"nope\"}")).get("code").getAsInt() < 0);
	}

	@Test
	void notificationOfUnknownMethodIsSilent() {
		assertNull(McpServer.respond("{\"jsonrpc\":\"2.0\",\"method\":\"nope\"}"));
	}

	@Test
	void pingIsEmptyResult() {
		assertEquals(0, ok(call("ping", null)).size());
	}

	@Test
	void idIsPreservedAsStringNumberAndNullError() {
		assertEquals("abc", JsonParser.parseString(call("ping", null, "\"abc\"")).getAsJsonObject().get("id").getAsString());
		assertEquals(3.5, JsonParser.parseString(call("ping", null, "3.5")).getAsJsonObject().get("id").getAsDouble());
	}

	private static String call(String method, String params) {
		return call(method, params, "1");
	}

	private static String call(String method, String params, String id) {
		String body = params == null
				? "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\"}"
				: "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
		String response = McpServer.respond(body);
		assertTrue(response.contains("\"jsonrpc\":\"2.0\""), response);
		return response;
	}

	private static JsonObject ok(String response) {
		JsonObject json = JsonParser.parseString(response).getAsJsonObject();
		assertFalse(json.has("error"), response);
		return json.getAsJsonObject("result");
	}

	private static JsonObject error(String response) {
		return JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("error");
	}

	private static int id(String response) {
		return JsonParser.parseString(response).getAsJsonObject().get("id").getAsInt();
	}
}
