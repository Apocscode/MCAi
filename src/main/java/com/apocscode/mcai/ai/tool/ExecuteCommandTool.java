package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSourceStack;

import java.util.Set;

/**
 * Executes Minecraft commands as the player or with elevated permissions.
 * This gives the AI core access to the game — time, weather, teleport,
 * give items, gamemode, summon, fill, setblock, etc.
 *
 * Security: Blocks dangerous server admin commands (stop, op, ban, whitelist, etc.).
 * Commands run with permission level 2 (Game Master) by default, which allows
 * most gameplay commands but not server administration.
 */
public class ExecuteCommandTool implements AiTool {

    // Commands that are NEVER allowed (server admin / destructive)
    private static final Set<String> BLOCKED_COMMANDS = Set.of(
            "stop", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip",
            "whitelist", "save-all", "save-off", "save-on", "kick",
            "publish", "debug", "reload", "forceload", "jfr",
            "perf", "transfer"
    );

    // Permission level for command execution
    // 0 = normal player, 1 = moderator, 2 = game master, 3 = admin, 4 = owner
    private static final int PERMISSION_LEVEL = 2;

    @Override
    public String name() {
        return "execute_command";
    }

    @Override
    public String description() {
        return "Execute a Minecraft command. Can run most gameplay commands like: " +
                "/time set day, /weather clear, /give, /tp, /gamemode, /effect, " +
                "/summon, /setblock, /fill, /kill, /xp, /enchant, /clear, " +
                "/title, /tellraw, /playsound, /particle, etc. " +
                "Use when the player asks you to change the game state directly. " +
                "Commands should NOT include the leading slash.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject command = new JsonObject();
        command.addProperty("type", "string");
        command.addProperty("description",
                "The command to execute WITHOUT the leading slash. " +
                        "Examples: 'time set day', 'weather clear', 'give @s diamond 64', " +
                        "'tp @s 100 64 200', 'effect give @s speed 600 2', " +
                        "'gamemode creative', 'setblock ~ ~1 ~ minecraft:command_block'");
        props.add("command", command);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("command");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        if (context.player() == null || context.server() == null) {
            return "Error: no server context";
        }

        String command = args.has("command") ? args.get("command").getAsString().trim() : "";
        if (command.isEmpty()) return "Error: no command specified";

        // Strip leading slash if present
        if (command.startsWith("/")) command = command.substring(1);

        // Security check — block dangerous commands
        String rootCommand = command.split("\\s+")[0].toLowerCase();
        if (BLOCKED_COMMANDS.contains(rootCommand)) {
            return "Error: command '" + rootCommand + "' is blocked for security reasons.";
        }

        // Execute on server thread with elevated permissions
        final String cmd = command;
        return context.runOnServer(() -> {
            try {
                // Create a command source with elevated permissions
                CommandSourceStack source = context.player().createCommandSourceStack()
                        .withPermission(PERMISSION_LEVEL)
                        .withSuppressedOutput(); // Don't spam chat

                // Use Brigadier dispatcher which returns an int result
                int result = context.server().getCommands()
                        .getDispatcher().execute(cmd, source);

                if (result > 0) {
                    return "Command executed successfully: /" + cmd + " (result: " + result + ")";
                } else {
                    return "Command completed: /" + cmd;
                }
            } catch (Exception e) {
                MCAi.LOGGER.error("Command execution failed: /{} - {}", cmd, e.getMessage());
                return "Error executing '/" + cmd + "': " + e.getMessage();
            }
        });
    }
}
