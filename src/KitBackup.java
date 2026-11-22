package mlg.rush;

import java.util.*;
import org.powernukkitx.Player;
import org.powernukkitx.item.Item;
import org.powernukkitx.utils.ItemHelper;

final class KitBackup {
    static List<String> capture(Player p) {
        List<String> slots = new ArrayList<>();
        p.getInventory().getContents().forEach((slot, item) -> encode(slots, "bag", slot, item));
        Item[] armor = p.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) encode(slots, "armor", i, armor[i]);
        p.getOffhandInventory().getContents().forEach((slot, item) -> encode(slots, "off", slot, item));
        return slots;
    }

    private static void encode(List<String> slots, String group, int slot, Item item) {
        if (item == null || item.isNull()) return;
        byte[] bytes = item.writeCompoundTag(ItemHelper.write(item));
        slots.add(group + ":" + slot + ":" + Base64.getEncoder().encodeToString(bytes));
    }

    static void empty(Player p) {
        p.getInventory().clearAll();
        p.getOffhandInventory().clearAll();
    }

    static void restore(Player p, List<String> slots) {
        Map<Integer,Item> bag = new HashMap<>(), armor = new HashMap<>(), off = new HashMap<>();
        for (String entry : slots) {
            String[] parts = entry.split(":", 3);
            Map<Integer,Item> target = switch (parts[0]) {
                case "bag" -> bag;
                case "armor" -> armor;
                case "off" -> off;
                default -> throw new IllegalArgumentException("Unbekannter Inventarbereich");
            };
            target.put(Integer.parseInt(parts[1]), ItemHelper.read(Item.parseCompoundTag(Base64.getDecoder().decode(parts[2]))));
        }
        empty(p);
        bag.forEach((i, item) -> p.getInventory().setItem(i, item));
        armor.forEach((i, item) -> p.getInventory().setArmorItem(i, item));
        off.forEach((i, item) -> p.getOffhandInventory().setItem(i, item));
    }
}
