package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * AI Tool: Interact with villagers — list trades and execute trades.
 */
public class VillagerTradeTool implements AiTool {

    @Override
    public String name() { return "villager_trade"; }

    @Override
    public String description() {
        return "Interact with nearby villagers. Actions: " +
                "'list' = show all available trades from nearest villager, " +
                "'trade' = execute a specific trade by index number. " +
                "Uses items from player's inventory to pay. " +
                "The villager must be within 16 blocks.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "Action: 'list' (show trades) or 'trade' (execute trade)");
        props.add("action", action);

        JsonObject index = new JsonObject();
        index.addProperty("type", "integer");
        index.addProperty("description", "Trade index (1-based) when action='trade'. Get from 'list' first.");
        props.add("index", index);

        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("description", "Number of times to repeat the trade. Default: 1.");
        props.add("count", count);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("action");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            if (!args.has("action") || args.get("action").isJsonNull()) {
                return "Error: 'action' parameter is required. Use: 'list', 'buy', or 'sell'.";
            }
            String action = args.get("action").getAsString().toLowerCase().trim();

            // Find nearest villager
            AABB area = new AABB(context.player().blockPosition()).inflate(16);
            List<AbstractVillager> villagers = context.player().level()
                    .getEntitiesOfClass(AbstractVillager.class, area, v -> v.isAlive());

            if (villagers.isEmpty()) {
                return "No villagers found within 16 blocks.";
            }

            // Sort by distance
            villagers.sort((a, b) -> Double.compare(
                    context.player().distanceToSqr(a),
                    context.player().distanceToSqr(b)));
            AbstractVillager villager = villagers.get(0);

            MerchantOffers offers = villager.getOffers();
            if (offers.isEmpty()) {
                return "This villager has no trades available.";
            }

            if (action.equals("list")) {
                return listTrades(villager, offers);
            } else if (action.equals("trade")) {
                int index = args.has("index") ? args.get("index").getAsInt() : -1;
                int count = args.has("count") ? args.get("count").getAsInt() : 1;
                return executeTrade(context, villager, offers, index, count);
            }

            return "Unknown action. Use 'list' or 'trade'.";
        });
    }

    private String listTrades(AbstractVillager villager, MerchantOffers offers) {
        StringBuilder sb = new StringBuilder();

        String type = "Villager";
        if (villager instanceof Villager v) {
            type = v.getVillagerData().getProfession().name() + " Villager (Level " +
                    v.getVillagerData().getLevel() + ")";
        } else if (villager instanceof WanderingTrader) {
            type = "Wandering Trader";
        }

        sb.append(type).append(" trades:\n");

        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            sb.append("  ").append(i + 1).append(". ");

            // Cost
            ItemStack cost1 = offer.getCostA();
            sb.append(cost1.getCount()).append("x ").append(cost1.getDisplayName().getString());
            ItemStack cost2 = offer.getCostB();
            if (!cost2.isEmpty()) {
                sb.append(" + ").append(cost2.getCount()).append("x ")
                  .append(cost2.getDisplayName().getString());
            }

            // Result
            ItemStack result = offer.getResult();
            sb.append(" → ").append(result.getCount()).append("x ")
              .append(result.getDisplayName().getString());

            // Uses
            if (offer.isOutOfStock()) {
                sb.append(" [OUT OF STOCK]");
            } else {
                sb.append(" [").append(offer.getUses()).append("/")
                  .append(offer.getMaxUses()).append(" uses]");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String executeTrade(ToolContext context, AbstractVillager villager,
                                 MerchantOffers offers, int index, int count) {
        if (index < 1 || index > offers.size()) {
            return "Invalid trade index " + index + ". Use 'list' first to see available trades (1-" + offers.size() + ").";
        }

        MerchantOffer offer = offers.get(index - 1);
        if (offer.isOutOfStock()) {
            return "Trade #" + index + " is out of stock.";
        }

        int tradesCompleted = 0;
        var playerInv = context.player().getInventory();

        for (int t = 0; t < count && !offer.isOutOfStock(); t++) {
            // Check player has the costs
            ItemStack cost1 = offer.getCostA();
            ItemStack cost2 = offer.getCostB();

            if (!hasItems(playerInv, cost1) || (!cost2.isEmpty() && !hasItems(playerInv, cost2))) {
                break;
            }

            // Consume costs
            removeItems(playerInv, cost1);
            if (!cost2.isEmpty()) removeItems(playerInv, cost2);

            // Give result
            ItemStack result = offer.getResult().copy();
            if (!playerInv.add(result)) {
                context.player().drop(result, false);
            }

            offer.increaseUses();
            tradesCompleted++;
        }

        if (tradesCompleted == 0) {
            return "Couldn't complete trade — missing payment items.";
        }

        ItemStack result = offer.getResult();
        return "Completed trade " + tradesCompleted + " time(s): got " +
                (result.getCount() * tradesCompleted) + "x " +
                result.getDisplayName().getString() + ".";
    }

    private boolean hasItems(net.minecraft.world.entity.player.Inventory inv, ItemStack required) {
        int need = required.getCount();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, required)) {
                need -= stack.getCount();
                if (need <= 0) return true;
            }
        }
        return false;
    }

    private void removeItems(net.minecraft.world.entity.player.Inventory inv, ItemStack required) {
        int remaining = required.getCount();
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, required)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                remaining -= take;
            }
        }
    }
}
