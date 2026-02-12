package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.ai.AiLogger;
import com.apocscode.mcai.config.AiConfig;
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

    @Override
    public String name() {
        return "execute_command";
    }

    @Override
    public String description() {
        return "Execute a Minecraft command (time, weather, give, tp, gamemode, effect, etc). " +
                "Do NOT include the leading slash.";
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
        Set<String> blocked = AiConfig.getBlockedCommands();
        if (blocked.contains(rootCommand)) {
            AiLogger.commandBlocked(command, "in blocked commands list");
            return "Error: command '" + rootCommand + "' is blocked for security reasons.";
        }

        // Execute on server thread with elevated permissions
        final String cmd = command;
        return context.runOnServer(() -> {
            try {
                int permLevel = AiConfig.COMMAND_PERMISSION_LEVEL.get();
                CommandSourceStack source = context.player().createCommandSourceStack()
                        .withPermission(permLevel)
                        .withSuppressedOutput(); // Don't spam chat

                // Use Brigadier dispatcher which returns an int result
                int result = context.server().getCommands()
                        .getDispatcher().execute(cmd, source);

                AiLogger.command(cmd, true, "result=" + result);

                if (result > 0) {
                    return "Command executed successfully: /" + cmd + " (result: " + result + ")";
                } else {
                    return "Command completed: /" + cmd;
                }
            } catch (Exception e) {
                AiLogger.command(cmd, false, e.getMessage());
                MCAi.LOGGER.error("Command execution failed: /{} - {}", cmd, e.getMessage());
                return "Error executing '/" + cmd + "': " + e.getMessage();
            }
        });
    }
}
