package com.example.afkkafa;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Elma kafasina tikla" tarzi menuler: basliktaki kelimeyi okur, dogru kafayi bulup tiklar.
 * Kafalari gorsellerinden (doku adresinden) tanir. Bilmedigi kafayi senin tikladigindan ogrenir.
 */
public class AfkHeads implements ClientModInitializer {

    private static final Pattern TITLE = Pattern.compile("(.+?)\\s+kafasina\\s+tikla");
    private static final Pattern TEX_URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Random RND = new Random();

    private static final HeadBrain BRAIN = new HeadBrain();
    private static Path file;

    private static Object lastScreen = null;
    private static String word = null;
    private static final Map<Integer, String> keys = new HashMap<>();
    private static int ticks = 0;
    private static boolean decided = false;
    private static int clickAt = -1;
    private static int clickSlot = -1;
    private static boolean selfClick = false;

    @Override
    public void onInitializeClient() {
        file = FabricLoader.getInstance().getConfigDir().resolve("afk-kafalar.txt");
        BRAIN.load(file);
        ClientTickEvents.END_CLIENT_TICK.register(AfkHeads::tick);
    }

    private static String norm(String s) {
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);
        s = s.replaceAll("\u00A7.", "");
        s = s.toLowerCase(Locale.ROOT);
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.replace('\u0131', 'i');
    }

    private static void reset() {
        lastScreen = null;
        word = null;
        keys.clear();
        ticks = 0;
        decided = false;
        clickAt = -1;
    }

    private static void msg(MinecraftClient c, String t) {
        if (c.player != null) c.player.sendMessage(Text.literal(t), true);
    }

    private static void tick(MinecraftClient c) {
        if (c.player == null || !(c.currentScreen instanceof HandledScreen<?> hs)) {
            if (lastScreen != null) reset();
            return;
        }
        Matcher m = TITLE.matcher(norm(hs.getTitle().getString()));
        if (!m.find()) {
            if (lastScreen != null) reset();
            return;
        }

        if (c.currentScreen != lastScreen) {
            reset();
            lastScreen = c.currentScreen;
            word = m.group(1).trim();
        }
        ticks++;

        if (!decided && ticks >= 12) decide(c, hs);

        if (clickAt >= 0 && ticks >= clickAt) {
            clickAt = -1;
            String k = keys.get(clickSlot);
            Slot s = hs.getScreenHandler().slots.get(clickSlot);
            if (k != null && s.hasStack() && k.equals(keyOf(s.getStack()))) {
                selfClick = true;
                try {
                    c.interactionManager.clickSlot(hs.getScreenHandler().syncId, clickSlot, 0, SlotActionType.PICKUP, c.player);
                } finally {
                    selfClick = false;
                }
            }
        }
    }

    private static void buildKeys(MinecraftClient c, HandledScreen<?> hs) {
        keys.clear();
        for (Slot s : hs.getScreenHandler().slots) {
            if (s.inventory == c.player.getInventory()) continue;
            String k = keyOf(s.getStack());
            if (k != null) keys.put(s.id, k);
        }
        // Kafa olmayan eşyalar (dolgu vb.) varsa ve en az 2 kafa görünüyorsa onları yok say
        int heads = 0;
        for (String k : keys.values()) if (!k.startsWith("item:")) heads++;
        if (heads >= 2) keys.values().removeIf(k -> k.startsWith("item:"));
    }

    private static void decide(MinecraftClient c, HandledScreen<?> hs) {
        buildKeys(c, hs);
        if (keys.size() < 2) {
            if (ticks > 60) decided = true;
            return;
        }
        decided = true;
        Integer pick = BRAIN.choose(word, keys);
        BRAIN.save(file);
        if (pick == null) {
            msg(c, "Bu kafayı bilmiyorum: \"" + word + "\" kafasına sen tıkla, öğrenirim");
            return;
        }
        clickSlot = pick;
        clickAt = ticks + 10 + RND.nextInt(15);
    }

    /** Oyuncu bir slota tikladiginda (mixin cagirir): dogru cevap sayilip ogrenilir. */
    public static void onUserClick(int slotId) {
        if (selfClick || word == null) return;
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player == null || !(c.currentScreen instanceof HandledScreen<?> hs)) return;
        if (keys.isEmpty()) buildKeys(c, hs);
        String k = keys.get(slotId);
        if (k == null) return;
        BRAIN.learn(word, k);
        BRAIN.save(file);
        msg(c, "Öğrendim: " + word);
    }

    private static String keyOf(ItemStack st) {
        if (st.isEmpty()) return null;
        String id = Registries.ITEM.getId(st.getItem()).getPath();
        NbtCompound nbt = st.getNbt();
        if (nbt == null || !nbt.contains("SkullOwner", NbtElement.COMPOUND_TYPE)) return "item:" + id;
        NbtCompound owner = nbt.getCompound("SkullOwner");
        if (!owner.contains("Properties", NbtElement.COMPOUND_TYPE)) return "item:" + id;
        NbtCompound props = owner.getCompound("Properties");
        if (!props.contains("textures", NbtElement.LIST_TYPE)) return "item:" + id;
        NbtList list = props.getList("textures", NbtElement.COMPOUND_TYPE);
        if (list.isEmpty()) return "item:" + id;
        String value = list.getCompound(0).getString("Value");
        try {
            String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            Matcher m = TEX_URL.matcher(json);
            if (m.find()) {
                String u = m.group(1);
                int i = u.lastIndexOf('/');
                return i >= 0 ? u.substring(i + 1) : u;
            }
        } catch (Exception ignored) {
        }
        return "h" + Integer.toHexString(value.hashCode());
    }
}
