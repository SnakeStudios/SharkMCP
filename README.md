# SharkMCP

SharkMCP is a Fabric client mod that lets an AI see and play a Minecraft
client. It runs a small MCP server on `127.0.0.1:8765` and exposes the client
as tools: where the player is, what they can see, and what they can do.

It exists so an assistant can operate the game the way a player would, instead
of guessing from a description. The server only listens on localhost. Nothing
is exposed to the network.

## What it can do

Reads happen immediately.

- `get_state` — position, look, health, hunger, xp, server, open screen, HUD
- `get_inventory` — hotbar, inventory, offhand
- `get_chat` — recent chat and game messages
- `get_scoreboard` — displayed objectives
- `list_commands` — commands the client may use
- `get_players` — the tab list
- `get_crosshair` — the block or entity under the crosshair

Everything else runs one at a time, in the order it was asked.

- `look` — turn the camera smoothly
- `move` — hold WASD, jump, sneak, or sprint for a duration
- `attack` and `use` — click, or hold, the crosshair target
- `hotbar` — select a slot
- `command` — run a server command, without the leading slash
- `connect`, `disconnect`, `reconnect`
- `close_screen` — close the open screen
- `set_hud` — hide or show the HUD
- `screenshot` — the framebuffer as a JPEG, at most 1280×720
- `wait`, `stop`

`chat` has its own queue and does not wait behind other actions. `stop` drops
queued actions and releases held inputs. It does not touch the chat queue.

## Requirements

- Minecraft **1.21.11**
- Fabric Loader **0.20.1** or newer
- [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11
- Java **21** to run the game. Building needs Java **25**, because that is what
  Fabric Loom runs on. The mod still compiles for Java 21.

## Install

Put `sharkmcp-1.0.0.jar` and Fabric API in your `mods` folder and launch the
1.21.11 profile. The log prints:

```
SharkMCP listening on http://127.0.0.1:8765/mcp
```

Point an MCP client at `http://127.0.0.1:8765/mcp`. It speaks JSON-RPC over
POST: `initialize`, `tools/list`, `tools/call`, and `ping`. No SSE, no OAuth.

## Build

```
./gradlew build
```

The mod jar is written to `build/libs`. `./gradlew test` runs the protocol,
argument, action queue, and screenshot tests without launching Minecraft.

GitHub Actions builds and tests every push and pull request. A push to `main`
publishes the jar on the `latest` release, replacing the previous one. A tag
named `v*` publishes its own release.

## License

[MIT](LICENSE) © Snake Studios
