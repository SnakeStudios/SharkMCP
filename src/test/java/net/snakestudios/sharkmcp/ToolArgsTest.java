package net.snakestudios.sharkmcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Argument parsing shared by every tool. A missing required field must fail before the client is touched. */
class ToolArgsTest {
	@Test
	void requiredFields() {
		assertEquals("missing yaw", fail("reqFloat", args("{}"), "yaw"));
		assertEquals("missing pitch", fail("reqFloat", args("{\"yaw\":1}"), "pitch"));
		assertEquals("missing seconds", fail("reqDouble", args("{}"), "seconds"));
		assertEquals("missing slot", fail("reqInt", args("{}"), "slot"));
		assertEquals("missing hidden", fail("reqBool", args("{}"), "hidden"));
		assertEquals("missing message", fail("reqString", args("{}"), "message"));
		assertEquals("missing message", fail("reqString", args("{\"message\":\"  \"}"), "message"));
		assertEquals("missing address", fail("reqString", args("{}"), "address"));
		assertEquals("missing command", fail("reqString", args("{\"command\":\"\"}"), "command"));
	}

	@Test
	void presentFields() {
		assertEquals(90f, call("reqFloat", args("{\"yaw\":90}"), "yaw"));
		assertEquals(1.5, call("reqDouble", args("{\"seconds\":1.5}"), "seconds"));
		assertEquals(4, call("reqInt", args("{\"slot\":4}"), "slot"));
		assertEquals(true, call("reqBool", args("{\"hidden\":true}"), "hidden"));
		assertEquals("hi", call("reqString", args("{\"message\":\"hi\"}"), "message"));
	}

	@Test
	void optionalFieldsFallBack() {
		assertEquals(false, call("optBool", args("{}"), "forward"));
		assertEquals(true, call("optBool", args("{\"forward\":true}"), "forward"));
		assertEquals(50, call("optInt", args("{}"), "limit", 50));
		assertEquals(3, call("optInt", args("{\"limit\":3}"), "limit", 50));
		assertEquals(-1d, call("optDouble", args("{}"), "seconds", -1d));
		assertEquals(0.4, call("optDouble", args("{\"seconds\":0.4}"), "seconds", -1d));
	}

	@Test
	void wrongTypes() {
		// Gson reads a non-boolean as false. A non-number still fails.
		assertEquals(false, call("optBool", args("{\"forward\":\"yes\"}"), "forward"));
		assertThrows(RuntimeException.class, () -> call("reqInt", args("{\"slot\":\"nope\"}"), "slot"));
	}

	@Test
	void toolSchemasAdvertiseTheRequiredShape() {
		JsonObject tools = JsonParser.parseString(McpServer.respond(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).getAsJsonObject().getAsJsonObject("result");
		for (var tool : tools.getAsJsonArray("tools")) {
			JsonObject schema = tool.getAsJsonObject().getAsJsonObject("inputSchema");
			assertEquals("object", schema.get("type").getAsString());
			assertTrue(schema.get("properties").isJsonObject());
			String name = tool.getAsJsonObject().get("name").getAsString();
			JsonObject properties = schema.getAsJsonObject("properties");
			switch (name) {
				case "look" -> {
					assertTrue(properties.has("yaw") && properties.has("pitch") && properties.has("seconds"));
					assertFalse(properties.getAsJsonObject("yaw").has("default"));
				}
				case "move" -> {
					assertTrue(properties.has("forward") && properties.has("seconds"));
					assertFalse(properties.getAsJsonObject("seconds").has("default"));
				}
				case "hotbar" -> assertTrue(properties.has("slot"));
				case "chat" -> assertTrue(properties.has("message"));
				case "command" -> assertTrue(properties.has("command"));
				case "connect" -> assertTrue(properties.has("address"));
				case "set_hud" -> assertTrue(properties.has("hidden"));
				case "screenshot" -> assertTrue(properties.has("hide_hud"));
				case "wait" -> assertFalse(properties.getAsJsonObject("seconds").has("default"));
				case "attack", "use" -> assertEquals(0, properties.getAsJsonObject("seconds").get("default").getAsInt());
				default -> assertTrue(properties.size() >= 0);
			}
		}
	}

	private static JsonObject args(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	private static Object call(String method, Object... values) {
		try {
			return MethodHolder.method(method).invoke(null, values);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof RuntimeException runtime) throw runtime;
			throw new RuntimeException(e.getCause());
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static String fail(String method, Object... values) {
		RuntimeException error = assertThrows(RuntimeException.class, () -> call(method, values));
		return error.getMessage();
	}

	private static final class MethodHolder {
		static final Method optBool = method("optBool", JsonObject.class, String.class);
		static final Method reqBool = method("reqBool", JsonObject.class, String.class);
		static final Method optInt = method("optInt", JsonObject.class, String.class, int.class);
		static final Method reqInt = method("reqInt", JsonObject.class, String.class);
		static final Method optDouble = method("optDouble", JsonObject.class, String.class, double.class);
		static final Method reqDouble = method("reqDouble", JsonObject.class, String.class);
		static final Method reqFloat = method("reqFloat", JsonObject.class, String.class);
		static final Method reqString = method("reqString", JsonObject.class, String.class);

		static Method method(String name) {
			return switch (name) {
				case "optBool" -> optBool;
				case "reqBool" -> reqBool;
				case "optInt" -> optInt;
				case "reqInt" -> reqInt;
				case "optDouble" -> optDouble;
				case "reqDouble" -> reqDouble;
				case "reqFloat" -> reqFloat;
				case "reqString" -> reqString;
				default -> throw new IllegalArgumentException(name);
			};
		}

		private static Method method(String name, Class<?>... types) {
			try {
				Method method = McpServer.class.getDeclaredMethod(name, types);
				method.setAccessible(true);
				return method;
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			}
		}
	}
}
