package com.apocscode.mcai.ai.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.List;

/**
 * Tool that lists all installed mods in the current Minecraft instance.
 * Uses NeoForge's ModList API to return real mod IDs, names, and versions.
 * Helps the AI give accurate, modpack-aware advice.
 */
public class ListInstalledModsTool implements AiTool {

    @Override
    public String name() {
        return "list_installed_mods";
    }

    @Override
    public String description() {
        return "List all mods installed in the current Minecraft modpack. " +
                "Returns mod IDs, display names, and versions. " +
                "Use this to find out what mods the player has, check version compatibility, " +
                "or tailor advice to the specific modpack.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject filter = new JsonObject();
        filter.addProperty("type", "string");
        filter.addProperty("description",
                "Optional filter — only return mods whose name or ID contains this text " +
                "(case-insensitive). Leave empty or omit to list all mods.");
        props.add("filter", filter);

        schema.add("properties", props);
        schema.add("required", new JsonArray()); // no required params
        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        String filter = args.has("filter") ? args.get("filter").getAsString().toLowerCase() : "";

        List<IModInfo> allMods = ModList.get().getMods();

        StringBuilder sb = new StringBuilder();
        int count = 0;

        for (IModInfo mod : allMods) {
            String modId = mod.getModId();
            String name = mod.getDisplayName();
            String version = mod.getVersion().toString();

            // Apply filter
            if (!filter.isEmpty() &&
                    !modId.toLowerCase().contains(filter) &&
                    !name.toLowerCase().contains(filter)) {
                continue;
            }

            sb.append(String.format("- %s (%s) v%s\n", name, modId, version));
            count++;
        }

        if (count == 0 && !filter.isEmpty()) {
            return "No mods found matching '" + filter + "'. Total mods installed: " + allMods.size();
        }

        String header = filter.isEmpty()
                ? "Installed mods (" + count + " total):\n"
                : "Mods matching '" + filter + "' (" + count + " of " + allMods.size() + "):\n";

        return header + sb;
    }
}
