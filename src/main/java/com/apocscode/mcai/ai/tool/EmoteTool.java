package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * AI Tool: Make the companion perform emotes with particles and sounds.
 * Adds personality and expressiveness to interactions.
 */
public class EmoteTool implements AiTool {

    @Override
    public String name() { return "emote"; }

    @Override
    public String description() {
        return "Make the companion perform an emote with visual effects and sound. " +
                "Emotes: 'wave' (friendly greeting), 'celebrate' (happy fireworks), " +
                "'sad' (rain cloud), 'angry' (smoke), 'love' (hearts), " +
                "'thinking' (enchantment particles), 'sneeze' (poof). " +
                "Use to express emotions or react to player actions.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject emote = new JsonObject();
        emote.addProperty("type", "string");
        emote.addProperty("description", "The emote to perform");
        JsonArray emoteEnum = new JsonArray();
        emoteEnum.add("wave");
        emoteEnum.add("celebrate");
        emoteEnum.add("sad");
        emoteEnum.add("angry");
        emoteEnum.add("love");
        emoteEnum.add("thinking");
        emoteEnum.add("sneeze");
        emote.add("enum", emoteEnum);
        props.add("emote", emote);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("emote");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";
            if (!(companion.level() instanceof ServerLevel serverLevel)) return "Server error.";

            String emote = args.has("emote") ? args.get("emote").getAsString() : "wave";
            double x = companion.getX();
            double y = companion.getY() + companion.getBbHeight();
            double z = companion.getZ();

            switch (emote) {
                case "wave" -> {
                    // Sparkle particles in an arc
                    for (int i = 0; i < 8; i++) {
                        double angle = Math.PI * i / 7;
                        serverLevel.sendParticles(ParticleTypes.END_ROD,
                                x + Math.cos(angle) * 0.8,
                                y + Math.sin(angle) * 0.5 + 0.3,
                                z, 1, 0, 0, 0, 0);
                    }
                    companion.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.5F, 1.2F);
                    return "*waves hello!*";
                }
                case "celebrate" -> {
                    // Firework-like burst
                    serverLevel.sendParticles(ParticleTypes.FIREWORK,
                            x, y + 0.5, z, 30, 0.5, 0.5, 0.5, 0.2);
                    serverLevel.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                            x, y, z, 15, 0.3, 0.3, 0.3, 0.3);
                    companion.playSound(SoundEvents.FIREWORK_ROCKET_BLAST, 0.8F, 1.0F);
                    return "*celebrates with fireworks!*";
                }
                case "sad" -> {
                    // Dripping water particles
                    for (int i = 0; i < 10; i++) {
                        serverLevel.sendParticles(ParticleTypes.FALLING_WATER,
                                x + (companion.getRandom().nextFloat() - 0.5) * 0.6,
                                y + 0.5,
                                z + (companion.getRandom().nextFloat() - 0.5) * 0.6,
                                3, 0.1, 0, 0.1, 0);
                    }
                    companion.playSound(SoundEvents.GHAST_HURT, 0.3F, 1.5F);
                    return "*looks sad...*";
                }
                case "angry" -> {
                    // Smoke and angry villager particles
                    serverLevel.sendParticles(ParticleTypes.SMOKE,
                            x, y + 0.3, z, 15, 0.3, 0.3, 0.3, 0.02);
                    serverLevel.sendParticles(ParticleTypes.ANGRY_VILLAGER,
                            x, y + 0.5, z, 5, 0.3, 0.2, 0.3, 0);
                    companion.playSound(SoundEvents.VILLAGER_NO, 0.6F, 0.8F);
                    return "*looks angry!*";
                }
                case "love" -> {
                    // Heart particles
                    serverLevel.sendParticles(ParticleTypes.HEART,
                            x, y + 0.5, z, 8, 0.5, 0.3, 0.5, 0);
                    companion.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5F, 1.4F);
                    return "*shows love with hearts!*";
                }
                case "thinking" -> {
                    // Enchantment glyphs circling head
                    for (int i = 0; i < 12; i++) {
                        double angle = Math.PI * 2 * i / 12;
                        serverLevel.sendParticles(ParticleTypes.ENCHANT,
                                x + Math.cos(angle) * 0.6,
                                y + 0.4,
                                z + Math.sin(angle) * 0.6,
                                2, 0, 0.2, 0, 0);
                    }
                    companion.playSound(SoundEvents.ENCHANTMENT_TABLE_USE, 0.3F, 1.5F);
                    return "*hmm, thinking...*";
                }
                case "sneeze" -> {
                    // Poof cloud
                    serverLevel.sendParticles(ParticleTypes.CLOUD,
                            x + companion.getLookAngle().x * 0.5,
                            y + 0.2,
                            z + companion.getLookAngle().z * 0.5,
                            10, 0.2, 0.1, 0.2, 0.05);
                    companion.playSound(SoundEvents.PANDA_SNEEZE, 0.7F, 1.0F);
                    return "*achoo!*";
                }
                default -> { return "Unknown emote: " + emote; }
            }
        });
    }
}
