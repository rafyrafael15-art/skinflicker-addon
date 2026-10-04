package com.example.addon.modules; // <-- cambia il package con quello del tuo addon

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.player.PlayerModelPart;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fa lampeggiare i layer della skin (cappello, giacca, maniche, pantaloni, mantello).
 * Scritto per Meteor Client 26.1.2 / Minecraft 26.1.x (mappature ufficiali Mojang).
 */
public class SkinFlicker extends Module {
    public enum FlickerMode { HORIZONTAL, VERTICAL, RANDOM }
    public enum HorizontalDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }
    public enum VerticalDirection { TOP_TO_BOTTOM, BOTTOM_TO_TOP }
    public enum PartsMode { ONE_BY_ONE, MULTIPLE_PARTS, ALL_SIMULTANEOUSLY }

    private final SettingGroup sgMode = settings.getDefaultGroup();
    private final SettingGroup sgDelay = settings.createGroup("Delay");
    private final SettingGroup sgMultiple = settings.createGroup("Multiple Parts");
    private final SettingGroup sgParts = settings.createGroup("Parts");

    // --- Mode ---
    private final Setting<FlickerMode> mode = sgMode.add(new EnumSetting.Builder<FlickerMode>()
        .name("mode").description("Il tipo di flicker.")
        .defaultValue(FlickerMode.HORIZONTAL).build());

    private final Setting<HorizontalDirection> horizontalDirection = sgMode.add(new EnumSetting.Builder<HorizontalDirection>()
        .name("horizontal-direction").description("Direzione del flicker orizzontale.")
        .defaultValue(HorizontalDirection.LEFT_TO_RIGHT)
        .visible(() -> mode.get() == FlickerMode.HORIZONTAL).build());

    private final Setting<VerticalDirection> verticalDirection = sgMode.add(new EnumSetting.Builder<VerticalDirection>()
        .name("vertical-direction").description("Direzione del flicker verticale.")
        .defaultValue(VerticalDirection.TOP_TO_BOTTOM)
        .visible(() -> mode.get() == FlickerMode.VERTICAL).build());

    private final Setting<PartsMode> partsMode = sgMode.add(new EnumSetting.Builder<PartsMode>()
        .name("parts-mode").description("Quante parti lampeggiano insieme.")
        .defaultValue(PartsMode.ONE_BY_ONE).build());

    // --- Delay (millisecondi) ---
    private final Setting<Boolean> randomizeDelay = sgDelay.add(new BoolSetting.Builder()
        .name("randomize-delay").description("Usa un delay casuale tra min e max.")
        .defaultValue(false).build());

    private final Setting<Integer> delay = sgDelay.add(new IntSetting.Builder()
        .name("delay").description("Delay tra uno scatto e l'altro (ms).")
        .defaultValue(100).min(0).sliderRange(0, 2000)
        .visible(() -> !randomizeDelay.get()).build());

    private final Setting<Integer> minRandomDelay = sgDelay.add(new IntSetting.Builder()
        .name("min-random-delay").description("Delay minimo (ms).")
        .defaultValue(50).min(0).sliderRange(0, 2000)
        .visible(randomizeDelay::get).build());

    private final Setting<Integer> maxRandomDelay = sgDelay.add(new IntSetting.Builder()
        .name("max-random-delay").description("Delay massimo (ms).")
        .defaultValue(200).min(0).sliderRange(0, 2000)
        .visible(randomizeDelay::get).build());

    // --- Multiple parts ---
    private final Setting<Integer> multipleCount = sgMultiple.add(new IntSetting.Builder()
        .name("parts-at-once").description("Quanti passaggi/parti lampeggiano insieme.")
        .defaultValue(2).min(1).sliderRange(1, 7)
        .visible(() -> partsMode.get() == PartsMode.MULTIPLE_PARTS).build());

    // --- Parts ---
    private final Setting<Boolean> hat = bool("hat", "Cappello");
    private final Setting<Boolean> jacket = bool("jacket", "Giacca");
    private final Setting<Boolean> leftSleeve = bool("left-sleeve", "Manica sinistra");
    private final Setting<Boolean> rightSleeve = bool("right-sleeve", "Manica destra");
    private final Setting<Boolean> leftPants = bool("left-pants", "Gamba sinistra");
    private final Setting<Boolean> rightPants = bool("right-pants", "Gamba destra");
    private final Setting<Boolean> cape = bool("cape", "Mantello");

    private final Map<PlayerModelPart, Boolean> original = new EnumMap<>(PlayerModelPart.class);
    private long lastToggle;
    private long currentDelay;
    private int index;
    private boolean allOn;

    public SkinFlicker() {
        super(Categories.Misc, "skin-flicker", "Fa lampeggiare rapidamente i layer della skin.");
    }

    private Setting<Boolean> bool(String name, String description) {
        return sgParts.add(new BoolSetting.Builder().name(name).description("Fa lampeggiare: " + description + ".")
            .defaultValue(true).build());
    }

    @Override
    public void onActivate() {
        original.clear();
        if (mc.options == null) return;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            original.put(part, mc.options.isModelPartEnabled(part));
        }
        lastToggle = 0;
        currentDelay = 0;
        index = 0;
        allOn = true;
    }

    @Override
    public void onDeactivate() {
        if (mc.options == null) return;
        // Ripristina lo stato originale dei layer
        for (Map.Entry<PlayerModelPart, Boolean> e : original.entrySet()) {
            mc.options.setModelPart(e.getKey(), e.getValue());
        }
        original.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.options == null) return;

        long now = System.currentTimeMillis();
        if (now - lastToggle < currentDelay) return;
        lastToggle = now;
        currentDelay = nextDelay();

        List<List<PlayerModelPart>> steps = buildSteps();
        if (steps.isEmpty()) return;

        List<PlayerModelPart> all = new ArrayList<>();
        steps.forEach(all::addAll);

        // Parti che in questo scatto vengono nascoste
        List<PlayerModelPart> hidden = new ArrayList<>();
        int window = switch (partsMode.get()) {
            case ONE_BY_ONE -> 1;
            case MULTIPLE_PARTS -> Math.min(multipleCount.get(), steps.size());
            case ALL_SIMULTANEOUSLY -> steps.size();
        };

        if (partsMode.get() == PartsMode.ALL_SIMULTANEOUSLY) {
            allOn = !allOn;
            if (!allOn) hidden.addAll(all);
        } else if (mode.get() == FlickerMode.RANDOM) {
            List<Integer> pool = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) pool.add(i);
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (int i = 0; i < window && !pool.isEmpty(); i++) {
                hidden.addAll(steps.get(pool.remove(rng.nextInt(pool.size()))));
            }
        } else {
            for (int i = 0; i < window; i++) {
                hidden.addAll(steps.get((index + i) % steps.size()));
            }
            index = (index + 1) % steps.size();
        }

        for (PlayerModelPart part : all) {
            mc.options.setModelPart(part, !hidden.contains(part));
        }
    }

    private long nextDelay() {
        if (!randomizeDelay.get()) return delay.get();
        int min = Math.min(minRandomDelay.get(), maxRandomDelay.get());
        int max = Math.max(minRandomDelay.get(), maxRandomDelay.get());
        return min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /** Costruisce la sequenza di "passaggi" in base alla modalità, tenendo solo le parti selezionate. */
    private List<List<PlayerModelPart>> buildSteps() {
        List<List<PlayerModelPart>> raw = new ArrayList<>();

        switch (mode.get()) {
            case VERTICAL -> {
                raw.add(parts(hat, PlayerModelPart.HAT));
                raw.add(merge(parts(jacket, PlayerModelPart.JACKET), parts(leftSleeve, PlayerModelPart.LEFT_SLEEVE),
                    parts(rightSleeve, PlayerModelPart.RIGHT_SLEEVE), parts(cape, PlayerModelPart.CAPE)));
                raw.add(merge(parts(leftPants, PlayerModelPart.LEFT_PANTS_LEG), parts(rightPants, PlayerModelPart.RIGHT_PANTS_LEG)));
                if (verticalDirection.get() == VerticalDirection.BOTTOM_TO_TOP) java.util.Collections.reverse(raw);
            }
            default -> { // HORIZONTAL e RANDOM
                raw.add(merge(parts(leftSleeve, PlayerModelPart.LEFT_SLEEVE)));
                raw.add(merge(parts(leftPants, PlayerModelPart.LEFT_PANTS_LEG)));
                raw.add(merge(parts(jacket, PlayerModelPart.JACKET), parts(hat, PlayerModelPart.HAT), parts(cape, PlayerModelPart.CAPE)));
                raw.add(merge(parts(rightPants, PlayerModelPart.RIGHT_PANTS_LEG)));
                raw.add(merge(parts(rightSleeve, PlayerModelPart.RIGHT_SLEEVE)));
                if (mode.get() == FlickerMode.HORIZONTAL && horizontalDirection.get() == HorizontalDirection.RIGHT_TO_LEFT) {
                    java.util.Collections.reverse(raw);
                }
            }
        }

        raw.removeIf(List::isEmpty);
        return raw;
    }

    private List<PlayerModelPart> parts(Setting<Boolean> enabled, PlayerModelPart part) {
        return enabled.get() ? List.of(part) : List.of();
    }

    @SafeVarargs
    private List<PlayerModelPart> merge(List<PlayerModelPart>... lists) {
        List<PlayerModelPart> out = new ArrayList<>();
        for (List<PlayerModelPart> l : lists) out.addAll(l);
        return out;
    }
}
