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
        waiting=parent==null?new LinkedHashSet<>():parent.waiting;
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

    private final LinkedHashSet<Player> waiting;

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


    private static final String PREFIX = "§bMLG§fRUSH";
    private static final String GAME = "MLGRUUSH";

    private void message(CommandSender target, String text) {
        target.sendMessage("§b✦ " + PREFIX + " §8- §7" + text);
    }

    private void error(CommandSender target,String text) {
        target.sendMessage("§b✦ " + PREFIX + " §8- §c" + text);
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
        if(!plugin.getConfig().exists("lobby-xp-level")) { plugin.getConfig().set("lobby-xp-level",2026); plugin.saveConfig(); }
        temporaryWorlds.addAll(plugin.getConfig().getStringList("practice-copies"));
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin,this::cleanPractice,100);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, this::prepareMap, 1);
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::tick, 5);
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::keepDaylight, 100);
    }

    private void daylight(Level level) {
        if (level == null) return;
        if (arenaWorld(level)) {
            level.getGameRules().setGameRule(GameRule.DO_MOB_SPAWNING,false);
            level.getGameRules().setGameRule(GameRule.PVP,true);
            level.getGameRules().setGameRule(GameRule.FALL_DAMAGE,true);
        }
        if (level.getTime() != Level.TIME_NOON) level.setTime(Level.TIME_NOON);
        if (!level.stopTime) level.stopTime();
        if (level.getGameRules().getBoolean(GameRule.DO_DAYLIGHT_CYCLE)) level.getGameRules().setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);
    }

    private boolean arenaWorld(Level level) {
        return level!=null && (level==world || temporaryWorlds.contains(level.getFolderName()) || plugin.getConfig().exists("map-"+level.getFolderName()));
    }

    @EventHandler
    public void creatureSpawn(CreatureSpawnEvent event) {
        if(arenaWorld(event.getPosition().level)) event.setCancelled();
    }

    @EventHandler
    public void entitySpawn(EntitySpawnEvent event) {
        if(arenaWorld(event.getPosition().level) && event.getEntity() instanceof org.powernukkitx.entity.EntityLiving
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
        for(RushGame game:List.copyOf(matches)) game.end(p->tr(p,"Server wird beendet.","Server shutting down."));
        for(Player p:List.copyOf(privateGames.keySet())) finishPractice(p,true);
        cleanPractice();
        for(Player player:List.copyOf(lobbyHUD)) resetLobbyHud(player);
        if (active) end(viewer -> tr(viewer,"Server wird beendet.","Server shutting down."));
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
        RushArena.connect(world,x,y,z);
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
            if (world.getBlock(foot).getId().startsWith("splashrush:")) RushArena.placeBed(world, foot, i);
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
    private boolean bridge(String id) { return id.equals("minecraft:red_wool") || id.equals("minecraft:blue_wool"); }
    private boolean region(Position pos) {
        return pos.getLevel() == world && Math.abs(pos.getFloorX() - x) <= width()
                && Math.abs(pos.getFloorZ() - z) <= depth() && pos.getFloorY() >= y - 2 && pos.getFloorY() <= y + 7;
    }

    private final String defaultMap = "MLG_Rush_Map";

    private String mapTitle(String name) {
        var generated=java.util.regex.Pattern.compile("(?i)(?:splashrush_arena|mlgrush_splashgames|MJ_Rush_Map|MLG_Rush_Map)(?:_([0-9]+))?").matcher(name);
        if (generated.matches()) return generated.group(1)==null ? "MLG Rush Map" : "MLG Rush Map "+generated.group(1);
        return name;
    }

    private void prepareMap() {
        if (world != null) return;
        if (plugin.getConfig().exists("welt")) {
            loadArena();
            return;
        }
        String name = defaultMap;
        for (int n = 2; existsMap(name); n++) name = defaultMap + "_" + n;
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
        int cx = 80, cy = 80, cz = 80;
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
            if(world==null) error(p,tr(p,"Die Arena konnte nicht geladen werden.","Could not load the arena.")); else message(p,tr(p,"Aktive Rush-Map: ","Active arena: ")+mapTitle(sourceMap==null?world.getFolderName():sourceMap));
            return;
        }
        if (active || !matches.isEmpty() || !waiting.isEmpty()) { error(p, tr(p,"Mapwechsel erst bei freier Arena und leerer Warteschlange.","Finish the match and empty the queue before switching arenas.")); return; }
        String name = args[1];
        if ((args.length != 2 && args.length != 5) || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            error(p, tr(p,"/rush arena <Weltname> [MitteX BodenY MitteZ]","/rush arena <world> [centerX floorY centerZ]")); return;
        }
        if (args.length == 2 && world!=null && world.getFolderName().equals(name)) { loadArena(); if(world==null) error(p,tr(p,"Welt konnte nicht geladen werden.","Could not load world.")); else message(p,tr(p,"Diese Map ist bereits aktiv.","This arena is already active.")); return; }
        if (!existsMap(name)) {
            if (args.length == 5) { error(p, tr(p,"Koordinaten sind für bestehende Welten. Für eine neue Void-Map reicht ihr Name.","Coordinates are for existing worlds. For a new void arena, use its name.")); return; }
            if(buildInVoid(name)) message(p,tr(p,"Neue Rush-Map aktiv: ","New arena: ")+mapTitle(name)); else error(p,tr(p,"Map konnte nicht erstellt werden.","Could not create the arena.")); return;
        }
        plugin.getServer().loadLevel(name);
        Level level = plugin.getServer().getLevelByName(name);
        if (level == null) { error(p, tr(p,"Welt konnte nicht geladen werden.","Could not load world.")); return; }
        String key = "map-" + name;
        List<String> saved = plugin.getConfig().getStringList(key);
        if (args.length == 2 && saved.size() == 3) {
            selectMap(level, Integer.parseInt(saved.get(0)), Integer.parseInt(saved.get(1)), Integer.parseInt(saved.get(2)));
            message(p, tr(p,"Rush-Map geladen: ","Loaded arena: ") + mapTitle(name)); return;
        }
        int cx, cy, cz;
        try {
            Position point = p.getLevel() == level ? p.getPosition() : level.getSpawnLocation();
            cx = args.length == 5 ? Integer.parseInt(args[2]) : point.getFloorX();
            cy = args.length == 5 ? Integer.parseInt(args[3]) : point.getFloorY() + 20;
            cz = args.length == 5 ? Integer.parseInt(args[4]) : point.getFloorZ();
        } catch (NumberFormatException ex) { error(p, tr(p,"Bitte ganze Koordinaten angeben.","Enter whole-number coordinates.")); return; }
        if (existingCourse(level, cx, cy, cz)) {
            selectMap(level, cx, cy, cz);
            message(p, tr(p,"Vorhandene Rush-Map übernommen: ","Selected existing arena: ") + mapTitle(name)); return;
        }
        if (!usableSite(level, cx, cy, cz)) { error(p, tr(p,"Zu wenig freie Fläche. Wähle eine freie Mitte mit /rush arena <Welt> <X> <Y> <Z>.","Not enough room. Choose an empty spot with /rush arena <world> <X> <Y> <Z>.")); return; }
        layout(level, cx, cy, cz);
        message(p, tr(p,"Rush-Map eingerichtet: ","Arena ready: ") + mapTitle(name));
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
        RushArena.build(level, cx, cy, cz);
        level.save(true);
        selectMap(level, cx, cy, cz);
    }

    private void selectMap(Level level, int cx, int cy, int cz) {
        if (world != null && !occupied(world.getFolderName())) clearBridge();
        sourceMap=null;
        world = level; changes.restore(plugin.getDataFolder().toPath(),level); daylight(level); x = cx; y = cy; z = cz;
        classicLayout = plugin.getConfig().getBoolean(markKey("layout",0)) || isClassicLayout(level, cx, cy, cz);
        upgradeBeds();
        RushArena.connect(world,x,y,z);
        plugin.getConfig().set("welt", level.getFolderName());
        plugin.getConfig().set("x", x); plugin.getConfig().set("y", y); plugin.getConfig().set("z", z);
        plugin.getConfig().set("map-" + level.getFolderName(), List.of(Integer.toString(x), Integer.toString(y), Integer.toString(z)));
        plugin.saveConfig();
    }


    String tr(String german, String english) {
        return plugin.getConfig().getString("language", "german").equals("english") ? english : german;
    }

    private String tr(CommandSender sender,String german,String english) {
        String fallback=plugin.getConfig().getString("language","german");
        String lang=sender instanceof Player p ? plugin.getConfig().getString("language-"+p.getUniqueId(),fallback) : fallback;
        return lang.equals("english") ? english : german;
    }

    private void playerLanguages(Player p) {
        new SimpleForm("Sprache / Language",tr(p,"Wähle deine Sprache.","Choose your language."))
                .addButton("Deutsch",who->setPlayerLanguage(who,"german"))
                .addButton("English",who->setPlayerLanguage(who,"english")).send(p);
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
        message(p,tr(p,"Deine Sprache ist Deutsch.","Your language is English."));
    }

    private void language(CommandSender sender, String[] args) {
        if (!sender.isOp()) { error(sender, tr(sender,"Nur für OPs.", "Operators only.")); return; }
        if (args.length != 1 || !(args[0].equalsIgnoreCase("german") || args[0].equalsIgnoreCase("english"))) {
            error(sender, tr(sender,"Sprache: ", "Language: ") + tr(sender,"Deutsch","English") + " | /mlgrush lang german / english");
            return;
        }
        if (!(sender instanceof Player p)) { error(sender,"Use this command in game."); return; }
        saveLanguage(p,args[0].toLowerCase(Locale.ROOT));

    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if(sender instanceof Player player && match(player)!=this) return match(player).onCommand(sender,cmd,label,args);
        if (label.equalsIgnoreCase("lang") || (cmd != null && cmd.getName().equalsIgnoreCase("lang"))) { language(sender,args); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("lang")) { language(sender, Arrays.copyOfRange(args,1,args.length)); return true; }
        if (!(sender instanceof Player p)) { error(sender, tr(sender,"Bitte im Spiel verwenden.","Use this command in game.")); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("mlgrush")) args = Arrays.copyOfRange(args, 1, args.length);
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if(side(p)>=0 && (sub.equals("hub") || sub.equals("lobby"))) {
            forfeit(p);
            enterLobby(p);
            return true;
        }
        if (privateGames.containsKey(p)) {
            if (sub.equals("leave") || sub.equals("lobby") || sub.equals("hub")) { finishPractice(p,true); if (!sub.equals("leave")) enterLobby(p); }
            else error(p,tr(p,"Du bist im Solo-Training. /rush leave","You are in solo practice. /rush leave"));
            return true;
        }
        switch (sub) {
            case "practice", "training", "traning" -> beginPractice(p);
            case "setlobby" -> {
                if (!p.isOp() || side(p) >= 0) { error(p, tr(p,"Nur als OP außerhalb des Matches.","Only an OP outside a match can do this.")); break; }
                plugin.getConfig().set("rush-lobby", locData(p.getLocation()));
                daylight(p.getLevel());
                plugin.saveConfig();
                enterLobby(p);
                message(p, tr(p,"Rush-Lobby gespeichert.","Rush lobby saved."));
            }
            case "lobby" -> { if (side(p) < 0) enterLobby(p); else error(p, tr(p,"Verlasse das Match mit /rush leave.","Leave the match with /rush leave.")); }
            case "edit" -> {
                if (!p.isOp() || active || !matches.isEmpty()) { error(p, tr(p,"Nur als OP bei freier Arena.","Only an OP can edit an idle arena.")); break; }
                prepareMap();
                if (world != null) p.teleport(teamSpawn(0));
            }
            case "setbed", "setspawn" -> setMark(p, sub, args);
            case "arena", "npc" -> {
                if (!p.isOp()) { error(p, tr(p,"Dafuer brauchst du OP.","Only operators can do that.")); break; }
                if (sub.equals("arena")) { chooseMap(p, args); break; }
                for (Entity entity : p.getLevel().getEntities()) {
                    if (lobbyStand(entity) && entity.distance(p) < 2) {
                        error(p, tr(p,"Hier steht bereits ein Rush-NPC.","There is already a Rush NPC here.")); return true;
                    }
                }
                spawnStand(p.getPosition(), false);
                message(p, tr(p,"Rush-NPC gesetzt.","Rush NPC placed."));
            }
            case "classic" -> {
                if (!p.isOp()) { error(p, tr(p,"Dafür brauchst du OP.","Only operators can do that.")); break; }
                if (active || !matches.isEmpty() || !waiting.isEmpty()) { error(p, tr(p,"Die Arena ist gerade belegt.","The arena is busy.")); break; }
                if (classicLayout) { message(p, tr(p,"Die MLG Rush Map ist bereits aktiv.","The MLG Rush Map is already active.")); break; }
                String key = "classic-map";
                if (plugin.getConfig().exists(key)) chooseMap(p, new String[]{"arena", plugin.getConfig().getString(key)});
                else {
                    String name = "MLG_Rush_Map";
                    for (int n = 2; existsMap(name); n++) name = "MLG_Rush_Map_" + n;
                    if (buildInVoid(name)) {
                        plugin.getConfig().set(key, name); plugin.saveConfig();
                        message(p, tr(p,"MLG Rush Map aktiv: ","MLG Rush Map: ") + mapTitle(name));
                    }
                }
            }
            case "join" -> join(p);
            case "duell" -> invite(p, args.length == 2 ? args[1] : "");
            case "annehmen" -> accept(p);
            case "profil" -> profile(p);
            case "", "menü", "menu" -> menu(p);
            case "leave" -> {
                if (side(p) >= 0) forfeit(p);
                else if (!lobbyWorld(p) && !waiting.contains(p) && leaveLobbyKit(p)) message(p, tr(p,"Inventar zurückgegeben.","Inventory restored."));
                else if (waiting.remove(p)) message(p,tr(p,"Warteschlange verlassen.","Left the queue.")); else error(p,tr(p,"Du bist in keinem Duell.","You are not in a duel."));
            }
            case "waffe" -> message(p, tr(p,"Alle spielen mit dem gleichen Stock.","Everyone plays with a stick."));
            default -> error(p, tr(p,"Unbekannter Befehl. /rush öffnet das Menü.","Unknown command. Use /rush for the menu."));
        }
        return true;
    }

    private final Map<Player, Long> nextNpcMenu = new WeakHashMap<>();
    private void npcMenu(Player p) {
        long now = System.nanoTime();
        if (!ready(p) || !p.getFormWindows().isEmpty() || now < nextNpcMenu.getOrDefault(p,0L)) return;
        nextNpcMenu.put(p,now+1_000_000_000L);
        menu(p);
    }

    private void menu(Player p) {
        int side = side(p);
        String description = active ? (training == null ? tr(p,"Duell läuft","Duel in progress") : tr(p,"Training läuft","Practice in progress")) : tr(p,"Arena frei","Arena available");
        SimpleForm form = new SimpleForm(GAME, description + tr(p," · Warteschlange: "," · Queue: ") + waiting.size());
        if (side < 0) {
            if (waiting.contains(p)) action(form, tr(p,"Warteschlange verlassen","Leave queue"), "leave");
            else form.addButton(tr(p,"Klassisches 1v1 suchen","Find a classic 1v1"), this::join);
            form.addButton(tr(p,"Spieler herausfordern","Challenge a player"), this::opponents);
            if (invites.containsKey(p.getUniqueId())) form.addButton(tr(p,"Herausforderung annehmen","Accept challenge"), this::accept);
        }
        if (p.isOp() && side < 0 && !classicLayout) action(form, tr(p,"MLG Rush Map aktivieren","Activate MLG Rush Map"), "classic");
        form.addButton(tr(p,"Mein Profil","My profile"), this::profile);
        if (p.isOp() && side >= 0 && training != null) form.addButton(tr(p,"Solo-Test","Solo practice"), this::trainingMenu);
        if (side >= 0) action(form, training == null ? tr(p,"Duell aufgeben","Forfeit duel") : tr(p,"Training beenden","End practice"), "leave");
        form.send(p);
    }

    private void action(SimpleForm form, String text, String command) {
        if (command.startsWith("test ")) form.addButton(text,p -> test(p,command.substring(5)));
        else form.addButton(text, p -> plugin.getServer().executeCommand(p, "mlgrush " + command));
    }

    private void opponents(Player p) {
        SimpleForm form = new SimpleForm(tr(p,"Herausfordern","Challenge"), tr(p,"Wähle deinen Gegner.","Choose your opponent."));
        for (Player other : plugin.getServer().getOnlinePlayers().values()) {
            if (other != p && match(other).side(other) < 0) form.addButton(other.getName(), who -> invite(who, other.getName()));
        }
        form.addButton(tr(p,"Zurück","Back"), this::menu).send(p);
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

    private void invite(Player p, String name) {
        if (name.isBlank()) { opponents(p); return; }
        invitePlayer(p,plugin.getServer().getPlayerExact(name));
    }

    private void invitePlayer(Player p, Player other) {
        if (other == null || other == p || match(p).side(p) >= 0 || match(other).side(other) >= 0) {
            error(p, tr(p,"Dieser Spieler ist gerade nicht verfügbar.","This player is unavailable.")); return;
        }
        if (!eligible(p)) return;
        invites.put(other.getUniqueId(), new Invite(p.getUniqueId(), System.nanoTime() + 60_000_000_000L));
        message(p, tr(p,"Einladung an ","Invitation to ") + other.getName() + tr(p," gesendet. Sie gilt 60 Sekunden."," sent. It expires in 60 seconds."));
        message(other, p.getName() + tr(other," fordert dich heraus. Schlag mit deinem Schwert zurück oder nutze /rush annehmen."," challenges you. Hit back with your sword or use /rush annehmen."));
    }

    private void accept(Player p) {
        Invite offer = invites.get(p.getUniqueId());
        if (offer == null || System.nanoTime() >= offer.expires()) {
            invites.remove(p.getUniqueId()); error(p, tr(p,"Keine offene Einladung.","No pending invitation.")); return;
        }
        Player other = plugin.getServer().getOnlinePlayers().get(offer.challenger());
        beginDuel(other,p);
    }

    private void beginDuel(Player other, Player p) {
        prepareMap();
        if (other == null || match(p).side(p)>=0 || match(other).side(other)>=0 || world == null) { error(p, tr(p,"Gegner oder Arena gerade nicht verfügbar.","Opponent or arena unavailable.")); return; }
        if (!eligible(p) || !eligible(other)) return;
        allocateDuel(other,p);
    }

    private void addStat(Player p, String stat) {
        String key = stat + "-" + p.getUniqueId();
        plugin.getConfig().set(key, plugin.getConfig().getInt(key) + 1);
        plugin.saveConfig();
    }

    private void result(int winner) {
        addStat(team[winner], "siege");
        addStat(team[1 - winner], "niederlagen");
    }

    private void profile(Player p) {
        UUID id = p.getUniqueId();
        int best = plugin.getConfig().getInt("best-" + id);
        String text = tr(p,"Siege: ","Wins: ") + plugin.getConfig().getInt("siege-" + id) + tr(p,"\nNiederlagen: ","\nLosses: ")
                + plugin.getConfig().getInt("niederlagen-" + id) + tr(p,"\nBetten im 1v1: ","\nBeds in duels: ") + plugin.getConfig().getInt("betten-" + id)
                + tr(p,"\nBridge-Bestzeit: ","\nBest bridge time: ") + (best == 0 ? tr(p,"Noch kein Lauf","No runs yet") : seconds(best));
        new SimpleForm(tr(p,"Mein Profil","My profile"), text).addButton(tr(p,"Zurück","Back"), this::menu).send(p);
    }

    private void trainingMenu(Player p) {
        if (!p.isOp()) return;
        SimpleForm form = new SimpleForm(tr(p,"OP-Training","Operator practice"), tr(p,"Die Arena gehört während des Tests dir. Mit /rush öffnest du dieses Menü im Match.","The arena is yours during practice. Use /rush to reopen this menu."));
        if (side(p) < 0) {
            action(form, tr(p,"Bettduell · zuerst 5 Punkte","Bed duel · first to 5"), "test klassik");
            action(form, tr(p,"Bridge-Zeitlauf · vom Spawn zum Bett","Bridge time trial"), "test zeit");
            action(form, tr(p,"Knockback · 3 Ring-outs am Dummy","Knockback · knock the target off 3 times"), "test kb");
        } else if (training != null) {
            action(form, tr(p,"Teamseite wechseln","Switch sides"), "test seite");
            action(form, tr(p,"Versuch neu starten","Restart attempt"), "test reset");
            if (training == Training.KLASSIK) action(form, tr(p,"Gegnerischen Bettpunkt simulieren","Simulate an opponent scoring"), "test gegentor");
            action(form, tr(p,"Training beenden","End practice"), "leave");
        }
        form.send(p);
    }

    private void test(Player p, String arg) {
        if (!p.isOp()) { error(p, tr(p,"Der Solo-Test ist für OPs.","Solo practice is for operators.")); return; }
        if (side(p) >= 0 && training != null) {
            int i = side(p);
            switch (arg) {
                case "seite" -> { kills[1-i]=kills[i]; deaths[1-i]=deaths[i]; kills[i]=0; deaths[i]=0; team[1 - i] = p; team[i] = null; resetRound(); }
                case "reset" -> resetRound();
                case "gegentor" -> { if (training == Training.KLASSIK) point(1 - i, 5); }
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
        if (active || occupied(world.getFolderName()) || waiting.stream().filter(w -> w != p).count() >= 2) {
            error(p, tr(p,"Die Arena ist gerade für ein Duell belegt.","The arena is in use.")); return;
        }
        if (plugin.getConfig().exists("return-" + p.getUniqueId())) { restore(p); if (plugin.getConfig().exists("return-" + p.getUniqueId())) return; }
        start(p, null, mode);
        message(p, switch (mode) {
            case KLASSIK -> tr(p,"Baue das blaue Bett fünfmal ab.","Break the blue bed five times.");
            case ZEIT -> tr(p,"Baue zum Gegnerbett und knacke deine Bestzeit. Ein Sturz startet den Versuch neu.","Bridge to the opposing bed. Falling restarts the timer.");
            case KB -> tr(p,"Schlage das Trainingsziel dreimal von der Plattform. Benutze den Stock.","Knock the target off three times with the stick.");
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
        if (!ready(p) || side(p)>=0 || privateGames.containsKey(p) || plugin.getConfig().exists("return-"+p.getUniqueId())) return;
        var template=pickTemplate();
        if (template==null) { error(p,tr(p,"Keine Trainingsmap verfügbar.","No practice map available.")); return; }
        String name="mlgruush_practice_"+UUID.randomUUID().toString().replace("-","");
        if (existsMap(name)) return;
        try {
            var game=createCopy(name,template);
            remember(p);
            plugin.saveConfig();
            privateGames.put(p,game);
            waiting.remove(p);
            invites.remove(p.getUniqueId());
            invites.values().removeIf(v->v.challenger().equals(p.getUniqueId()));
            if (!p.isSurvival() && !p.setGamemode(0)) throw new IllegalStateException("Survival");
            respawnPractice(p,game);
            message(p,tr(p,"Solo-Training auf ","Solo practice on ")+mapTitle(template.name())+" §7· /rush leave");
        } catch(RuntimeException ex) {
            finishPractice(p,true);
            cleanPractice();
            plugin.getLogger().warning("Training: "+ex);
            error(p,tr(p,"Training konnte nicht starten.","Could not start practice."));
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
        var board=boards.get(p);
        boolean fresh=board==null;
        if(fresh) { board=new org.powernukkitx.scoreboard.Scoreboard("mlgpractice",PREFIX); boards.put(p,board); }
        board.setLines(List.of(tr(p,"§bTraining","§bPractice"),tr(p,"§7Betten  ","§7Beds  ")+game.points,tr(p,"§7Tode    ","§7Deaths  ")+game.deaths,hearts(p)));
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
        Collections.shuffle(configured);
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
        var template=pickTemplate();
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
            waiting.remove(p);
            error(p,tr(p,"Keine Arena verfügbar.","No arena available."));
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
            if (mode == null) message(p, "Map · " + mapTitle(sourceMap==null?world.getFolderName():sourceMap));
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
                waiting.remove(p);
                invites.remove(p.getUniqueId());
                invites.values().removeIf(v -> v.challenger().equals(p.getUniqueId()));
                if (!p.isSurvival() && !p.setGamemode(0)) throw new IllegalStateException("Survival konnte nicht aktiviert werden");
                KitBackup.empty(p);
            }
            resetRound();
            matchRelease = mode == null ? System.nanoTime() + 5_000_000_000L : 0;
        } catch (RuntimeException ex) {
            end(viewer -> tr(viewer,"Start abgebrochen. Dein Inventar wurde zurückgegeben.","Start cancelled. Your inventory has been restored."));
            throw ex;
        }
    }

    private void forfeit(Player p) {
        if (training != null) { end(viewer -> tr(viewer,"Solo-Test beendet.","Solo practice finished.")); return; }
        int winner = 1 - side(p);
        result(winner);
        String winnerName=team[winner].getName();
        end(viewer -> winnerName + tr(viewer," gewinnt durch Aufgabe."," wins by forfeit."));
    }

    private int elapsed() { return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000); }
    private String seconds(int ms) { return String.format(Locale.GERMANY, "%.2f s", ms / 1000.0); }

    private boolean lobbyStand(Entity entity) {
        return entity instanceof EntityArmorStand && (entity.hasTag("MLGRushLobby") || entity.hasTag("MJRushLobby") || entity.hasTag("SplashRushLobby"));
    }

    private void labelStand(Entity entity, boolean practice) {
        entity.setNameTag(practice ? tr("§eTrainingsziel","§ePractice target") : "§bMLG§fRUUSH§r\n" + tr("Antippen zum Spielen","Tap to play"));
        entity.setNameTagVisible(true);
        entity.setNameTagAlwaysVisible(true);
    }

    private EntityArmorStand spawnStand(Position pos, boolean practice) {
        var chunk = pos.level.getChunk(pos.getFloorX() >> 4, pos.getFloorZ() >> 4, true);
        var stand = new EntityArmorStand(chunk, Entity.getDefaultNBT(pos));
        if (!practice) stand.addTag("MLGRushLobby");
        stand.setImmobile(!practice);
        stand.setCanBeSavedWithChunk(!practice);
        labelStand(stand, practice);
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
        dummy = spawnStand(spawn, true);
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
                if (training == Training.KB) point(team[0] == null ? 1 : 0, 3);
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
        Level target = plugin.getServer().getLevelByName("world");
        if (target == null && plugin.getServer().loadLevel("world")) target = plugin.getServer().getLevelByName("world");
        if (target == null) target = plugin.getServer().getDefaultLevel();
        return target == null ? null : target.getSafeSpawn();
    }

    private boolean lobbyWorld(Player p) {
        if(side(p)>=0 || privateGames.containsKey(p)) return false;
        var saved=plugin.getConfig().getStringList("rush-lobby");
        return p.level!=null && p.level.getFolderName().equals(saved.isEmpty()?"world":saved.getFirst());
    }

    private boolean inLobby(Player p) {
        Position lobby = lobbySpawn();
        return !privateGames.containsKey(p) && side(p) < 0 && lobby != null && p.level == lobby.level && lobby.distanceSquared(p) <= 32 * 32;
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
        if(slot<0 || slot>2) {
            slot=layout(p)[0];
            inventory.setHeldItemIndex(slot);
        }
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
        if(slot>=3 && slot<=8 && p.getInventory()!=null && hasRushKit(p)) {
            event.setCancelled();
            int current=p.getInventory().getHeldItemIndex();
            if(current==2 && slot==3) p.getInventory().setHeldItemIndex(0);
            else if(current==0 && slot==8) p.getInventory().setHeldItemIndex(2);
            syncSelection(p);
        }
    }

    private void enterLobby(Player p) {
        Position lobby = lobbySpawn();
        if (lobby == null) { error(p, tr(p,"Die Rush-Lobby wurde noch nicht gesetzt.","The Rush lobby has not been set.")); return; }
        if (!ready(p) || !p.teleport(lobby)) return;
        String backup = "lobby-kit-" + p.getUniqueId();
        if (!plugin.getConfig().exists(backup)) {
            plugin.getConfig().set(backup, KitBackup.capture(p));
            plugin.getConfig().set("lobby-slot-" + p.getUniqueId(),p.getInventory().getHeldItemIndex());
            plugin.saveConfig();
        }
        KitBackup.empty(p);
        lobbyItems(p);
        p.getInventory().setHeldItemIndex(0);
        showLobbyHud(p);
    }

    private void lobbyItems(Player p) {
        p.getInventory().setItem(0,Item.get("minecraft:iron_sword").setCustomName(tr(p,"Herausfordern","Challenge")));
        p.getInventory().setItem(4, Item.get("minecraft:chest").setCustomName(tr(p,"Inventar verwalten","Manage inventory")));
        p.getInventory().setItem(8, Item.get("minecraft:compass").setCustomName(tr(p,"Training","Practice")));
        p.getInventory().setItem(6,Item.get("minecraft:book").setCustomName(tr(p,"Sprache · Deutsch","Language · English")));
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
        p.sendExperience(0);
        p.sendExperienceLevel(Math.max(0,plugin.getConfig().getInt("lobby-xp-level",2026)));
    }

    private void resetLobbyHud(Player p) {
        if(lobbyHUD.remove(p) && ready(p)) {
            hungerHud(p,false);
            p.sendExperience();
            p.sendExperienceLevel();
        }
    }

    private int[] layout(Player p) {
        int[] defaults={0,1,2};
        List<Integer> slots=plugin.getConfig().getIntegerList("hotbar-"+p.getUniqueId());
        if(slots.size()!=3 || new HashSet<>(slots).size()!=3 || slots.stream().anyMatch(i->i<0 || i>8)) return defaults;
        List<Integer> order=new ArrayList<>(slots);
        Collections.sort(order);
        return new int[]{order.indexOf(slots.get(0)),order.indexOf(slots.get(1)),order.indexOf(slots.get(2))};
    }

    private String kitName(Player p,int i) {
        return switch(i) { case 0 -> tr(p,"Teamwolle","Team wool"); case 1 -> tr(p,"Stock","Stick"); default -> tr(p,"Spitzhacke","Pickaxe"); };
    }

    private boolean canEdit(Player p) {
        return ready(p) && lobbyWorld(p) && inLobby(p) && plugin.getConfig().exists("lobby-kit-"+p.getUniqueId());
    }

    private Item knockbackStick() {
        Item stick=Item.get("minecraft:stick");
        stick.addEnchantment(Enchantment.getEnchantment(Enchantment.ID_KNOCKBACK).setLevel(3,false));
        return stick;
    }

    private void editLayout(Player p,int[] draft) {
        if(!canEdit(p) || p.getTopWindow().isPresent()) return;
        FakeInventory chest=new FakeInventory(FakeInventoryType.CHEST,tr(p,"Hotbar verwalten","Manage hotbar"));
        int[] selected={-1};
        Runnable draw=()->{
            for(int i=0;i<3;i++) {
                Item icon=switch(i) { case 0 -> Item.get("minecraft:red_wool"); case 1 -> knockbackStick(); default -> Item.get("minecraft:diamond_pickaxe"); };
                icon.setCustomName((selected[0]==i?"§b":"§f")+(draft[i]+1)+" · "+kitName(p,i));
                icon.setLore(tr(p,"Zwei Items zum Tauschen antippen","Tap two items to swap"));
                chest.setItem(12+draft[i],icon);
            }
            chest.setItem(22,Item.get("minecraft:emerald").setCustomName(tr(p,"Speichern","Save")));
        };
        chest.setDefaultItemHandler((inv,slot,before,after,event)->{
            event.setCancelled();
            if(!canEdit(p) || event.getPlayer()!=p) return;
            if(slot==22) {
                plugin.getConfig().set("hotbar-"+p.getUniqueId(),List.of(draft[0],draft[1],draft[2]));
                plugin.saveConfig();
                p.removeWindow(chest);
                message(p,tr(p,"Hotbar gespeichert.","Hotbar saved."));
                return;
            }
            for(int i=0;i<3;i++) if(slot==12+draft[i]) {
                if(selected[0]<0) selected[0]=i;
                else {
                    int other=selected[0],previous=draft[other];
                    draft[other]=draft[i];
                    draft[i]=previous;
                    selected[0]=-1;
                }
                draw.run();
                break;
            }
        });
        draw.run();
        p.addWindow(chest);
    }

    @EventHandler
    public void held(PlayerItemHeldEvent e) {
        RushGame gameOwner=match(e.getPlayer());
        if(gameOwner!=this) { gameOwner.held(e); return; }

        if(e.getSlot()>2 && hasRushKit(e.getPlayer())) {
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
        boolean training=item.getId().equals("minecraft:compass") && (item.getCustomName().equals("Training") || item.getCustomName().equals("Practice"));
        boolean editor=item.getId().equals("minecraft:chest") && (item.getCustomName().equals("Inventar verwalten") || item.getCustomName().equals("Manage inventory"));
        boolean language=item.getId().equals("minecraft:book") && item.getCustomName().equals(tr(p,"Sprache · Deutsch","Language · English"));
        if(!training && !editor && !language) return false;
        long now=System.nanoTime();
        if(now>=lobbyClicks.getOrDefault(p,0L) && p.getFormWindows().isEmpty()) {
            lobbyClicks.put(p,now+600_000_000L);
            if(training) beginPractice(p); else if(language) playerLanguages(p); else editLayout(p,layout(p));
        }
        return true;
    }

    private boolean sword(Item item) { return item != null && item.getId().equals("minecraft:iron_sword") && (item.getCustomName().equals("Herausfordern") || item.getCustomName().equals("Challenge")); }

    private final Set<Player> pendingPractice = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Player,Long> lastPlayerHit = new WeakHashMap<>();

    private boolean practiceSword(Player p) {
        return ready(p) && p.isOp() && side(p)<0 && sword(p.getItemInHand()) && inLobby(p);
    }

    private void schedulePractice(Player p) {
        if (!practiceSword(p) || !pendingPractice.add(p)) return;
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

        if (lobbyStand(e.getEntity())) { e.setCancelled(); npcMenu(e.getPlayer()); return; }
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
        if (!p.isOp()) { error(p, tr(p,"Dafür brauchst du OP.","Only operators can do that.")); return; }
        int i = args.length != 2 ? -1 : switch (args[1].toLowerCase(Locale.ROOT)) {
            case "red", "rot" -> 0; case "blue", "blau" -> 1; default -> -1;
        };
        if (i < 0) { error(p, "/rush " + type + tr(p," red oder blue"," red or blue")); return; }
        prepareMap();
        if (active || !matches.isEmpty() || !waiting.isEmpty() || world == null || p.level != world || !region(p)) {
            error(p, tr(p,"Stell dich in die freie aktive Arena. /rush edit bringt dich dorthin.","Stand inside the idle arena. Use /rush edit to get there.")); return;
        }
        plugin.getConfig().set(markKey("layout",0), classicLayout);
        if (type.equals("setspawn")) {
            plugin.getConfig().set(markKey("spawn", i), locData(p.getLocation()));
        } else {
            Block floor = p.getTargetBlock(6);
            if (floor == null || floor.isAir()) { error(p, tr(p,"Schau auf den Bodenblock für das Fußende.","Look at the floor under the foot of the bed.")); return; }
            Vector3 foot = new Vector3(floor.getFloorX(), floor.getFloorY()+1, floor.getFloorZ());
            Vector3 head = foot.add(sign(i), 0, 0);
            Vector3 old = bed(i);
            for (Vector3 v : List.of(foot, head)) {
                Block target = world.getBlock(v);
                boolean movable = (v.equals(old) || v.equals(old.add(sign(i),0,0))) && target instanceof BlockBed;
                if (!region(target) || (!target.isAir() && !movable) || world.getBlock(v.add(0,-1,0)).isAir()) {
                    error(p, tr(p,"Das Bett braucht zwei freie Felder mit Boden darunter.","The bed needs two empty spaces with solid ground underneath.")); return;
                }
            }
            for (Vector3 v : List.of(old, old.add(sign(i),0,0))) if (world.getBlock(v) instanceof BlockBed) world.setBlock(v,new BlockAir());
            RushArena.placeBed(world, foot, i);
            plugin.getConfig().set(markKey("bed",i), List.of(""+foot.getFloorX(),""+foot.getFloorY(),""+foot.getFloorZ()));
            world.save(true);
        }
        plugin.saveConfig();
        message(p, (type.equals("setspawn") ? "Spawn" : tr(p,"Bett","Bed")) + tr(p," für "," for ") + (i == 0 ? tr(p,"Rot","Red") : tr(p,"Blau","Blue")) + tr(p," gespeichert."," saved."));
    }

    private String hearts(Player p) {
        int alive=Math.max(0,Math.min(3,(int)Math.ceil(p.getHealthCurrent()/2)));
        return "  §c"+"♥ ".repeat(alive)+"§8"+"♥ ".repeat(3-alive);
    }

    private void updateBoard(int i) {
        Player p = team[i];
        if (p == null) return;
        var board = boards.get(p);
        List<String> lines = List.of(
                tr(p,"§7Bettpunkte","§7Bed points"),
                tr(p,"  §cRot     §f","  §cRed     §f") + points[0],
                tr(p,"  §9Blau    §f","  §9Blue    §f") + points[1],
                "§r ",
                tr(p,"§7Deine Runde","§7Your round"),
                "  §7Kills    §7" + kills[i],
                tr(p,"  §7Tode    §7","  §7Deaths  §7") + deaths[i], hearts(p));
        if (board == null) {
            board = new org.powernukkitx.scoreboard.Scoreboard("mlg_rush", PREFIX);
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
        error(p, tr(p,"Für ein Spielerduell bitte in Survival wechseln.","Switch to survival to join a duel."));
        return false;
    }

    private void join(Player p) {
        prepareMap();
        if (world == null) { error(p, tr(p,"Die Rush-Map konnte nicht geladen werden.","Could not load the arena.")); return; }
        if (side(p) >= 0 || waiting.contains(p)) { error(p, tr(p,"Du bist bereits angemeldet.","You are already signed up.")); return; }
        if (!eligible(p)) return;
        waiting.add(p);
        message(p, tr(p,"Warteschlange: ","Queue: ") + waiting.size() + tr(p," Spieler. Verlassen mit /rush leave."," players. Leave with /rush leave."));
    }

    private void tick() {
        for(Player p:List.copyOf(lobbyHUD)) if(!lobbyWorld(p)) resetLobbyHud(p);
        for(Player trainee:List.copyOf(privateGames.keySet())) {
            PracticeMap game=privateGames.get(trainee);
            if(!trainee.isOnline() || trainee.level!=game.level) { finishPractice(trainee,false); continue; }
            if(!game.inside(trainee)) { game.deaths++; respawnPractice(trainee,game); }
        }
        for(RushGame game:List.copyOf(matches)) game.tickMatch();
        if(waiting.size()>=2) {
            waiting.removeIf(p -> !p.isOnline() || !eligible(p));
            if(waiting.size()>=2) {
                Iterator<Player> next=waiting.iterator();
                allocateDuel(next.next(),next.next());
            }
        }
        tickMatch();
    }

    private void tickMatch() {
        if (!active) return;
        advanceDummy();
        if (!active) return;
        for (int i = 0; i < 2; i++) {
            Player p = team[i];
            if (p == null) continue;
            if (countdown()) {
                p.sendTip(tr(p,"§eMLGRUUSH startet in ","§eMLGRUUSH starts in ") + ((matchRelease - System.nanoTime()) / 1_000_000_000L + 1) + " s");
                continue;
            }
            if(p.getLevel()!=world) { forfeit(p); return; }
            if (p.getY() < y - 5 || Math.abs(p.getX() - x) > width() + 3 || Math.abs(p.getZ() - z) > depth() + 4) {
                defeat(i, p.getLevel() == world && p.getY() < y - 5);
            }
            if (training != Training.ZEIT && training != Training.KB) continue;
            String status = training == Training.ZEIT ? tr(p,"Zeit ","Time ") + seconds(elapsed()) :
                    tr(p,"Treffer ", "Hits ") + dummyHits + " | Ring-outs " + points[i] + "/3";
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
            e.setFoodLevel(20);
            e.setFoodSaturationLevel(20);
        }
    }

    private void hungerHud(Player p, boolean hide) {
        var packet = new org.cloudburstmc.protocol.bedrock.packet.SetHudPacket();
        packet.getHudElementList().addAll(java.util.EnumSet.of(
                org.cloudburstmc.protocol.bedrock.data.HudElement.HEALTH,
                org.cloudburstmc.protocol.bedrock.data.HudElement.HUNGER,
                org.cloudburstmc.protocol.bedrock.data.HudElement.ARMOR,
                org.cloudburstmc.protocol.bedrock.data.HudElement.AIR_BUBBLES,
                org.cloudburstmc.protocol.bedrock.data.HudElement.HORSE_HEALTH));
        packet.setHudVisible(hide ? org.cloudburstmc.protocol.bedrock.data.HudVisibility.HIDE : org.cloudburstmc.protocol.bedrock.data.HudVisibility.RESET);
        p.sendPacket(packet);
    }

    @EventHandler
    public void naturalHealing(EntityRegainHealthEvent event) {
        if(event.getEntity() instanceof Player owner && match(owner)!=this) { match(owner).naturalHealing(event); return; }

        if (event.getEntity() instanceof Player p && (side(p)>=0 || privateGames.containsKey(p)) && (event.getRegainReason()==EntityRegainHealthEvent.CAUSE_REGEN || event.getRegainReason()==EntityRegainHealthEvent.CAUSE_EATING)) event.setCancelled();
    }

    private void spawn(int i) {
        Player p = team[i];
        p.teleport(teamSpawn(i));
        creditUntil[i] = 0;
        equip(p,i);
    }

    private void equip(Player p,int i) {
        resetLobbyHud(p);
        int[] kit=layout(p);
        p.setHealthMax(6);
        p.setHealthCurrent(6);
        p.getFoodData().setFood(20,20);
        hungerHud(p,true);
        p.setMotion(new Vector3(0, 0, 0));
        KitBackup.empty(p);
        p.getInventory().setItem(kit[1], knockbackStick());
        p.getInventory().setItem(kit[2], Item.get("minecraft:diamond_pickaxe"));
        Item blocks = Item.get(i == 0 ? "minecraft:red_wool" : "minecraft:blue_wool");
        blocks.setCount(64);
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
        roundReady = System.nanoTime() + 500_000_000L;
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

        if(lobbyWorld(e.getPlayer()) && !e.getPlayer().isOp()) { e.setCancelled(); return; }
        var game=privateGames.get(e.getPlayer());
        if(game!=null) {
            Block b=e.getBlock();
            if(b.level!=game.level || !game.inside(b) || !e.getBlockReplace().isAir() || !b.getId().equals("minecraft:red_wool")) {e.setCancelled();return;}
            game.edits.add(new Vector3(b.getFloorX(),b.getFloorY(),b.getFloorZ()));
            Player p=e.getPlayer();
            plugin.getServer().getScheduler().scheduleDelayedTask(plugin,()->{
                if(privateGames.get(p)==game && ready(p)) p.getInventory().setItem(layout(p)[0],Item.get("minecraft:red_wool",0,64));
            },1);
            return;
        }
        Block block = e.getBlock();
        int i = side(e.getPlayer());
        if (i >= 0 && countdown()) { e.setCancelled(); return; }
        if (i < 0 && !region(block)) return;
        if (i < 0 || !buildArea(block) || !block.getId().equals(i == 0 ? "minecraft:red_wool" : "minecraft:blue_wool")
                || !e.getBlockReplace().isAir() || blocksSpawn(block)) { e.setCancelled(); return; }
        Vector3 pos = new Vector3(block.getFloorX(),block.getFloorY(),block.getFloorZ());
        changes.remember(plugin.getDataFolder().toPath(),world,pos);
        built.add(pos);
        Player builder = e.getPlayer();
        long round = started;
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (!active || started != round || side(builder) != i || !builder.isOnline()) return;
            Item supply = Item.get(i == 0 ? "minecraft:red_wool" : "minecraft:blue_wool");
            for (var entry : builder.getInventory().getContents().entrySet()) {
                if (entry.getValue().getId().equals(supply.getId())) {
                    supply.setCount(64);
                    builder.getInventory().setItem(entry.getKey(), supply);
                    return;
                }
            }
            supply.setCount(64);
            builder.getInventory().setItem(layout(builder)[0],supply);
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

        if(lobbyWorld(e.getPlayer()) && !e.getPlayer().isOp()) { e.setCancelled(); return; }
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
            end(viewer -> tr(viewer,"Ziel erreicht in ","Finished in ") + seconds(ms) + tr(viewer,". Bestzeit: ",". Best: ") + seconds(plugin.getConfig().getInt(key)));
            return;
        }
        if (training == null) addStat(team[i], "betten");
        point(i, 5);
    }

    private void point(int i, int goal) {
        points[i]++;
        String name = team[i] == null ? tr("Testgegner","Practice opponent") : team[i].getName();
        for (Player p : team) if (p != null) message(p, tr(p,"Punkt für ","Point for ") + name + "! " + points[0] + " : " + points[1]);
        if (points[i] < goal) { resetRound(); return; }
        if (training == null) result(i);
        end(viewer -> name + tr(viewer," gewinnt!"," wins!"));
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
            if(damage<=0 || game==null && countdown()) return;
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
            else if (lobbyStand(e.getEntity())) npcMenu(from);
            return;
        }
        if(e.getEntity() instanceof Player lobbyPlayer && lobbyWorld(lobbyPlayer)) {
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
            if (attacker instanceof Player p) npcMenu(p);
            return;
        }
        if (!(e.getEntity() instanceof Player target) || side(target) < 0) {
            if (attacker instanceof Player p && (side(p) >= 0 || privateGames.containsKey(p))) e.setCancelled();
            return;
        }
        e.setCancelled();
        if (!(attacker instanceof Player p) || side(p) != 1 - side(target)) return;
        strike(p, target);
    }

    private void strike(Player p, Entity target) {
        String id = p.getItemInHand().getId();
        if (target.getLevel()!=p.getLevel() || target==dummy && !id.equals("minecraft:stick")) return;
        long now = System.nanoTime();
        if (countdown() || now < roundReady || now < strikes.getOrDefault(p, 0L)) return;
        double dx = target.getX() - p.getX(), dz = target.getZ() - p.getZ();
        double distance = Math.hypot(dx, dz);
        if (distance < 0.001) return;
        strikes.put(p, now + 420_000_000L);
        double force = 0.38 + 0.32 * p.getItemInHand().getEnchantmentLevel(Enchantment.ID_KNOCKBACK);
        Vector3 impulse = new Vector3(force * dx / distance, 0.42, force * dz / distance);
        if (target == dummy) {
            vx = impulse.x; vy = impulse.y; vz = impulse.z;
            launched = true;
            dummyHits++;
            p.sendActionBar(tr(p,"Treffer ","Hits ") + dummyHits + " · " + "Stick");
        } else if (target instanceof Player victim) {
            int i = side(victim);
            creditUntil[i] = now + 8_000_000_000L;
            if (victim.getHealthCurrent() <= 2) { defeat(i, true); return; }
            victim.setHealthCurrent(victim.getHealthCurrent() - 2);
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
        return !player.isOp() || hasRushKit(player) || lobbyHUD.contains(player);
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
        waiting.remove(p);
        invites.remove(p.getUniqueId());
        invites.values().removeIf(v -> v.challenger().equals(p.getUniqueId()));
        if (side(p) >= 0) forfeit(p);

    }
}
