package mlg.rush;

import java.util.*;
import org.powernukkitx.inventory.fake.*;
import org.powernukkitx.item.enchantment.Enchantment;
import org.powernukkitx.event.inventory.ItemStackRequestActionEvent;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.*;
import org.powernukkitx.form.window.SimpleForm;
import org.powernukkitx.Player;
import org.powernukkitx.block.*;
import org.powernukkitx.command.*;
import org.powernukkitx.entity.Entity;
import org.powernukkitx.event.*;
import org.powernukkitx.event.block.*;
import org.powernukkitx.event.entity.*;
import org.powernukkitx.event.player.*;
import org.powernukkitx.item.Item;
import org.powernukkitx.level.*;
import org.powernukkitx.math.Vector3;
import org.powernukkitx.plugin.PluginBase;
import org.powernukkitx.entity.item.EntityArmorStand;

public final class RushGame implements Listener {
    private final leon plugin;
    private final RushGame root;
    private final List<RushGame> matches;
    private final List<String> rotation;
    private String sourceMap;

    RushGame(leon plugin,RushGame parent) {
        this.plugin=plugin;
        root=parent==null?this:parent;
        matches=parent==null?new ArrayList<>():parent.matches;
        rotation=parent==null?new ArrayList<>():parent.rotation;
        invites=parent==null?new HashMap<>():parent.invites;
        lobbyHUD=parent==null?Collections.newSetFromMap(new WeakHashMap<>()):parent.lobbyHUD;
        temporaryWorlds=parent==null?new HashSet<>():parent.temporaryWorlds;
    }

    private RushGame match(Player player) {
        if(this==root) for(RushGame game:matches) if(game.side(player)>=0) return game;
        return this;
    }

    private RushGame match(Level level) {
        if(this==root) for(RushGame game:matches) if(game.world==level) return game;
        return this;
    }

    private final Map<Player, Long> strikes = new HashMap<>();
    private final ArenaChanges changes = new ArenaChanges();
    private final Set<Vector3> built = new HashSet<>();
    private final Player[] team = new Player[2];
    private final int[] points = new int[2];
    private final int[] kills = new int[2], deaths = new int[2];
    private final long[] creditUntil = new long[2];
    private final Map<Player, org.powernukkitx.scoreboard.Scoreboard> boards = new HashMap<>();
    private Level world;
    private int x, y, z;
    private boolean classicLayout;
    private long roundReady;
    private long matchRelease;
    private int countdownShown;
    private boolean active;
    private enum Training { KLASSIK, ZEIT, KB }
    private Training training;
    private EntityArmorStand dummy;
    private double vx, vy, vz;
    private int dummyHits;
    private boolean launched;
    private long started;
    private final Map<UUID, Invite> invites;
    private record Invite(UUID challenger, long expires) {}

    private String value(String key) { return plugin.getConfig().getString(key); }
    private boolean enabled(String key) { return plugin.getConfig().getBoolean(key); }
    private int number(String key,int min,int max) {
        return Math.clamp(plugin.getConfig().getInt(key),min,max);
    }
    private double decimal(String key,double min,double max) {
        return Math.clamp(plugin.getConfig().getDouble(key),min,max);
    }
    private void message(CommandSender target,String text) {
        target.sendMessage(value("chat-format").replace("{prefix}",value("prefix")).replace("{message}",text));
    }
    private void error(CommandSender target,String text) {
        target.sendMessage(value("error-format").replace("{prefix}",value("prefix")).replace("{message}",text));
    }

    private void importPreviousConfig() {
        var folder = plugin.getDataFolder().toPath();
        var previous = folder.resolveSibling("SplashRush");
        var config = folder.resolve("config.yml");
        try {
            if (java.nio.file.Files.exists(config) && java.nio.file.Files.size(config)>0 || !java.nio.file.Files.isRegularFile(previous.resolve("config.yml"))) return;
            java.nio.file.Files.createDirectories(folder);
            try (var journals = java.nio.file.Files.newDirectoryStream(previous,"round-*.log")) {
                for (var path : journals) java.nio.file.Files.copy(path,folder.resolve(path.getFileName()),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            java.nio.file.Files.copy(previous.resolve("config.yml"),config,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    public void onEnable() {
        importPreviousConfig();
        java.io.File config = new java.io.File(plugin.getDataFolder(), "config.yml");
        if (!config.exists() || config.length() == 0) plugin.saveResource("config.yml", true);
        try(var input=plugin.getResource("config.yml")) {
            var defaults=new org.powernukkitx.utils.Config(org.powernukkitx.utils.Config.YAML);
            if(!defaults.load(input)) throw new IllegalStateException("Invalid bundled config.yml");
            boolean changed=false;
            for(var entry:defaults.getAll().entrySet()) {
                if(plugin.getConfig().exists(entry.getKey())) continue;
                plugin.getConfig().set(entry.getKey(),entry.getValue());
                changed=true;
            }
            if(changed) plugin.saveConfig();
        } catch(java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
        validateMaterials();
        temporaryWorlds.addAll(plugin.getConfig().getStringList("practice-copies"));
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin,this::cleanPractice,100);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, this::prepareMap, 1);
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::tick, 5);
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::keepDaylight, 100);
    }

    private void validateMaterials() {
        for(String key:List.of("kit-stick","kit-pickaxe","kit-block-red","kit-block-blue","lobby-sword","lobby-editor",
                "lobby-practice","lobby-leaderboard","lobby-language","editor-save-item","editor-empty-slot","palette-red_concrete","palette-blue_concrete",
                "palette-red_terracotta","palette-blue_terracotta","palette-smooth_stone","palette-smooth_quartz","palette-sea_lantern")) {
            String id=value(key);
            Item item=Item.get(id);
            if(item.isNull() || !id.equals(item.getId())) throw new IllegalArgumentException("config.yml: "+key+" = "+id);
            if((key.startsWith("kit-block-") || key.startsWith("palette-")) && item.getBlock().isAir())
                throw new IllegalArgumentException("config.yml: "+key+" requires a placeable block");
        }
    }

    private void daylight(Level level) {
        if (level == null) return;
        if (arenaWorld(level)) {
            level.getGameRules().setGameRule(GameRule.DO_MOB_SPAWNING,enabled("arena-allow-mobs"));
            level.getGameRules().setGameRule(GameRule.PVP,enabled("arena-pvp"));
            level.getGameRules().setGameRule(GameRule.FALL_DAMAGE,enabled("arena-fall-damage"));
        }
        if(!enabled("fixed-time")) {
            if(level.stopTime) level.startTime();
            level.getGameRules().setGameRule(GameRule.DO_DAYLIGHT_CYCLE,true);
            return;
        }
        int time=number("world-time",0,23999);
        if(level.getTime()!=time) level.setTime(time);
        if (!level.stopTime) level.stopTime();
        if (level.getGameRules().getBoolean(GameRule.DO_DAYLIGHT_CYCLE)) level.getGameRules().setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);
    }

    private boolean arenaWorld(Level level) {
        return level!=null && (level==world || temporaryWorlds.contains(level.getFolderName()) || plugin.getConfig().exists("map-"+level.getFolderName()));
    }

    @EventHandler
    public void creatureSpawn(CreatureSpawnEvent event) {
        if(!enabled("arena-allow-mobs") && arenaWorld(event.getPosition().level)) event.setCancelled();
    }

    @EventHandler
    public void entitySpawn(EntitySpawnEvent event) {
        if(!enabled("arena-allow-mobs") && arenaWorld(event.getPosition().level) && event.getEntity() instanceof org.powernukkitx.entity.EntityLiving
                && !(event.getEntity() instanceof Player) && !(event.getEntity() instanceof EntityArmorStand)) event.setCancelled();
    }

    private void keepDaylight() {
        List<String> lobby = plugin.getConfig().getStringList("rush-lobby");
        for (Level level : plugin.getServer().getLevels().values()) {
            String name = level.getFolderName();
            if (level == world || plugin.getConfig().exists("map-" + name) || (!lobby.isEmpty() && name.equals(lobby.getFirst()))) daylight(level);
        }
    }

    public void onDisable() {
        for(RushGame game:List.copyOf(matches)) game.end(p->tr(p,"server-shutting-down"));
        for(Player p:List.copyOf(privateGames.keySet())) finishPractice(p,true);
        cleanPractice();
        for(Player player:List.copyOf(lobbyHUD)) resetLobbyHud(player);
        if (active) end(viewer -> tr(viewer,"server-shutting-down"));
        for(Player player:List.copyOf(lobbyHUD)) resetLobbyHud(player);
    }

    private void loadArena() {
        if (world != null || !plugin.getConfig().exists("welt")) return;
        String name = plugin.getConfig().getString("welt");
        plugin.getServer().loadLevel(name);
        world = plugin.getServer().getLevelByName(name);
        if (world == null) return;
        changes.restore(plugin.getDataFolder().toPath(),world);
        daylight(world);
        x = plugin.getConfig().getInt("x");
        y = plugin.getConfig().getInt("y");
        z = plugin.getConfig().getInt("z");
        classicLayout = plugin.getConfig().getBoolean(markKey("layout",0)) || isClassicLayout(world, x, y, z);
        upgradeBeds();
        RushArena.connect(plugin.getConfig(),world,x,y,z);
        for (int dx = -width(); dx <= width(); dx++) {
            for (int dz = -depth(); dz <= depth(); dz++) {
                for (int dy = -2; dy <= 7; dy++) {
                    Vector3 pos = at(dx, dy, dz);
                    if (world.getBlock(pos).getId().matches("splashrush:(red|blue)_bridge")) world.setBlock(pos, new BlockAir());
                }
            }
        }
        clearBridge();
        String key = "map-" + name;
        if (!plugin.getConfig().exists(key)) {
            plugin.getConfig().set(key, List.of(Integer.toString(x), Integer.toString(y), Integer.toString(z)));
            plugin.saveConfig();
        }
    }

    private Vector3 at(int dx, int dy, int dz) { return new Vector3(x + dx, y + dy, z + dz); }
    private int side(Player p) { return p == team[0] ? 0 : p == team[1] ? 1 : -1; }
    private int width() { return classicLayout ? 30 : 18; }
    private int depth() { return classicLayout ? 10 : 5; }
    private void upgradeBeds() {
        for (int i=0; i<2; i++) {
            Vector3 foot = bed(i);
            if (world.getBlock(foot).getId().startsWith("splashrush:")) RushArena.placeBed(plugin.getConfig(),world, foot, i);
        }
    }

    private int bedOffset() { return classicLayout ? 25 : 15; }
    private int spawnOffset(int i) { return classicLayout ? (i == 0 ? -21 : 20) : sign(i) * 13; }

    private boolean isClassicLayout(Level level, int cx, int cy, int cz) {
        Block foot = level.getBlock(new Vector3(cx - 25, cy + 1, cz));
        return (foot instanceof BlockBed || foot.getId().equals("splashrush:red_bed"))
                && !level.getBlock(new Vector3(cx + 25, cy, cz)).isAir();
    }

    private int sign(int side) { return side == 0 ? -1 : 1; }
    private boolean bridge(String id) { return id.equals(value("kit-block-red")) || id.equals(value("kit-block-blue")); }
    private boolean region(Position pos) {
        return pos.getLevel() == world && Math.abs(pos.getFloorX() - x) <= width()
                && Math.abs(pos.getFloorZ() - z) <= depth() && pos.getFloorY() >= y - 2 && pos.getFloorY() <= y + 7;
    }

    private String defaultMap() { String name=value("default-world"); return name.matches("[A-Za-z0-9_-]{1,56}") ? name : "MLG_Rush_Map"; }

    private String mapTitle(String name) {
        var generated=java.util.regex.Pattern.compile("(?i)(?:splashrush_arena|mlgrush_splashgames|MJ_Rush_Map|MLG_Rush_Map)(?:_([0-9]+))?").matcher(name);
        if(name.equals(defaultMap())) return value("default-map-title");
        if (generated.matches()) return value("default-map-title")+(generated.group(1)==null ? "" : " "+generated.group(1));
        return name;
    }

    private void prepareMap() {
        if (world != null) return;
        if (plugin.getConfig().exists("welt")) {
            loadArena();
            return;
        }
        String name = defaultMap();
        for (int n = 2; existsMap(name); n++) name = defaultMap() + "_" + n;
        buildInVoid(name);
    }

    private boolean existsMap(String name) {
        return plugin.getServer().isLevelLoaded(name) || new java.io.File(plugin.getServer().getDataPath(), "worlds/" + name).exists();
    }

    private boolean buildInVoid(String name) {
        var generator = new org.powernukkitx.level.format.LevelConfig.GeneratorConfig()
                .name("void").seed(1L).dimensionData(DimensionEnum.OVERWORLD.getDimensionData()).preset(Map.of());
        var config = new org.powernukkitx.level.format.LevelConfig("leveldb", true, Map.of(0, generator));
        if (!plugin.getServer().generateLevel(name, config)) {
            plugin.getLogger().warning("Rush-Welt konnte nicht generiert werden: " + name);
            return false;
        }
        Level level = plugin.getServer().getLevelByName(name);
        if (level == null) return false;
        int cx=number("arena-center-x",-1000000,1000000), cy=number("arena-floor-y",8,240), cz=number("arena-center-z",-1000000,1000000);
        for (int chunkX = (cx - 30) >> 4; chunkX <= (cx + 30) >> 4; chunkX++) {
            for (int chunkZ = (cz - 10) >> 4; chunkZ <= (cz + 10) >> 4; chunkZ++)
                level.syncGenerateChunk(chunkX, chunkZ);
        }
        layout(level, cx, cy, cz);
        plugin.getLogger().info("MLG-Rush-Map erstellt: " + name);
        return true;
    }

    private boolean usableSite(Level level, int cx, int cy, int cz) {
        if (!level.isYInRange(cy - 7) || !level.isYInRange(cy + 8)) return false;
        for (int dx = -30; dx <= 30; dx++)
            for (int dz = -10; dz <= 10; dz++)
                for (int dy = -7; dy <= 8; dy++)
                    if (!level.getBlock(new Vector3(cx + dx, cy + dy, cz + dz)).isAir()) return false;
        return true;
    }

    private void chooseMap(Player p, String[] args) {
        if (args.length == 1) {
            prepareMap();
            if(world==null) error(p,tr(p,"could-not-load-the-arena")); else message(p,tr(p,"active-arena")+mapTitle(sourceMap==null?world.getFolderName():sourceMap));
            return;
        }
        if (active || !matches.isEmpty()) { error(p, tr(p,"finish-the-match-and-empty-the-queue-before")); return; }
        String name = args[1];
        if ((args.length != 2 && args.length != 5) || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            error(p, tr(p,"rush-arena-world-centerx-floory-centerz")); return;
        }
        if (args.length == 2 && world!=null && world.getFolderName().equals(name)) { loadArena(); if(world==null) error(p,tr(p,"could-not-load-world")); else message(p,tr(p,"this-arena-is-already-active")); return; }
        if (!existsMap(name)) {
            if (args.length == 5) { error(p, tr(p,"coordinates-are-for-existing-worlds-for-a-new")); return; }
            if(buildInVoid(name)) message(p,tr(p,"new-arena")+mapTitle(name)); else error(p,tr(p,"could-not-create-the-arena")); return;
        }
        plugin.getServer().loadLevel(name);
        Level level = plugin.getServer().getLevelByName(name);
        if (level == null) { error(p, tr(p,"could-not-load-world")); return; }
        String key = "map-" + name;
        List<String> saved = plugin.getConfig().getStringList(key);
        if (args.length == 2 && saved.size() == 3) {
            selectMap(level, Integer.parseInt(saved.get(0)), Integer.parseInt(saved.get(1)), Integer.parseInt(saved.get(2)));
            message(p, tr(p,"loaded-arena") + mapTitle(name)); return;
        }
        int cx, cy, cz;
        try {
            Position point = p.getLevel() == level ? p.getPosition() : level.getSpawnLocation();
            cx = args.length == 5 ? Integer.parseInt(args[2]) : point.getFloorX();
            cy = args.length == 5 ? Integer.parseInt(args[3]) : point.getFloorY() + 20;
            cz = args.length == 5 ? Integer.parseInt(args[4]) : point.getFloorZ();
        } catch (NumberFormatException ex) { error(p, tr(p,"enter-whole-number-coordinates")); return; }
        if (existingCourse(level, cx, cy, cz)) {
            selectMap(level, cx, cy, cz);
            message(p, tr(p,"selected-existing-arena") + mapTitle(name)); return;
        }
        if (!usableSite(level, cx, cy, cz)) { error(p, tr(p,"not-enough-room-choose-an-empty-spot-with")); return; }
        layout(level, cx, cy, cz);
        message(p, tr(p,"arena-ready") + mapTitle(name));
    }

    private boolean existingCourse(Level level, int cx, int cy, int cz) {
        if (isClassicLayout(level, cx, cy, cz)) return true;
        for (int s : new int[]{-1, 1}) {
            if (level.getBlock(new Vector3(cx + s * 13, cy, cz)).isAir()) return false;
            for (int a = 15; a <= 16; a++) {
                String id = level.getBlock(new Vector3(cx + s * a, cy + 1, cz)).getId();
                String foot = s < 0 ? "splashrush:red_bed" : "splashrush:blue_bed";
                if (!id.equals(foot) && !(a == 16 && id.equals(foot + "_head"))) return false;
            }
        }
        return true;
    }

    private void layout(Level level, int cx, int cy, int cz) {
        RushArena.build(plugin.getConfig(),level, cx, cy, cz);
        level.save(true);
        selectMap(level, cx, cy, cz);
    }

    private void selectMap(Level level, int cx, int cy, int cz) {
        if (world != null && !occupied(world.getFolderName())) clearBridge();
        sourceMap=null;
        world = level; changes.restore(plugin.getDataFolder().toPath(),level); daylight(level); x = cx; y = cy; z = cz;
        classicLayout = plugin.getConfig().getBoolean(markKey("layout",0)) || isClassicLayout(level, cx, cy, cz);
        upgradeBeds();
        RushArena.connect(plugin.getConfig(),world,x,y,z);
        plugin.getConfig().set("welt", level.getFolderName());
        plugin.getConfig().set("x", x); plugin.getConfig().set("y", y); plugin.getConfig().set("z", z);
        plugin.getConfig().set("map-" + level.getFolderName(), List.of(Integer.toString(x), Integer.toString(y), Integer.toString(z)));
        plugin.saveConfig();
    }

    private String locale(CommandSender sender) {
        String fallback=value("language");
        String language=sender instanceof Player p ? plugin.getConfig().getString("language-"+p.getUniqueId(),fallback) : fallback;
        return language.equals("english") ? "en" : "de";
    }

    private String tr(CommandSender sender,String key) {
        String text=value("text-"+locale(sender)+"-"+key);
        return text.contains("{") ? text.replace("{goal}",Integer.toString(number("bed-points-to-win",1,1000)))
                .replace("{invite}",Integer.toString(number("invite-seconds",1,3600))).replace("{ringouts}",Integer.toString(number("practice-ringouts",1,1000))) : text;
    }

    private void playerLanguages(Player p) {
        new SimpleForm(value("language-menu-title"),tr(p,"choose-your-language"))
                .addButton(value("language-button-de"),who->setPlayerLanguage(who,"german"))
                .addButton(value("language-button-en"),who->setPlayerLanguage(who,"english")).send(p);
    }

    private void setPlayerLanguage(Player p,String value) {
        if(!canEdit(p)) return;
        saveLanguage(p,value);
    }

    private void saveLanguage(Player p,String value) {
        String key="language-"+p.getUniqueId();
        plugin.getConfig().set(key,value);
        plugin.saveConfig();
        if(canEdit(p)) lobbyItems(p);
        if(side(p)>=0) updateBoard(side(p));
        if(privateGames.containsKey(p)) practiceBoard(p,privateGames.get(p));
        message(p,tr(p,"your-language-is-english"));
    }

    private void language(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mlgrush.lang")) { error(sender, tr(sender,"operators-only")); return; }
        if (args.length != 1 || !(args[0].equalsIgnoreCase("german") || args[0].equalsIgnoreCase("english"))) {
            error(sender, tr(sender,"language") + tr(sender,"english") + " | /mlgrush lang german / english");
            return;
        }
        if (!(sender instanceof Player p)) { error(sender,"Use this command in game."); return; }
        saveLanguage(p,args[0].toLowerCase(Locale.ROOT));

    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if(sender instanceof Player player && match(player)!=this) return match(player).onCommand(sender,cmd,label,args);
        if (label.equalsIgnoreCase("lang") || (cmd != null && cmd.getName().equalsIgnoreCase("lang"))) { language(sender,args); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("lang")) { language(sender, Arrays.copyOfRange(args,1,args.length)); return true; }
        if (!(sender instanceof Player p)) { error(sender, tr(sender,"use-this-command-in-game")); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("mlgrush")) args = Arrays.copyOfRange(args, 1, args.length);
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if(sub.isEmpty() || sub.equals("help")) { help(p); return true; }
        if (sub.equals("lobby") || sub.equals("leave") || sub.equals("hub")) {
            if (side(p) >= 0) forfeit(p);
            else if (privateGames.containsKey(p)) finishPractice(p,true);
            else {
                enterLobby(p);
                return true;
            }
            if (!lobbyHUD.contains(p) || !inLobby(p)) enterLobby(p);
            return true;
        }
        if (privateGames.containsKey(p)) {
            error(p,tr(p,"you-are-in-solo-practice-rush-leave"));
            return true;
        }
        switch (sub) {
            case "practice", "training", "traning" -> beginPractice(p);
            case "setlobby" -> {
                if (!p.hasPermission("mlgrush.admin") || side(p) >= 0) { error(p, tr(p,"only-an-op-outside-a-match-can-do")); break; }
                plugin.getConfig().set("rush-lobby", locData(p.getLocation()));
                daylight(p.getLevel());
                plugin.saveConfig();
                enterLobby(p);
                message(p, tr(p,"rush-lobby-saved"));
            }
            case "edit" -> {
                if (!p.hasPermission("mlgrush.admin") || active || !matches.isEmpty()) { error(p, tr(p,"only-an-op-can-edit-an-idle-arena")); break; }
                prepareMap();
                if (world != null) p.teleport(teamSpawn(0));
            }
            case "setbed", "setspawn" -> setMark(p, sub, args);
            case "arena" -> {
                if (!p.hasPermission("mlgrush.admin")) { error(p, tr(p,"only-operators-can-do-that")); break; }
                chooseMap(p, args);
            }
            case "classic" -> {
                if (!p.hasPermission("mlgrush.admin")) { error(p, tr(p,"only-operators-can-do-that-2")); break; }
                if (active || !matches.isEmpty()) { error(p, tr(p,"the-arena-is-busy")); break; }
                if (classicLayout) { message(p, tr(p,"the-mlg-rush-map-is-already-active")); break; }
                String key = "classic-map";
                if (plugin.getConfig().exists(key)) chooseMap(p, new String[]{"arena", plugin.getConfig().getString(key)});
                else {
                    String name = defaultMap();
                    for (int n = 2; existsMap(name); n++) name = defaultMap()+"_" + n;
                    if (buildInVoid(name)) {
                        plugin.getConfig().set(key, name); plugin.saveConfig();
                        message(p, tr(p,"mlg-rush-map") + mapTitle(name));
                    }
                }
            }
            default -> error(p, tr(p,"use-rush-help"));
        }
        return true;
    }

    private void help(Player p) {
        message(p,tr(p,"help-title"));
        message(p,"/rush help · /rush lobby (/rush hub) · /rush leave");
        if(enabled("practice-enabled")) message(p,"/rush training (/rush practice, /rush traning)");
        if(p.hasPermission("mlgrush.lang")) message(p,"/rush lang german|english (/lang german|english)");
        if(p.hasPermission("mlgrush.admin")) {
            message(p,"/rush setlobby · /rush edit · /rush classic");
            message(p,"/rush arena [world [x y z]]");
            message(p,"/rush setspawn red|blue · /rush setbed red|blue");
        }
    }

    private record Rank(String name,int wins,String id) {}

    private List<Rank> topWins() {
        var ranks=new ArrayList<Rank>();
        for(String key:plugin.getConfig().getKeys(false)) {
            if(!key.startsWith("siege-")) continue;
            String id=key.substring(6);
            int wins=plugin.getConfig().getInt(key);
            if(wins<=0) continue;
            ranks.add(new Rank(plugin.getConfig().getString("rank-name-"+id,id),wins,id));
        }
        return ranks.stream().sorted(Comparator.comparingInt(Rank::wins).reversed()
                .thenComparing(Rank::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Rank::id)).limit(10).toList();
    }

    private void leaderboard(Player p) {
        long now=System.nanoTime();
        if(!ready(p) || !inLobby(p) || !p.getFormWindows().isEmpty() || now<nextLeaderboardOpen.getOrDefault(p,0L)) return;
        nextLeaderboardOpen.put(p,now+500_000_000L);
        var rows=new ArrayList<String>();
        for(Rank rank:topWins()) rows.add("§b"+(rows.size()+1)+"  §f"+rank.name()+"  §7"+rank.wins());
        String body=rows.isEmpty()?tr(p,"leaderboard-empty"):String.join("\n",rows);
        new SimpleForm(tr(p,"leaderboard-title"),body).addButton(tr(p,"close")).send(p);
    }

    private final Map<Player, Long> nextLeaderboardOpen = new WeakHashMap<>();
    private void action(SimpleForm form, String text, String command) {
        if (command.startsWith("test ")) form.addButton(text,p -> test(p,command.substring(5)));
        else form.addButton(text, p -> plugin.getServer().executeCommand(p, "mlgrush " + command));
    }

    private void challengeHit(Player from, Player to) {
        Invite pending = invites.get(from.getUniqueId());
        if (pending != null && pending.challenger().equals(to.getUniqueId()) && System.nanoTime() < pending.expires()) {
            beginDuel(to, from);
            return;
        }
        Invite sent = invites.get(to.getUniqueId());
        if (sent != null && sent.challenger().equals(from.getUniqueId()) && System.nanoTime() < sent.expires()) return;
        invitePlayer(from,to);
    }

    private void invitePlayer(Player p, Player other) {
        if (other == null || other == p || match(p).side(p) >= 0 || match(other).side(other) >= 0) {
            error(p, tr(p,"this-player-is-unavailable")); return;
        }
        if (!eligible(p)) return;
        invites.put(other.getUniqueId(), new Invite(p.getUniqueId(), System.nanoTime() + number("invite-seconds",1,3600) * 1_000_000_000L));
        message(p, tr(p,"invitation-to") + other.getName() + tr(p,"sent-it-expires-in-invite-seconds"));
        message(other, p.getName() + tr(other,"return-sword-challenge"));
    }

    private void beginDuel(Player other, Player p) {
        prepareMap();
        if (other == null || match(p).side(p)>=0 || match(other).side(other)>=0 || world == null) { error(p, tr(p,"opponent-or-arena-unavailable")); return; }
        if (!eligible(p) || !eligible(other)) return;
        allocateDuel(other,p);
    }

    private void addStat(Player p, String stat) {
        plugin.getConfig().set("rank-name-"+p.getUniqueId(),p.getName());
        String key = stat + "-" + p.getUniqueId();
        plugin.getConfig().set(key, plugin.getConfig().getInt(key) + 1);
        plugin.saveConfig();
    }

    private void result(int winner) {
        addStat(team[winner], "siege");
        addStat(team[1 - winner], "niederlagen");
    }

    private void trainingMenu(Player p) {
        if (!p.isOp()) return;
        SimpleForm form = new SimpleForm(tr(p,"operator-practice"), tr(p,"the-arena-is-yours-during-practice-use-rush"));
        if (side(p) < 0) {
            action(form, tr(p,"bed-duel-first-to-goal"), "test klassik");
            action(form, tr(p,"bridge-time-trial"), "test zeit");
            action(form, tr(p,"knockback-knock-the-target-off-ringouts-times"), "test kb");
        } else if (training != null) {
            action(form, tr(p,"switch-sides"), "test seite");
            action(form, tr(p,"restart-attempt"), "test reset");
            if (training == Training.KLASSIK) action(form, tr(p,"simulate-an-opponent-scoring"), "test gegentor");
            action(form, tr(p,"end-practice"), "leave");
        }
        form.send(p);
    }

    private void test(Player p, String arg) {
        if (!p.isOp()) { error(p, tr(p,"solo-practice-is-for-operators")); return; }
        if (side(p) >= 0 && training != null) {
            int i = side(p);
            switch (arg) {
                case "seite" -> { kills[1-i]=kills[i]; deaths[1-i]=deaths[i]; kills[i]=0; deaths[i]=0; team[1 - i] = p; team[i] = null; resetRound(); }
                case "reset" -> resetRound();
                case "gegentor" -> { if (training == Training.KLASSIK) point(1 - i, number("bed-points-to-win",1,1000)); }
                default -> trainingMenu(p);
            }
            return;
        }
        Training mode = switch (arg) {
            case "klassik" -> Training.KLASSIK;
            case "zeit" -> Training.ZEIT;
            case "kb" -> Training.KB;
            default -> null;
        };
        if (mode == null) { trainingMenu(p); return; }
        prepareMap();
        if (world == null) return;
        if (active || occupied(world.getFolderName())) {
            error(p, tr(p,"the-arena-is-in-use")); return;
        }
        if (plugin.getConfig().exists("return-" + p.getUniqueId())) { restore(p); if (plugin.getConfig().exists("return-" + p.getUniqueId())) return; }
        start(p, null, mode);
        message(p, switch (mode) {
            case KLASSIK -> tr(p,"break-the-blue-bed-goal-times");
            case ZEIT -> tr(p,"bridge-to-the-opposing-bed-falling-restarts-the");
            case KB -> tr(p,"knock-the-target-off-ringouts-times-with-the");
        });
    }

    private final Map<Player,PracticeMap> privateGames = new HashMap<>();
    private final Set<String> temporaryWorlds;
    private PracticeMap.Template activeTemplate;

    private PracticeMap.Template pickTemplate() {
        prepareMap();
        var keys=new ArrayList<>(plugin.getConfig().getKeys(false));
        Collections.shuffle(keys);
        for (String key:keys) {
            if (!key.startsWith("map-")) continue;
            String name=key.substring(4);
            if (activeTemplate!=null && world.getFolderName().equals(name)) return activeTemplate;
            for(RushGame game:matches) if(game.activeTemplate!=null && game.world.getFolderName().equals(name)) return game.activeTemplate;
            var coords=plugin.getConfig().getStringList(key);
            if (coords.size()!=3 || !existsMap(name)) continue;
            if (!plugin.getServer().isLevelLoaded(name) && !plugin.getServer().loadLevel(name)) continue;
            Level source=plugin.getServer().getLevelByName(name);
            if (source==null) continue;
            new ArenaChanges().restore(plugin.getDataFolder().toPath(),source);
            try {
                int cx=Integer.parseInt(coords.get(0)), cy=Integer.parseInt(coords.get(1)), cz=Integer.parseInt(coords.get(2));
                String suffix="-"+name+"-"+cx+"-"+cy+"-"+cz+"-";
                boolean modern=plugin.getConfig().getBoolean("layout"+suffix+"0") || isClassicLayout(source,cx,cy,cz);
                Location start=readLocation("spawn"+suffix+"0");
                if (start==null) start=new Location(cx+(modern?-21:-13)+0.5,cy+1,cz+0.5,-90,0,source);
                var goal=plugin.getConfig().getStringList("bed"+suffix+"1");
                Vector3 bed=goal.size()==3 ? new Vector3(Integer.parseInt(goal.get(0)),Integer.parseInt(goal.get(1)),Integer.parseInt(goal.get(2))) : new Vector3(cx+(modern?25:15),cy+1,cz);
                return PracticeMap.Template.capture(source,cx,cy,cz,start,bed);
            } catch(NumberFormatException ignored) { }
        }
        return null;
    }

    private void recordWorlds() {
        plugin.getConfig().set("practice-copies",new ArrayList<>(temporaryWorlds));
        plugin.saveConfig();
    }

    private void cleanPractice() {
        boolean changed=false;
        for (String name:List.copyOf(temporaryWorlds)) {
            if (occupied(name)) continue;
            if (privateGames.values().stream().anyMatch(game->game.level.getFolderName().equals(name))) continue;
            try {
                if (PracticeMap.dispose(plugin.getServer(),name)) changed |= temporaryWorlds.remove(name);
            } catch(java.io.IOException ex) { plugin.getLogger().warning("Training "+name+": "+ex.getMessage()); }
        }
        if(changed) recordWorlds();
    }

    private void beginPractice(Player p) {
        if(!enabled("practice-enabled")) { error(p,tr(p,"practice-is-disabled")); return; }
        if (!ready(p) || side(p)>=0 || privateGames.containsKey(p) || plugin.getConfig().exists("return-"+p.getUniqueId())) return;
        var template=pickTemplate();
        if (template==null) { error(p,tr(p,"no-practice-map-available")); return; }
        String name="mlgruush_practice_"+UUID.randomUUID().toString().replace("-","");
        if (existsMap(name)) return;
        try {
            var game=createCopy(name,template);
            remember(p);
            plugin.saveConfig();
            privateGames.put(p,game);
            invites.remove(p.getUniqueId());
            invites.values().removeIf(v->v.challenger().equals(p.getUniqueId()));
            if (!p.isSurvival() && !p.setGamemode(0)) throw new IllegalStateException("Survival");
            respawnPractice(p,game);
            message(p,tr(p,"solo-practice-on")+mapTitle(template.name())+value("practice-leave-hint"));
        } catch(RuntimeException ex) {
            finishPractice(p,true);
            cleanPractice();
            plugin.getLogger().warning("Training: "+ex);
            error(p,tr(p,"could-not-start-practice"));
        }
    }

    private PracticeMap createCopy(String name,PracticeMap.Template template) {
        temporaryWorlds.add(name);
        recordWorlds();
        var generator=new org.powernukkitx.level.format.LevelConfig.GeneratorConfig().name("void").seed(1L).dimensionData(DimensionEnum.OVERWORLD.getDimensionData()).preset(Map.of());
        if (!plugin.getServer().generateLevel(name,new org.powernukkitx.level.format.LevelConfig("leveldb",true,Map.of(0,generator)))) throw new IllegalStateException("generateLevel");
        var level=Objects.requireNonNull(plugin.getServer().getLevelByName(name));
        daylight(level);
        return new PracticeMap(level,template);
    }

    private void respawnPractice(Player p,PracticeMap game) {
        if (!p.teleport(game.spawn())) throw new IllegalStateException("Teleport");
        equip(p,0);
        practiceBoard(p,game);
    }

    private void practiceBoard(Player p,PracticeMap game) {
        if(!enabled("practice-scoreboard")) return;
        var board=boards.get(p);
        boolean fresh=board==null;
        if(fresh) { board=new org.powernukkitx.scoreboard.Scoreboard("mlgpractice",value("scoreboard-title")); boards.put(p,board); }
        board.setLines(scoreLines(p,"practice",0,0,0,game.deaths,game.points));
        if(fresh) board.addViewer(p,org.powernukkitx.scoreboard.data.DisplaySlot.SIDEBAR);
    }

    private void finishPractice(Player p,boolean travel) {
        if(privateGames.remove(p)==null) return;
        var board=boards.remove(p);
        if(board!=null) board.removeViewer(p,org.powernukkitx.scoreboard.data.DisplaySlot.SIDEBAR);
        if(ready(p)) restore(p,travel);
        cleanPractice();
    }

    @EventHandler
    public void trainingHub(PlayerCommandPreprocessEvent e) {
        RushGame gameOwner=match(e.getPlayer());
        if(gameOwner!=this) { gameOwner.trainingHub(e); return; }

        String command=e.getMessage().trim().split(" +",2)[0].toLowerCase(Locale.ROOT);
        if(!(command.equals("/hub") || command.equals("/lobby"))) return;
        if(side(e.getPlayer())>=0) {
            forfeit(e.getPlayer());
            if(plugin.getServer().getCommandMap().getCommand(command.substring(1))==null) { e.setCancelled(); enterLobby(e.getPlayer()); }
            return;
        }
        if(!privateGames.containsKey(e.getPlayer())) return;
        finishPractice(e.getPlayer(),true);
        if(plugin.getServer().getCommandMap().getCommand(command.substring(1))==null) {
            e.setCancelled();
            enterLobby(e.getPlayer());
        }
    }

    private void allocateDuel(Player red,Player blue) {
        var configured=new ArrayList<String>();
        for(String key:plugin.getConfig().getKeys(false))
            if(key.startsWith("map-") && existsMap(key.substring(4))) configured.add(key.substring(4));
        rotation.retainAll(configured);
        if(enabled("shuffle-map-order")) Collections.shuffle(configured); else Collections.sort(configured);
        for(String name:configured) if(!rotation.contains(name)) rotation.add(name);
        RushGame game=active?new RushGame(plugin,this):this;
        for(String name:List.copyOf(rotation)) {
            if(occupied(name)) continue;
            var coords=plugin.getConfig().getStringList("map-"+name);
            if(coords.size()!=3) continue;
            if(!plugin.getServer().isLevelLoaded(name) && !plugin.getServer().loadLevel(name)) continue;
            var level=plugin.getServer().getLevelByName(name);
            if(level==null) continue;
            try {
                game.x=Integer.parseInt(coords.get(0));
                game.y=Integer.parseInt(coords.get(1));
                game.z=Integer.parseInt(coords.get(2));
            } catch(NumberFormatException ex) { continue; }
            game.sourceMap=name;
            game.world=level;
            game.changes.restore(plugin.getDataFolder().toPath(),level);
            game.classicLayout=plugin.getConfig().getBoolean(game.markKey("layout",0)) || game.isClassicLayout(level,game.x,game.y,game.z);
            game.daylight(level);
            rotation.remove(name);
            rotation.add(name);
            launchDuel(game,red,blue);
            return;
        }
        var template=enabled("copy-when-full") ? pickTemplate() : null;
        if(template!=null) {
            game=new RushGame(plugin,this);
            String name="mlgruush_duel_"+UUID.randomUUID().toString().replace("-","");
            try {
                game.sourceMap=template.name();
                game.world=createCopy(name,template).level;
                game.x=template.x(); game.y=template.y(); game.z=template.z();
                game.classicLayout=plugin.getConfig().getBoolean(game.markKey("layout",0)) || game.isClassicLayout(game.world,game.x,game.y,game.z);
                launchDuel(game,red,blue);
                return;
            } catch(RuntimeException ex) {
                plugin.getLogger().warning("Arena "+name+": "+ex);
                cleanPractice();
            }
        }
        for(Player p:List.of(red,blue)) {
            error(p,tr(p,"no-arena-available"));
        }
    }

    private boolean occupied(String name) {
        if(active && world.getFolderName().equals(name)) return true;
        return matches.stream().anyMatch(game->game.world.getFolderName().equals(name));
    }

    private void launchDuel(RushGame game,Player red,Player blue) {
        if(game!=this) matches.add(game);
        try { game.start(red,blue,null); }
        finally {
            if(!game.active && game!=this) { matches.remove(game); cleanPractice(); }
        }
    }

    private void start(Player red, Player blue, Training mode) {
        List<Player> players = blue == null ? List.of(red) : List.of(red, blue);
        for (Player p : players) if (!ready(p)) return;
        activeTemplate=PracticeMap.Template.capture(world,x,y,z,teamSpawn(0),bed(1));
        for (Player p : players) {
            if (mode == null) message(p, value("duel-map-announcement") + mapTitle(sourceMap==null?world.getFolderName():sourceMap));
            remember(p);
        }
        plugin.saveConfig();
        training = mode;
        team[0] = red; team[1] = blue;
        Arrays.fill(points, 0);
        Arrays.fill(kills, 0);
        Arrays.fill(deaths, 0);
        active = true;
        dummyHits = 0;
        try {
            for (Player p : players) {
                invites.remove(p.getUniqueId());
                invites.values().removeIf(v -> v.challenger().equals(p.getUniqueId()));
                if (!p.isSurvival() && !p.setGamemode(0)) throw new IllegalStateException("Survival konnte nicht aktiviert werden");
                KitBackup.empty(p);
            }
            resetRound();
            countdownShown = 0;
            int seconds = mode == null ? number("countdown-seconds",0,120) : 0;
            matchRelease = seconds == 0 ? 0 : System.nanoTime() + seconds * 1_000_000_000L;
            updateCountdownTitle();
        } catch (RuntimeException ex) {
            end(viewer -> tr(viewer,"start-cancelled-your-inventory-has-been-restored"));
            throw ex;
        }
    }

    private void forfeit(Player p) {
        if (training != null) { end(viewer -> tr(viewer,"solo-practice-finished")); return; }
        int winner = 1 - side(p);
        result(winner);
        String winnerName=team[winner].getName();
        end(viewer -> winnerName + tr(viewer,"wins-by-forfeit"));
    }

    private int elapsed() { return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000); }
    private String seconds(int ms) { return String.format(Locale.GERMANY, "%.2f s", ms / 1000.0); }

    private boolean lobbyStand(Entity entity) {
        return entity instanceof EntityArmorStand && (entity.hasTag("MLGRushLobby") || entity.hasTag("MJRushLobby") || entity.hasTag("SplashRushLobby"));
    }

    private EntityArmorStand spawnPracticeTarget(Position pos) {
        var chunk = pos.level.getChunk(pos.getFloorX() >> 4, pos.getFloorZ() >> 4, true);
        var stand = new EntityArmorStand(chunk, Entity.getDefaultNBT(pos));
        stand.setImmobile(false);
        stand.setCanBeSavedWithChunk(false);
        stand.setNameTag(tr(null,"practice-target"));
        stand.setNameTagVisible(true);
        stand.setNameTagAlwaysVisible(true);
        stand.spawnToAll();
        return stand;
    }

    private void removeDummy() {
        if (dummy != null) dummy.close();
        dummy = null;
        launched = false;
    }

    private void resetDummy() {
        removeDummy();
        if (training != Training.KB) return;
        int playerSide = team[0] == null ? 1 : 0;
        int dx = training == Training.KB ? spawnOffset(playerSide) + 1 : spawnOffset(1 - playerSide);
        Position spawn = new Position(x + dx + 0.5, y + 1, z + (classicLayout && training == Training.KB ? 5.5 : 1.5), world);
        dummy = spawnPracticeTarget(spawn);
    }

    private void advanceDummy() {
        if (dummy == null || !launched) return;
        for (int step = 0; step < 5; step++) {
            dummy.move(vx, vy, vz);
            boolean ground = dummy.isOnGround();
            vx *= ground ? 0.58 : 0.91;
            vz *= ground ? 0.58 : 0.91;
            vy = ground ? -0.08 : (vy - 0.08) * 0.98;
            if (dummy.getY() < y - 4) {
                if (training == Training.KB) point(team[0] == null ? 1 : 0, number("practice-ringouts",1,1000));
                else resetDummy();
                return;
            }
            if (ground && Math.hypot(vx, vz) < 0.01) { launched = false; break; }
        }
        dummy.updateMovement();
    }

    private String markKey(String type, int i) {
        return type + "-" + (sourceMap==null?world.getFolderName():sourceMap) + "-" + x + "-" + y + "-" + z + "-" + i;
    }

    private List<String> locData(Location pos) {
        return List.of(pos.getLevel().getFolderName(), "" + pos.x, "" + pos.y, "" + pos.z, "" + pos.yaw, "" + pos.pitch);
    }

    private Location readLocation(String key) {
        List<String> v = plugin.getConfig().getStringList(key);
        if (v.size() != 6) return null;
        Level level = plugin.getServer().getLevelByName(v.get(0));
        if (level == null && plugin.getServer().loadLevel(v.get(0))) level = plugin.getServer().getLevelByName(v.get(0));
        return level == null ? null : new Location(Double.parseDouble(v.get(1)), Double.parseDouble(v.get(2)), Double.parseDouble(v.get(3)), Double.parseDouble(v.get(4)), Double.parseDouble(v.get(5)), level);
    }

    private Position lobbySpawn() {
        Location saved = readLocation("rush-lobby");
        if (saved != null) return saved;
        Level target = plugin.getServer().getLevelByName(value("fallback-lobby-world"));
        if (target == null && plugin.getServer().loadLevel(value("fallback-lobby-world"))) target = plugin.getServer().getLevelByName(value("fallback-lobby-world"));
        if (target == null) target = plugin.getServer().getDefaultLevel();
        return target == null ? null : target.getSafeSpawn();
    }

    private boolean lobbyWorld(Player p) {
        if(side(p)>=0 || privateGames.containsKey(p)) return false;
        var saved=plugin.getConfig().getStringList("rush-lobby");
        return p.level!=null && p.level.getFolderName().equals(saved.isEmpty()?value("fallback-lobby-world"):saved.getFirst());
    }

    private boolean inLobby(Player p) {
        Position lobby = lobbySpawn();
        return !privateGames.containsKey(p) && side(p) < 0 && lobby != null && p.level == lobby.level && lobby.distanceSquared(p) <= Math.pow(number("lobby-radius",1,1024),2);
    }

    private boolean ready(Player p) {
        return p.isOnline() && p.getInventory()!=null && p.getOffhandInventory()!=null;
    }

    private boolean hasRushKit(Player p) {
        return privateGames.containsKey(p) || side(p)>=0;
    }

    private void syncSelection(Player p) {
        var inventory=p.getInventory();
        int slot=inventory.getHeldItemIndex();
        if(!kitSlot(slot)) {
            slot=kitLayout(p)[0];
            inventory.setHeldItemIndex(slot);
        }
        sendSelection(p,slot);
    }

    private void sendSelection(Player p,int slot) {
        var selection=new org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket();
        selection.setContainerID(0);
        selection.setSelectedSlot(slot);
        selection.setShouldSelectSlot(true);
        p.sendPacket(selection);
    }

    @EventHandler
    public void inventoryPacket(org.powernukkitx.event.server.PacketReceiveEvent event) {
        RushGame gameOwner=match(event.getPlayer());
        if(gameOwner!=this) { gameOwner.inventoryPacket(event); return; }

        var packet=event.getPacket();
        Player p=event.getPlayer();
        if(packet instanceof org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket && p.getInventory()==null) {
            event.setCancelled();
            return;
        }
        int slot;
        if(packet instanceof org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket bar && bar.getContainerID()==0)
            slot=bar.getSelectedSlot();
        else if(packet instanceof org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket equipment && equipment.getContainerId()==0)
            slot=equipment.getSelectedSlot();
        else return;
        if(slot>=0 && slot<=8 && !kitSlot(slot) && p.getInventory()!=null && hasRushKit(p)) {
            event.setCancelled();
            int current=p.getInventory().getHeldItemIndex();
            if(current==KIT_START+KIT_SLOTS-1 && slot==KIT_START+KIT_SLOTS) p.getInventory().setHeldItemIndex(KIT_START);
            else if(current==KIT_START && slot==KIT_START-1) p.getInventory().setHeldItemIndex(KIT_START+KIT_SLOTS-1);
            syncSelection(p);
        }
    }

    private void enterLobby(Player p) {
        Position lobby = lobbySpawn();
        if (lobby == null) { error(p, tr(p,"the-rush-lobby-has-not-been-set")); return; }
        if (!ready(p) || !p.teleport(lobby)) return;
        String backup = "lobby-kit-" + p.getUniqueId();
        if (!plugin.getConfig().exists(backup)) {
            plugin.getConfig().set(backup, KitBackup.capture(p));
            plugin.getConfig().set("lobby-slot-" + p.getUniqueId(),p.getInventory().getHeldItemIndex());
            plugin.saveConfig();
        }
        KitBackup.empty(p);
        lobbyItems(p);
        p.getInventory().setHeldItemIndex(lobbySlot("sword"));
        p.clearTitle();
        p.resetTitleSettings();
        showLobbyHud(p);
        syncLobbyInventory(p);
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (ready(p) && lobbyHUD.contains(p) && !root.match(p).hasRushKit(p) && lobbyWorld(p)) syncLobbyInventory(p);
        }, 2);
    }

    private void syncLobbyInventory(Player player) {
        player.getInventory().sendContents(player);
        sendSelection(player,player.getInventory().getHeldItemIndex());
    }

    private List<Integer> slots(String key,List<Integer> fallback,int max) {
        List<Integer> list=plugin.getConfig().getIntegerList(key);
        if(list.size()!=fallback.size() || new HashSet<>(list).size()!=list.size() || list.stream().anyMatch(i->i<1 || i>max)) return fallback;
        return list;
    }

    private int lobbySlot(String tool) {
        var positions=slots("lobby-slots",List.of(1,5,9,7),9);
        if(tool.equals("leaderboard")) {
            int slot=number("leaderboard-slot",1,9);
            while(positions.contains(slot)) slot=slot%9+1;
            return slot-1;
        }
        return positions.get(List.of("sword","editor","practice","language").indexOf(tool))-1;
    }

    private void lobbyItems(Player p) {
        p.getInventory().setItem(lobbySlot("sword"),lobbyItem("sword",tr(p,"challenge")));
        p.getInventory().setItem(lobbySlot("editor"),lobbyItem("editor",tr(p,"manage-inventory")));
        p.getInventory().setItem(lobbySlot("practice"),lobbyItem("practice",tr(p,"practice")));
        p.getInventory().setItem(lobbySlot("leaderboard"),lobbyItem("leaderboard",tr(p,"leaderboard-title")));
        p.getInventory().setItem(lobbySlot("language"),lobbyItem("language",tr(p,"language-english")));
    }

    private Item lobbyItem(String tool,String name) {
        Item item=Item.get(value("lobby-"+tool)).setCustomName(name);
        item.setNbt(item.getOrCreateNbt().putString("mlgrush_tool",tool));
        return item;
    }

    private boolean isTool(Item item,String tool) {
        return item!=null && item.hasNbt() && tool.equals(item.getNbt().getString("mlgrush_tool"));
    }

    private boolean leaveLobbyKit(Player p) {
        String backup = "lobby-kit-" + p.getUniqueId();
        if (!plugin.getConfig().exists(backup)) return false;
        resetLobbyHud(p);
        KitBackup.restore(p,plugin.getConfig().getStringList(backup));
        String slot = "lobby-slot-" + p.getUniqueId();
        p.getInventory().setHeldItemIndex(plugin.getConfig().getInt(slot));
        plugin.getConfig().remove(slot);
        plugin.getConfig().remove(backup);
        plugin.saveConfig();
        return true;
    }

    private final Set<Player> lobbyHUD;
    private final Map<Player,Long> lobbyClicks=new WeakHashMap<>();
    private void showLobbyHud(Player p) {
        lobbyHUD.add(p);
        hungerHud(p,true);
        showRushExperience(p);
    }

    private void showRushExperience(Player p) {
        p.sendExperience(0);
        p.sendExperienceLevel(lobbyHUD.contains(p) ? Math.max(0,plugin.getConfig().getInt("lobby-xp-level",2026)) : 0);
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void experienceChanged(PlayerExperienceChangeEvent event) {
        Player player=event.getPlayer();
        if(lobbyHUD.contains(player) || match(player).hasRushKit(player)) {
            plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
                if(ready(player) && (lobbyHUD.contains(player) || match(player).hasRushKit(player))) showRushExperience(player);
            }, 1);
        }
    }

    private void resetLobbyHud(Player p) {
        if(lobbyHUD.contains(p) && ready(p)) {
            hungerHud(p,false);
            lobbyHUD.remove(p);
            p.sendExperience();
            p.sendExperienceLevel();
        }
    }

    private static final int KIT_SLOTS=3;
    private static final int KIT_START=3;

    private boolean kitSlot(int slot) {
        return slot>=KIT_START && slot<KIT_START+KIT_SLOTS;
    }

    private int[] kitLayout(Player player) {
        int[] positions=layout(player);
        for(int i=0;i<positions.length;i++) positions[i]+=KIT_START;
        return positions;
    }

    private int[] layout(Player p) {
        int[] defaults=slots("kit-default-slots",List.of(1,2,3),KIT_SLOTS).stream().mapToInt(i->i-1).toArray();
        List<Integer> slots=plugin.getConfig().getIntegerList("hotbar-"+p.getUniqueId());
        if(slots.size()!=3 || new HashSet<>(slots).size()!=3 || slots.stream().anyMatch(i->i<0 || i>8)) return defaults;
        if(Collections.max(slots)<KIT_SLOTS) return slots.stream().mapToInt(Integer::intValue).toArray();
        List<Integer> order=new ArrayList<>(slots);
        Collections.sort(order);
        return new int[]{order.indexOf(slots.get(0)),order.indexOf(slots.get(1)),order.indexOf(slots.get(2))};
    }

    private String kitName(Player p,int i) {
        return switch(i) { case 0 -> tr(p,"team-wool"); case 1 -> tr(p,"stick"); default -> tr(p,"pickaxe"); };
    }

    private boolean canEdit(Player p) {
        return ready(p) && lobbyWorld(p) && inLobby(p) && plugin.getConfig().exists("lobby-kit-"+p.getUniqueId());
    }

    private Item knockbackStick() {
        Item stick=Item.get(value("kit-stick"));
        stick.addEnchantment(Enchantment.getEnchantment(Enchantment.ID_KNOCKBACK).setLevel(number("knockback-enchantment",0,10),false));
        return stick;
    }

    private void editLayout(Player p,int[] draft) {
        if(!canEdit(p) || p.getTopWindow().isPresent()) return;
        FakeInventory chest=new FakeInventory(FakeInventoryType.CHEST,tr(p,"manage-hotbar"));
        int[] selected={-1};
        int first=(9-KIT_SLOTS)/2+9;
        Runnable draw=()->{
            for(int i=0;i<KIT_SLOTS;i++) chest.setItem(first+i,Item.get(value("editor-empty-slot")).setCustomName(Integer.toString(i+1)));
            for(int i=0;i<3;i++) {
                Item icon=switch(i) { case 0 -> Item.get(value("kit-block-red")); case 1 -> knockbackStick(); default -> Item.get(value("kit-pickaxe")); };
                icon.setCustomName((selected[0]==i?value("editor-selected-format"):value("editor-slot-format")).replace("{slot}",Integer.toString(draft[i]+1)).replace("{item}",kitName(p,i)));
                icon.setLore(tr(p,"tap-two-items-to-swap"));
                chest.setItem(first+draft[i],icon);
            }
            chest.setItem(22,Item.get(value("editor-save-item")).setCustomName(tr(p,"save")));
        };
        chest.setDefaultItemHandler((inv,slot,before,after,event)->{
            event.setCancelled();
            if(!canEdit(p) || event.getPlayer()!=p) return;
            if(slot==22) {
                plugin.getConfig().set("hotbar-"+p.getUniqueId(),List.of(draft[0],draft[1],draft[2]));
                plugin.saveConfig();
                p.removeWindow(chest);
                message(p,tr(p,"hotbar-saved"));
                return;
            }
            if(slot<first || slot>=first+KIT_SLOTS) return;
            int destination=slot-first;
            int occupant=-1;
            for(int i=0;i<3;i++) if(draft[i]==destination) occupant=i;
            if(selected[0]<0) selected[0]=occupant;
            else {
                int moved=selected[0];
                if(occupant>=0) draft[occupant]=draft[moved];
                draft[moved]=destination;
                selected[0]=-1;
            }
            draw.run();
        });
        draw.run();
        p.addWindow(chest);
    }

    @EventHandler
    public void held(PlayerItemHeldEvent e) {
        RushGame gameOwner=match(e.getPlayer());
        if(gameOwner!=this) { gameOwner.held(e); return; }

        if(!kitSlot(e.getSlot()) && hasRushKit(e.getPlayer())) {
            e.setCancelled();
            syncSelection(e.getPlayer());
        }
    }

    @EventHandler
    public void moveKit(ItemStackRequestActionEvent e) {
        RushGame gameOwner=match(e.getPlayer());
        if(gameOwner!=this) { gameOwner.moveKit(e); return; }

        if(e.getAction() instanceof DropAction && dropBlocked(e.getPlayer())) {
            e.setCancelled();
            return;
        }
        if((side(e.getPlayer())>=0 || privateGames.containsKey(e.getPlayer()))
                && (e.getAction() instanceof TransferItemStackRequestAction || e.getAction() instanceof SwapAction)) e.setCancelled();
    }

    private boolean useLobbyTool(Player p,Item item) {
        if(item==null || !canEdit(p)) return false;
        boolean training=isTool(item,"practice");
        boolean editor=isTool(item,"editor");
        boolean language=isTool(item,"language");
        if(isTool(item,"leaderboard")) { leaderboard(p); return true; }
        if(!training && !editor && !language) return false;
        long now=System.nanoTime();
        if(now>=lobbyClicks.getOrDefault(p,0L) && p.getFormWindows().isEmpty()) {
            lobbyClicks.put(p,now+600_000_000L);
            if(training) beginPractice(p); else if(language) playerLanguages(p); else editLayout(p,layout(p));
        }
        return true;
    }

    private boolean sword(Item item) { return isTool(item,"sword"); }

    private final Set<Player> pendingPractice = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Player,Long> lastPlayerHit = new WeakHashMap<>();

    private boolean practiceSword(Player p) {
        return ready(p) && p.isOp() && side(p)<0 && sword(p.getItemInHand()) && inLobby(p);
    }

    private void schedulePractice(Player p) {
        if (!enabled("op-sword-practice") || !practiceSword(p) || !pendingPractice.add(p)) return;
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (pendingPractice.remove(p) && practiceSword(p) && System.nanoTime()-lastPlayerHit.getOrDefault(p,0L)>500_000_000L) test(p,"klassik");
        },3);
    }

    @EventHandler
    public void airSwing(PlayerAnimationEvent event) {
        RushGame gameOwner=match(event.getPlayer());
        if(gameOwner!=this) { gameOwner.airSwing(event); return; }

        if (event.getAnimationType()!=org.cloudburstmc.protocol.bedrock.packet.AnimatePacket.Action.SWING) return;
        var source = event.getSwingSource();
        if (source == null || source == org.cloudburstmc.protocol.bedrock.data.ActorSwingSource.NONE) schedulePractice(event.getPlayer());
    }

    @EventHandler
    public void challengeUse(PlayerInteractEvent e) {
        RushGame gameOwner=match(e.getPlayer());
        if(gameOwner==this && this==root && e.getBlock()!=null) gameOwner=match(e.getBlock().getLevel());
        if(gameOwner!=this) { gameOwner.challengeUse(e); return; }

        if(useLobbyTool(e.getPlayer(),e.getItem())) { e.setCancelled();return; }
        if (e.getBlock() instanceof BlockBed && region(e.getBlock())) { e.setCancelled(); return; }
        if (sword(e.getItem()) && inLobby(e.getPlayer())) {
            e.setCancelled();
            if (e.getAction()==PlayerInteractEvent.Action.LEFT_CLICK_AIR || e.getAction()==PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) schedulePractice(e.getPlayer());
        }
    }

    @EventHandler
    public void challengePlayer(PlayerInteractEntityEvent e) {
        RushGame gameOwner=match(e.getPlayer());
        if(gameOwner!=this) { gameOwner.challengePlayer(e); return; }

        if (lobbyStand(e.getEntity())) { e.setCancelled(); leaderboard(e.getPlayer()); return; }
        if (e.getEntity() == dummy) { e.setCancelled(); return; }

    }

    private Vector3 bed(int i) {
        List<String> v = plugin.getConfig().getStringList(markKey("bed", i));
        if (v.size() == 3) return new Vector3(Integer.parseInt(v.get(0)), Integer.parseInt(v.get(1)), Integer.parseInt(v.get(2)));
        return at(sign(i) * bedOffset(), 1, 0);
    }

    private Location teamSpawn(int i) {
        Location custom = readLocation(markKey("spawn", i));
        if (custom != null) return new Location(custom.x,custom.y,custom.z,custom.yaw,custom.pitch,world);
        double dz = 0.5;
        return new Location(x + spawnOffset(i) + 0.5, y + 1, z + dz, i == 0 ? -90 : 90, 0, world);
    }

    private void setMark(Player p, String type, String[] args) {
        if (!p.hasPermission("mlgrush.admin")) { error(p, tr(p,"only-operators-can-do-that-2")); return; }
        int i = args.length != 2 ? -1 : switch (args[1].toLowerCase(Locale.ROOT)) {
            case "red", "rot" -> 0; case "blue", "blau" -> 1; default -> -1;
        };
        if (i < 0) { error(p, "/rush " + type + tr(p,"red-or-blue")); return; }
        prepareMap();
        if (active || !matches.isEmpty() || world == null || p.level != world || !region(p)) {
            error(p, tr(p,"stand-inside-the-idle-arena-use-rush-edit")); return;
        }
        plugin.getConfig().set(markKey("layout",0), classicLayout);
        if (type.equals("setspawn")) {
            plugin.getConfig().set(markKey("spawn", i), locData(p.getLocation()));
        } else {
            Block floor = p.getTargetBlock(6);
            if (floor == null || floor.isAir()) { error(p, tr(p,"look-at-the-floor-under-the-foot-of")); return; }
            Vector3 foot = new Vector3(floor.getFloorX(), floor.getFloorY()+1, floor.getFloorZ());
            Vector3 head = foot.add(sign(i), 0, 0);
            Vector3 old = bed(i);
            for (Vector3 v : List.of(foot, head)) {
                Block target = world.getBlock(v);
                boolean movable = (v.equals(old) || v.equals(old.add(sign(i),0,0))) && target instanceof BlockBed;
                if (!region(target) || (!target.isAir() && !movable) || world.getBlock(v.add(0,-1,0)).isAir()) {
                    error(p, tr(p,"the-bed-needs-two-empty-spaces-with-solid")); return;
                }
            }
            for (Vector3 v : List.of(old, old.add(sign(i),0,0))) if (world.getBlock(v) instanceof BlockBed) world.setBlock(v,new BlockAir());
            RushArena.placeBed(plugin.getConfig(),world, foot, i);
            plugin.getConfig().set(markKey("bed",i), List.of(""+foot.getFloorX(),""+foot.getFloorY(),""+foot.getFloorZ()));
            world.save(true);
        }
        plugin.saveConfig();
        message(p, (type.equals("setspawn") ? "Spawn" : tr(p,"bed")) + tr(p,"for") + (i == 0 ? tr(p,"red") : tr(p,"blue")) + tr(p,"saved"));
    }

    private String hearts(Player p) {
        int alive=Math.max(0,Math.min(number("hearts",1,20), (int)Math.ceil(p.getHealthCurrent()/2)));
        return value("hearts-format").replace("{full}",value("heart-symbol").repeat(alive))
                .replace("{empty}",value("heart-symbol").repeat(number("hearts",1,20)-alive));
    }

    private List<String> scoreLines(Player p,String mode,int red,int blue,int kills,int deaths,int beds) {
        return plugin.getConfig().getStringList(mode+"-lines-"+locale(p)).stream().map(line->line
                .replace("{red}",Integer.toString(red)).replace("{blue}",Integer.toString(blue))
                .replace("{kills}",Integer.toString(kills)).replace("{deaths}",Integer.toString(deaths))
                .replace("{beds}",Integer.toString(beds)).replace("{hearts}",hearts(p))).toList();
    }

    private void updateBoard(int i) {
        Player p = team[i];
        if (p == null || !enabled("duel-scoreboard")) return;
        var board = boards.get(p);
        List<String> lines=scoreLines(p,"duel",points[0],points[1],kills[i],deaths[i],0);
        if (board == null) {
            board = new org.powernukkitx.scoreboard.Scoreboard("mlg_rush", value("scoreboard-title"));
            board.setLines(lines);
            boards.put(p,board);
            board.addViewer(p, org.powernukkitx.scoreboard.data.DisplaySlot.SIDEBAR);
        } else board.setLines(lines);
    }

    private void defeat(int i, boolean award) {
        deaths[i]++;
        if (award && team[1-i] != null && System.nanoTime() < creditUntil[i]) kills[1-i]++;
        if (training == Training.ZEIT) resetRound(); else spawn(i);
        updateBoard(i); updateBoard(1-i);
    }

    private boolean eligible(Player p) {
        if ((p.getGamemode() == 0 || inLobby(p)) && !plugin.getConfig().exists("return-" + p.getUniqueId())) return true;
        error(p, tr(p,"switch-to-survival-to-join-a-duel"));
        return false;
    }

    private void tick() {
        for(Player p:List.copyOf(lobbyHUD)) if(!lobbyWorld(p)) resetLobbyHud(p);
        for(Player trainee:List.copyOf(privateGames.keySet())) {
            PracticeMap game=privateGames.get(trainee);
            if(!trainee.isOnline() || trainee.level!=game.level) { finishPractice(trainee,false); continue; }
            if(!game.inside(trainee)) { game.deaths++; respawnPractice(trainee,game); }
        }
        for(RushGame game:List.copyOf(matches)) game.tickMatch();
        tickMatch();
    }

    private void updateCountdownTitle() {
        long now = System.nanoTime();
        int seconds = (int)((Math.max(0L, matchRelease - now) + 999_999_999L) / 1_000_000_000L);
        if (seconds == countdownShown) return;
        countdownShown = seconds;
        for (Player player : team) {
            if (player == null) continue;
            String title = seconds > 0 ? "§e" + seconds : tr(player,"countdown-go");
            player.sendTitle(title,"",0,seconds > 0 ? 30 : 15,0);
        }
    }

    private void tickMatch() {
        if (!active) return;
        updateCountdownTitle();
        advanceDummy();
        if (!active) return;
        for (int i = 0; i < 2; i++) {
            Player p = team[i];
            if (p == null) continue;
            if (countdown()) continue;
            if(p.getLevel()!=world) { forfeit(p); return; }
            if (p.getY() < y - 5 || Math.abs(p.getX() - x) > width() + 3 || Math.abs(p.getZ() - z) > depth() + 4) {
                defeat(i, p.getLevel() == world && p.getY() < y - 5);
            }
            if (training != Training.ZEIT && training != Training.KB) continue;
            String status = training == Training.ZEIT ? tr(p,"time") + seconds(elapsed()) :
                    tr(p,"hits") + dummyHits + " | Ring-outs " + points[i] + "/" + number("practice-ringouts",1,1000);
            p.sendTip(status);
        }
    }

    private void remember(Player p) {
        String key = "return-" + p.getUniqueId();
        plugin.getConfig().set(key, List.of(p.getLevel().getFolderName(), Double.toString(p.getX()), Double.toString(p.getY()), Double.toString(p.getZ()),
                Integer.toString(p.getGamemode()), Integer.toString(p.getInventory().getHeldItemIndex()), Double.toString(p.getYaw()), Double.toString(p.getPitch()), Integer.toString(p.getHealthMax()), Float.toString(p.getHealthCurrent()), Integer.toString(p.getFoodData().getFood()), Float.toString(p.getFoodData().getSaturation())));
        plugin.getConfig().set("kit-" + p.getUniqueId(), KitBackup.capture(p));
    }

    private void stripKit(Player p) {
        for (var entry : p.getInventory().getContents().entrySet()) {
            if (entry.getValue().getId().startsWith("splashrush:")) p.getInventory().clear(entry.getKey());
        }
        for (var entry : p.getOffhandInventory().getContents().entrySet()) {
            if (entry.getValue().getId().startsWith("splashrush:")) p.getOffhandInventory().clear(entry.getKey());
        }
    }

    private void restore(Player p) { restore(p,true); }

    private void restore(Player p,boolean travel) {
        String key = "return-" + p.getUniqueId();
        if (!plugin.getConfig().exists(key)) return;
        List<String> pos = plugin.getConfig().getStringList(key);
        plugin.getServer().loadLevel(pos.get(0));
        Level level = plugin.getServer().getLevelByName(pos.get(0));
        stripKit(p);
        Position dest = level == null ? plugin.getServer().getDefaultLevel().getSafeSpawn() :
                new Location(Double.parseDouble(pos.get(1)), Double.parseDouble(pos.get(2)), Double.parseDouble(pos.get(3)), pos.size() >= 8 ? Double.parseDouble(pos.get(6)) : 0, pos.size() >= 8 ? Double.parseDouble(pos.get(7)) : 0, level);
        if (travel && !p.teleport(dest)) return;
        if (pos.size() >= 6) {
            p.setGamemode(Integer.parseInt(pos.get(4)));
            KitBackup.restore(p, plugin.getConfig().getStringList("kit-" + p.getUniqueId()));
            p.getInventory().setHeldItemIndex(Integer.parseInt(pos.get(5)));
            plugin.getConfig().remove("kit-" + p.getUniqueId());
        }
        if (pos.size() >= 10) {
            p.setHealthMax(Integer.parseInt(pos.get(8)));
            p.setHealthCurrent(Float.parseFloat(pos.get(9)));
        }
        hungerHud(p,false);
        p.clearTitle();
        p.resetTitleSettings();
        p.sendExperience();
        p.sendExperienceLevel();
        if (pos.size() >= 12) {
            var food = p.getFoodData();
            food.setFood(Integer.parseInt(pos.get(10)), Float.parseFloat(pos.get(11)));
        }
        if (travel && (plugin.getConfig().exists("rush-lobby") || plugin.getConfig().exists("lobby-kit-"+p.getUniqueId()))) enterLobby(p);
        else if(!travel && !inLobby(p)) leaveLobbyKit(p);
        plugin.getConfig().remove(key);
        plugin.saveConfig();
    }

    @EventHandler
    public void foodChange(PlayerFoodLevelChangeEvent e) {
        RushGame gameOwner=match(e.getPlayer());
        if(gameOwner!=this) { gameOwner.foodChange(e); return; }

        if (side(e.getPlayer()) >= 0 || privateGames.containsKey(e.getPlayer())) {
            if(!enabled("freeze-hunger")) return;
            e.setFoodLevel(number("kit-food",0,20));
            e.setFoodSaturationLevel(number("kit-food",0,20));
        }
    }

    private void hungerHud(Player p, boolean hide) {
        var packet = new org.cloudburstmc.protocol.bedrock.packet.SetHudPacket();
        String key=lobbyHUD.contains(p) ? "lobby-hide-hud" : "kit-hide-hud";
        if(key.equals("kit-hide-hud")) packet.getHudElementList().add(org.cloudburstmc.protocol.bedrock.data.HudElement.PROGRESS_BAR);
        for(String name:plugin.getConfig().getStringList(key)) {
            try { packet.getHudElementList().add(org.cloudburstmc.protocol.bedrock.data.HudElement.valueOf(name)); }
            catch(IllegalArgumentException ignored) { }
        }
        packet.setHudVisible(hide ? org.cloudburstmc.protocol.bedrock.data.HudVisibility.HIDE : org.cloudburstmc.protocol.bedrock.data.HudVisibility.RESET);
        p.sendPacket(packet);
    }

    @EventHandler
    public void naturalHealing(EntityRegainHealthEvent event) {
        if(event.getEntity() instanceof Player owner && match(owner)!=this) { match(owner).naturalHealing(event); return; }

        if (event.getEntity() instanceof Player p && (side(p)>=0 || privateGames.containsKey(p)) && !enabled("natural-healing") && (event.getRegainReason()==EntityRegainHealthEvent.CAUSE_REGEN || event.getRegainReason()==EntityRegainHealthEvent.CAUSE_EATING)) event.setCancelled();
    }

    private void spawn(int i) {
        Player p = team[i];
        p.teleport(teamSpawn(i));
        creditUntil[i] = 0;
        equip(p,i);
    }

    private void equip(Player p,int i) {
        resetLobbyHud(p);
        int[] kit=kitLayout(p);
        p.setHealthMax(2 * number("hearts",1,20));
        p.setHealthCurrent(2 * number("hearts",1,20));
        p.getFoodData().setFood(number("kit-food",0,20),number("kit-food",0,20));
        hungerHud(p,true);
        showRushExperience(p);
        p.setMotion(new Vector3(0, 0, 0));
        KitBackup.empty(p);
        p.getInventory().setItem(kit[1], knockbackStick());
        p.getInventory().setItem(kit[2], Item.get(value("kit-pickaxe")));
        Item blocks = Item.get(i == 0 ? value("kit-block-red") : value("kit-block-blue"));
        blocks.setCount(number("kit-block-count",1,64));
        p.getInventory().setItem(kit[0], blocks);
        p.getInventory().setHeldItemIndex(kit[0]);
        syncSelection(p);
        Level arena=p.getLevel();
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin,()->{
            if(ready(p) && p.getLevel()==arena && hasRushKit(p)) {
                p.getInventory().sendContents(p);
                syncSelection(p);
            }
        }, 2);
    }

    private void clearBridge() {
        changes.restore(plugin.getDataFolder().toPath(), world);
        built.clear();
    }

    private void resetRound() {
        clearBridge();
        strikes.clear();
        roundReady = System.nanoTime() + number("respawn-protection-ms",0,5000) * 1_000_000L;
        started = System.nanoTime();
        for (int i = 0; i < 2; i++) if (team[i] != null) { spawn(i); updateBoard(i); }
        resetDummy();
    }

    private void end(java.util.function.Function<Player,String> notice) {
        activeTemplate=null;
        active = false;
        matchRelease = 0;
        training = null;
        removeDummy();
        clearBridge();
        for (int i = 0; i < 2; i++) {
            Player p = team[i];
            team[i] = null;
            var board = boards.remove(p);
            if (board != null) board.removeViewer(p, org.powernukkitx.scoreboard.data.DisplaySlot.SIDEBAR);
            if (p != null && ready(p)) { restore(p); message(p, notice.apply(p)); }
        }
        strikes.clear();
        sourceMap=null;
        if(this!=root) {
            matches.remove(this);
            root.cleanPractice();
        }
    }

    @EventHandler
    public void place(BlockPlaceEvent e) {
        RushGame gameOwner=match(e.getPlayer());
        if(gameOwner==this && this==root && e.getBlock()!=null) gameOwner=match(e.getBlock().getLevel());
        if(gameOwner!=this) { gameOwner.place(e); return; }

        if(enabled("lobby-protect-build") && lobbyWorld(e.getPlayer()) && !e.getPlayer().isOp()) { e.setCancelled(); return; }
        var game=privateGames.get(e.getPlayer());
        if(game!=null) {
            Block b=e.getBlock();
            if(b.level!=game.level || !game.inside(b) || !e.getBlockReplace().isAir() || !b.getId().equals(value("kit-block-red"))) {e.setCancelled();return;}
            game.edits.add(new Vector3(b.getFloorX(),b.getFloorY(),b.getFloorZ()));
            Player p=e.getPlayer();
            plugin.getServer().getScheduler().scheduleDelayedTask(plugin,()->{
                if(enabled("refill-blocks") && privateGames.get(p)==game && ready(p)) p.getInventory().setItem(kitLayout(p)[0],Item.get(value("kit-block-red"),0,number("kit-block-count",1,64)));
            },1);
            return;
        }
        Block block = e.getBlock();
        int i = side(e.getPlayer());
        if (i >= 0 && countdown()) { e.setCancelled(); return; }
        if (i < 0 && !region(block)) return;
        if (i < 0 || !buildArea(block) || !block.getId().equals(i == 0 ? value("kit-block-red") : value("kit-block-blue"))
                || !e.getBlockReplace().isAir() || blocksSpawn(block)) { e.setCancelled(); return; }
        Vector3 pos = new Vector3(block.getFloorX(),block.getFloorY(),block.getFloorZ());
        changes.remember(plugin.getDataFolder().toPath(),world,pos);
        built.add(pos);
        Player builder = e.getPlayer();
        long round = started;
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (!enabled("refill-blocks") || !active || started != round || side(builder) != i || !builder.isOnline()) return;
            Item supply = Item.get(i == 0 ? value("kit-block-red") : value("kit-block-blue"));
            for (var entry : builder.getInventory().getContents().entrySet()) {
                if (entry.getValue().getId().equals(supply.getId())) {
                    supply.setCount(number("kit-block-count",1,64));
                    builder.getInventory().setItem(entry.getKey(), supply);
                    return;
                }
            }
            supply.setCount(number("kit-block-count",1,64));
            builder.getInventory().setItem(kitLayout(builder)[0],supply);
        }, 1);
    }

    private boolean countdown() { return active && System.nanoTime() < matchRelease; }

    @EventHandler
    public void countdownMove(PlayerMoveEvent e) {
        RushGame gameOwner=match(e.getPlayer());
        if(gameOwner!=this) { gameOwner.countdownMove(e); return; }

        if (side(e.getPlayer()) >= 0 && countdown() && e.getFrom().distanceSquared(e.getTo()) > 0.0001) e.setCancelled();
    }

    @EventHandler
    public void mine(BlockBreakEvent e) {
        RushGame gameOwner=match(e.getPlayer());
        if(gameOwner==this && this==root && e.getBlock()!=null) gameOwner=match(e.getBlock().getLevel());
        if(gameOwner!=this) { gameOwner.mine(e); return; }

        if(enabled("lobby-protect-build") && lobbyWorld(e.getPlayer()) && !e.getPlayer().isOp()) { e.setCancelled(); return; }
        var game=privateGames.get(e.getPlayer());
        if(game!=null) {
            e.setCancelled();
            Block block=e.getBlock();
            if(block.level!=game.level || !game.inside(block)) return;
            Vector3 pos=new Vector3(block.getFloorX(),block.getFloorY(),block.getFloorZ());
            Vector3 goal=game.template.goal();
            if(block instanceof BlockBed) {
                if(pos.equals(goal)||pos.equals(goal.add(1,0,0))) {game.points++;game.reset();respawnPractice(e.getPlayer(),game);}
                return;
            }
            if(!enabled("arena-mine-terrain") && !game.edits.contains(pos)) return;
            if(pos.add(0,1,0).equals(game.spawn().floor()) || game.level.getBlock(pos.add(0,1,0)) instanceof BlockBed) return;
            game.edits.add(pos);
            var tile=game.level.getBlockEntity(pos);
            if(tile!=null) tile.close();
            game.level.setBlock(pos,new BlockAir(),true,false);
            return;
        }
        Block b = e.getBlock();
        int i = side(e.getPlayer());
        if (i < 0 && !region(b)) return;
        e.setCancelled();
        if (countdown()) return;
        if (i < 0 || !buildArea(b)) return;
        Vector3 pos = new Vector3(b.getFloorX(), b.getFloorY(), b.getFloorZ());
        if (!(b instanceof BlockBed)) {
            if(!enabled("arena-mine-terrain") && !built.contains(pos)) return;
            if (!region(b) && !built.contains(pos)) return;
            for (int teamIndex=0;teamIndex<2;teamIndex++) {
                Vector3 above = pos.add(0,1,0);
                Vector3 foot = bed(teamIndex);
                if (above.equals(foot) || above.equals(foot.add(sign(teamIndex),0,0)) || above.equals(teamSpawn(teamIndex).floor())) return;
            }
            changes.remember(plugin.getDataFolder().toPath(),world,pos);
            var tile = world.getBlockEntity(pos);
            if (tile != null) tile.close();
            world.setBlock(pos,new BlockAir(),true,false);
            built.remove(pos);
            return;
        }
        if ((!pos.equals(bed(1-i)) && !pos.equals(bed(1-i).add(sign(1-i), 0, 0))) || System.nanoTime() < roundReady) return;
        if (training == Training.KB) return;
        if (training == Training.ZEIT) {
            int ms = elapsed();
            String key = "best-" + team[i].getUniqueId();
            int best = plugin.getConfig().getInt(key);
            if (best == 0 || ms < best) { plugin.getConfig().set(key, ms); plugin.saveConfig(); }
            end(viewer -> tr(viewer,"finished-in") + seconds(ms) + tr(viewer,"best") + seconds(plugin.getConfig().getInt(key)));
            return;
        }
        if (training == null) addStat(team[i], "betten");
        point(i, number("bed-points-to-win",1,1000));
    }

    private void point(int i, int goal) {
        points[i]++;
        String name = team[i] == null ? tr(null,"practice-opponent") : team[i].getName();
        for (Player p : team) if (p != null) message(p, tr(p,"point-for") + name + "! " + points[0] + " : " + points[1]);
        if (points[i] < goal) { resetRound(); return; }
        if (training == null) result(i);
        end(viewer -> name + tr(viewer,"wins-2"));
    }

    @EventHandler
    public void damage(EntityDamageEvent e) {
        if(e.getEntity() instanceof Player owner && match(owner)!=this) { match(owner).damage(e); return; }

        Entity attacker = e instanceof EntityDamageByEntityEvent hit ? hit.getDamager() : null;
        if(attacker instanceof Player player && match(player)!=this) { e.setCancelled(); return; }
        if(e.getCause()==EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p && (side(p)>=0 || privateGames.containsKey(p))) {
            if(e.isCancelled()) return;
            float damage=e.getFinalDamage();
            e.setCancelled();
            PracticeMap game=privateGames.get(p);
            if(!enabled("arena-fall-damage") || damage<=0 || game==null && countdown()) return;
            if(p.getHealthCurrent()<=damage) {
                if(game==null) defeat(side(p),true);
                else { game.deaths++; respawnPractice(p,game); }
            } else {
                p.setHealthCurrent(p.getHealthCurrent()-damage);
                if(game==null) updateBoard(side(p)); else practiceBoard(p,game);
            }
            return;
        }
        if(e.getEntity() instanceof Player targetPlayer && privateGames.containsKey(targetPlayer) || attacker instanceof Player trainee && privateGames.containsKey(trainee)) { e.setCancelled(); return; }
        if (attacker instanceof Player from && ready(from) && sword(from.getItemInHand()) && inLobby(from)) {
            e.setCancelled();
            if (e.getEntity() instanceof Player to) {
                pendingPractice.remove(from);
                lastPlayerHit.put(from,System.nanoTime());
                if (ready(to) && inLobby(to)) challengeHit(from,to);
            } else if (from.isOp()) schedulePractice(from);
            else if (lobbyStand(e.getEntity())) leaderboard(from);
            return;
        }
        if(e.getEntity() instanceof Player lobbyPlayer && lobbyWorld(lobbyPlayer) && enabled("lobby-protect-damage")) {
            e.setCancelled();
            if(e.getCause()==EntityDamageEvent.DamageCause.VOID && ready(lobbyPlayer)) enterLobby(lobbyPlayer);
            return;
        }
        if (dummy != null && e.getEntity() == dummy) {
            e.setCancelled();
            if (attacker instanceof Player p && side(p) >= 0) strike(p, dummy);
            return;
        }
        if (lobbyStand(e.getEntity())) {
            e.setCancelled();
            if (attacker instanceof Player p) leaderboard(p);
            return;
        }
        if (!(e.getEntity() instanceof Player target) || side(target) < 0) {
            if (attacker instanceof Player p && (side(p) >= 0 || privateGames.containsKey(p))) e.setCancelled();
            return;
        }
        e.setCancelled();
        if (!(attacker instanceof Player p) || side(p) != 1 - side(target)) return;
        if(enabled("arena-pvp")) strike(p,target);
    }

    private void strike(Player p, Entity target) {
        String id = p.getItemInHand().getId();
        if (target.getLevel()!=p.getLevel() || target==dummy && !id.equals(value("kit-stick"))) return;
        long now = System.nanoTime();
        if (countdown() || now < roundReady || now < strikes.getOrDefault(p, 0L)) return;
        double dx = target.getX() - p.getX(), dz = target.getZ() - p.getZ();
        double distance = Math.hypot(dx, dz);
        if (distance < 0.001) return;
        strikes.put(p, now + number("hit-cooldown-ms",0,5000) * 1_000_000L);
        double force = decimal("knockback-base",0,5) + decimal("knockback-per-level",0,5) * p.getItemInHand().getEnchantmentLevel(Enchantment.ID_KNOCKBACK);
        Vector3 impulse = new Vector3(force * dx / distance, decimal("knockback-vertical",0,3), force * dz / distance);
        if (target == dummy) {
            vx = impulse.x; vy = impulse.y; vz = impulse.z;
            launched = true;
            dummyHits++;
            p.sendActionBar(tr(p,"hits") + dummyHits + " · " + "Stick");
        } else if (target instanceof Player victim) {
            int i = side(victim);
            creditUntil[i] = now + number("kill-credit-seconds",0,120) * 1_000_000_000L;
            if (victim.getHealthCurrent() <= number("hit-damage",0,40)) { defeat(i, true); return; }
            victim.setHealthCurrent(victim.getHealthCurrent() - number("hit-damage",0,40));
            updateBoard(side(victim));
            victim.setMotion(impulse);
        }
    }

    @EventHandler
    public void drop(PlayerDropItemEvent e) {
        RushGame gameOwner=match(e.getPlayer());
        if(gameOwner!=this) { gameOwner.drop(e); return; }
        if(dropBlocked(e.getPlayer())) e.setCancelled();
    }

    private boolean dropBlocked(Player player) {
        return (enabled("block-non-op-drops") && !player.isOp()) || (enabled("protect-kit-drops") && hasRushKit(player)) || (enabled("protect-lobby-drops") && lobbyHUD.contains(player));
    }

    private boolean buildArea(Block block) {
        return block.level == world && Math.abs(block.getFloorX()-x) <= width()+3
                && Math.abs(block.getFloorZ()-z) <= depth()+4 && block.getFloorY() >= y-5;
    }

    private boolean blocksSpawn(Block b) {
        for (int i = 0; i < 2; i++) {
            Location spot = teamSpawn(i);
            if (b.getFloorX() == spot.getFloorX() && b.getFloorZ() == spot.getFloorZ()
                    && b.getFloorY() >= spot.getFloorY() && b.getFloorY() <= spot.getFloorY()+1) return true;
        }
        return false;
    }

    @EventHandler
    public void pickup(org.powernukkitx.event.inventory.InventoryPickupItemEvent e) {
        if(e.getInventory().getHolder() instanceof Player owner && match(owner)!=this) { match(owner).pickup(e); return; }

        if (e.getInventory().getHolder() instanceof Player p && (side(p) >= 0 || privateGames.containsKey(p))) e.setCancelled();
    }

    @EventHandler
    public void login(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> welcome(p), 1);
    }

    private void welcome(Player p) {
        if(!p.isOnline()) return;
        if(!ready(p)) {
            plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> welcome(p), 5);
            return;
        }
        String id=p.getUniqueId().toString();
        if(plugin.getConfig().exists("siege-"+id) && !p.getName().equals(plugin.getConfig().getString("rank-name-"+id))) {
            plugin.getConfig().set("rank-name-"+id,p.getName());
            plugin.saveConfig();
        }
        joinSpawn(p);
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            if(canEdit(p) && !plugin.getConfig().exists("language-"+p.getUniqueId())) playerLanguages(p);
        }, 10);
    }

    private void joinSpawn(Player p) {
        if (!ready(p) || side(p) >= 0) return;
        String key = "return-" + p.getUniqueId();
        if (plugin.getConfig().exists(key)) {
            restore(p);
            if (plugin.getConfig().exists(key)) return;
            if (plugin.getConfig().exists("lobby-kit-"+p.getUniqueId())) return;
        }
        enterLobby(p);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        RushGame gameOwner=match(e.getPlayer());
        if(gameOwner!=this) { gameOwner.quit(e); return; }

        finishPractice(e.getPlayer(),true);
        Player p = e.getPlayer();
        invites.remove(p.getUniqueId());
        invites.values().removeIf(v -> v.challenger().equals(p.getUniqueId()));
        if (side(p) >= 0) forfeit(p);

    }
}
