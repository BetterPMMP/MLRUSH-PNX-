package mlg.rush;

import org.powernukkitx.block.Block;
import org.powernukkitx.level.Level;
import org.powernukkitx.math.Vector3;

final class RushArena {
    static void placeBed(Level world, Vector3 foot, int team) {
        var direction = team == 0 ? org.powernukkitx.math.BlockFace.WEST : org.powernukkitx.math.BlockFace.EAST;
        var color = team == 0 ? org.powernukkitx.utils.DyeColor.RED : org.powernukkitx.utils.DyeColor.BLUE;
        for (int half=0; half<2; half++) {
            Vector3 pos = half == 0 ? foot : foot.getSide(direction);
            var part = (org.powernukkitx.block.BlockBed) Block.get("minecraft:bed", world, pos.getFloorX(), pos.getFloorY(), pos.getFloorZ());
            part.setBlockFace(direction);
            part.setHeadPiece(half == 1);
            var tag = new org.powernukkitx.nbt.tag.CompoundTag().putByte("color", color.getWoolData());
            org.powernukkitx.block.BlockEntityHolder.setBlockAndCreateEntity(part, true, false, tag);
        }
    }

    static void build(Level world, int cx, int cy, int cz) {
        Surface map = new Surface(world, cx, cy, cz);
        for (int team : new int[]{-1, 1}) {
            String color = team < 0 ? "red_concrete" : "blue_concrete";
            String trim = team < 0 ? "red_terracotta" : "blue_terracotta";
            for (int distance=14; distance<=28; distance++) {
                for (int lane=-7; lane<=7; lane++) {
                    int corner = Math.min(distance-14,28-distance) + 7-Math.abs(lane);
                    if (corner < 3) continue;
                    int x = team * distance;
                    boolean edge = distance == 14 || distance == 28 || Math.abs(lane)==7 || corner==3;
                    map.put(x,-1,lane,edge ? trim : "smooth_stone");
                    if (distance>=17 && distance<=25 && Math.abs(lane)<=4) map.put(x,-2,lane,trim);
                    String top = edge || Math.abs(lane)==4 ? color : "smooth_quartz";
                    if (distance==17 && Math.abs(lane)<=2) top=color;
                    if (distance==16 && Math.abs(lane)<=1 || distance==15 && lane==0) top=color;
                    map.put(x,0,lane,top);
                }
            }
            for (int lane : new int[]{-5,5}) {
                map.put(team*25,1,lane,color);
                map.put(team*25,2,lane,"sea_lantern");
            }
            placeBed(world,new Vector3(cx+team*25, cy+1, cz),team<0 ? 0 : 1);
        }
    }

    static void connect(Level world, int x, int y, int z) {
        if (!world.getBlock(x-14,y,z).getId().equals("minecraft:red_concrete")
                || !world.getBlock(x+14,y,z).getId().equals("minecraft:blue_concrete")) return;
        for (int step=-13; step<=13; step++) {
            Vector3 pos = new Vector3(x+step,y,z);
            if (world.getBlock(pos).isAir()) world.setBlock(pos, Block.get("minecraft:smooth_quartz"));
        }
    }

    private record Surface(Level world, int x, int y, int z) {
        void put(int dx, int dy, int dz, String id) {
            world.setBlock(new Vector3(x + dx, y + dy, z + dz), Block.get("minecraft:" + id));
        }
    }
}
