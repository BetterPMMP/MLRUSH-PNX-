package mlg.rush;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.powernukkitx.Server;
import org.powernukkitx.block.*;
import org.powernukkitx.blockentity.BlockEntity;
import org.powernukkitx.level.*;
import org.powernukkitx.math.Vector3;
import org.powernukkitx.nbt.tag.CompoundTag;

final class PracticeMap {
    record Cell(BlockState ground, BlockState fluid, CompoundTag data) {}
    record Template(String name, int x, int y, int z, Location start, Vector3 goal, Map<Vector3,Cell> blocks) {
        static Template capture(Level level, int x, int y, int z, Location start, Vector3 goal) {
            Map<Vector3,Cell> blocks = new HashMap<>();
            for (int dx=-33;dx<=33;dx++) for (int dz=-14;dz<=14;dz++) for (int dy=-7;dy<=10;dy++) {
                int h=y+dy;
                if (!level.isYInRange(h)) continue;
                Block ground=level.getBlock(x+dx,h,z+dz);
                Block fluid=level.getBlock(x+dx,h,z+dz,1);
                if (ground.isAir() && fluid.isAir()) continue;
                Vector3 pos=new Vector3(x+dx,h,z+dz);
                var tile=level.getBlockEntity(pos);
                if (tile!=null) tile.saveNBT();
                blocks.put(pos,new Cell(ground.getBlockState(),fluid.getBlockState(),tile==null?null:tile.getNbt().copy()));
            }
            return new Template(level.getFolderName(),x,y,z,start,goal,blocks);
        }
    }

    final Level level;
    final Template template;
    final Set<Vector3> edits = new HashSet<>();
    int points, deaths;
    PracticeMap(Level level, Template template) {
        this.level=level;
        this.template=template;
        for(int cx=(template.x()-33)>>4;cx<=(template.x()+33)>>4;cx++)
            for(int cz=(template.z()-14)>>4;cz<=(template.z()+14)>>4;cz++) level.syncGenerateChunk(cx,cz);
        for (var entry:template.blocks().entrySet()) write(entry.getKey(),entry.getValue());
    }

    Location spawn() {
        Location src=template.start();
        return new Location(src.x,src.y,src.z,src.yaw,src.pitch,level);
    }

    boolean inside(Vector3 pos) {
        return Math.abs(pos.x-template.x())<=33 && Math.abs(pos.z-template.z())<=14 && pos.y>=template.y()-5;
    }

    void reset() {
        for (Vector3 pos:edits) write(pos,template.blocks().get(pos));
        edits.clear();
    }

    private void write(Vector3 pos, Cell cell) {
        var old=level.getBlockEntity(pos);
        if (old!=null) old.close();
        level.setBlock(pos,0,cell==null?new BlockAir():Block.get(cell.ground()),true,false);
        level.setBlock(pos,1,cell==null?new BlockAir():Block.get(cell.fluid()),true,false);
        if (cell!=null && cell.data()!=null) {
            CompoundTag tag=cell.data().copy();
            BlockEntity.createBlockEntity(tag.getString("id"),level.getChunk(pos.getFloorX()>>4,pos.getFloorZ()>>4,true),tag);
        }
    }

    static boolean dispose(Server server,String name) throws IOException {
        if (!name.matches("mlgruush_(?:practice|duel)_[0-9a-f]{32}")) throw new IOException("Ungültiger Trainingsweltname");
        Path root=Path.of(server.getDataPath(),"worlds").toRealPath();
        Path dir=root.resolve(name).normalize();
        if (!dir.getParent().equals(root) || Files.isSymbolicLink(dir)) throw new IOException("Ungültiger Trainingspfad");
        Level world=server.getLevelByName(name);
        if (world!=null && (!world.getPlayers().isEmpty() || !server.unloadLevel(world,true))) return false;
        if (!Files.exists(dir,LinkOption.NOFOLLOW_LINKS)) return true;
        Files.walkFileTree(dir,new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path file,BasicFileAttributes info) throws IOException {
                Files.delete(file);return FileVisitResult.CONTINUE;
            }
            public FileVisitResult postVisitDirectory(Path folder,IOException error) throws IOException {
                if(error!=null) throw error;
                Files.delete(folder);return FileVisitResult.CONTINUE;
            }
        });
        return true;
    }
}
