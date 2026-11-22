package mlg.rush;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import org.powernukkitx.block.Block;
import org.powernukkitx.blockentity.BlockEntity;
import org.powernukkitx.item.Item;
import org.powernukkitx.level.Level;
import org.powernukkitx.math.Vector3;
import org.powernukkitx.registry.Registries;

final class ArenaChanges {
    private final Set<Vector3> recorded = new HashSet<>();
    private Path file(Path folder, Level world) {
        String name = Base64.getUrlEncoder().withoutPadding().encodeToString(world.getFolderName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return folder.resolve("round-" + name + ".log");
    }

    void remember(Path folder, Level world, Vector3 pos) {
        if (recorded.contains(pos)) return;
        var tile = world.getBlockEntity(pos);
        String nbt = "-";
        if (tile != null) {
            tile.saveNBT();
            nbt = Base64.getEncoder().encodeToString(Item.get("minecraft:air").writeCompoundTag(tile.getNbt()));
        }
        String row = pos.getFloorX()+" "+pos.getFloorY()+" "+pos.getFloorZ()+" "
                +world.getBlock(pos).getBlockState().blockStateHash()+" "+world.getBlock(pos,1).getBlockState().blockStateHash()+" "+nbt+"\n";
        try {
            Files.writeString(file(folder,world),row,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            recorded.add(pos);
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    void restore(Path folder, Level world) {
        if (world == null) return;
        Path journal = file(folder,world);
        if (!Files.exists(journal)) return;
        try {
            for (String row : Files.readAllLines(journal)) {
                String[] fields = row.split(" ");
                Vector3 pos = new Vector3(Integer.parseInt(fields[0]),Integer.parseInt(fields[1]),Integer.parseInt(fields[2]));
                var existing = world.getBlockEntity(pos);
                if (existing != null) existing.close();
                for (int layer=0;layer<2;layer++) {
                    var state = Objects.requireNonNull(Registries.BLOCKSTATE.get(Integer.parseInt(fields[3+layer])));
                    world.setBlock(pos,layer,Block.get(state),true,false);
                }
                if (!fields[5].equals("-")) {
                    var tag = Item.parseCompoundTag(Base64.getDecoder().decode(fields[5]));
                    Objects.requireNonNull(BlockEntity.createBlockEntity(tag.getString("id"),world.getChunk(pos.getFloorX()>>4,pos.getFloorZ()>>4,true),tag));
                }
            }
            if (!world.save(true)) throw new IOException("Arena konnte nicht gespeichert werden");
            Files.delete(journal);
            recorded.clear();
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
}
