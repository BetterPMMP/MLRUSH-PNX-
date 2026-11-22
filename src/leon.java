package mlg.rush;

import org.powernukkitx.command.Command;
import org.powernukkitx.command.CommandSender;
import org.powernukkitx.plugin.PluginBase;

public final class leon extends PluginBase {
    private final RushGame game=new RushGame(this,null);

    public void onEnable() { game.onEnable(); }
    public void onDisable() { game.onDisable(); }
    public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        return game.onCommand(sender,command,label,args);
    }
}
