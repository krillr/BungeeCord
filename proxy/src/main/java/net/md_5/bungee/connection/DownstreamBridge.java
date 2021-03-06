package net.md_5.bungee.connection;

import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import java.io.DataInput;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.api.event.ServerDisconnectEvent;
import net.md_5.bungee.api.event.TabCompleteResponseEvent;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.Util;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.score.Objective;
import net.md_5.bungee.api.score.Position;
import net.md_5.bungee.api.score.Score;
import net.md_5.bungee.api.score.Scoreboard;
import net.md_5.bungee.api.score.Team;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.netty.PacketHandler;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.protocol.packet.KeepAlive;
import net.md_5.bungee.protocol.packet.PlayerListItem;
import net.md_5.bungee.protocol.packet.ScoreboardObjective;
import net.md_5.bungee.protocol.packet.ScoreboardScore;
import net.md_5.bungee.protocol.packet.ScoreboardDisplay;
import net.md_5.bungee.protocol.packet.PluginMessage;
import net.md_5.bungee.protocol.packet.Kick;
import net.md_5.bungee.protocol.packet.SetCompression;
import net.md_5.bungee.protocol.packet.TabCompleteResponse;
import net.md_5.bungee.tab.TabList;

@RequiredArgsConstructor
public class DownstreamBridge extends PacketHandler
{

    private final ProxyServer bungee;
    private final UserConnection con;
    private final ServerConnection server;
    private BungeePluginChannelHandler pluginChannelHandler;

    public ProxyServer getBungee() {
        return bungee;
    }

    public UserConnection getCon() {
        return con;
    }

    public ServerConnection getServer() {
        return server;
    }

    @Override
    public void exception(Throwable t) throws Exception
    {
        if ( server.isObsolete() )
        {
            // do not perform any actions if the user has already moved
            return;
        }

        ServerInfo def = bungee.getServerInfo( con.getPendingConnection().getListener().getFallbackServer() );
        if ( server.getInfo() != def )
        {
            server.setObsolete( true );
            con.connectNow( def );
            con.sendMessage( bungee.getTranslation( "server_went_down" ) );
        } else
        {
            con.disconnect( Util.exception( t ) );
        }
    }

    @Override
    public void disconnected(ChannelWrapper channel) throws Exception
    {
        // We lost connection to the server
        server.getInfo().removePlayer( con );
        if ( bungee.getReconnectHandler() != null )
        {
            bungee.getReconnectHandler().setServer( con );
        }

        if ( !server.isObsolete() )
        {
            con.disconnect( bungee.getTranslation( "lost_connection" ) );
        }

        ServerDisconnectEvent serverDisconnectEvent = new ServerDisconnectEvent( con, server.getInfo() );
        bungee.getPluginManager().callEvent( serverDisconnectEvent );
    }

    @Override
    public void handle(PacketWrapper packet) throws Exception
    {
        if ( !server.isObsolete() )
        {
            con.getEntityRewrite().rewriteClientbound( packet.buf, con.getServerEntityId(), con.getClientEntityId() );
            con.sendPacket( packet );
        }
    }

    @Override
    public void handle(KeepAlive alive) throws Exception
    {
        con.setSentPingId( alive.getRandomId() );
        con.setSentPingTime(System.currentTimeMillis());
    }

    @Override
    public void handle(PlayerListItem playerList) throws Exception
    {
        con.getTabListHandler().onUpdate(TabList.rewrite(playerList));
        throw CancelSendSignal.INSTANCE; // Always throw because of profile rewriting
    }

    @Override
    public void handle(ScoreboardObjective objective) throws Exception
    {
        Scoreboard serverScoreboard = con.getServerSentScoreboard();
        switch ( objective.getAction() )
        {
            case 0:
                serverScoreboard.addObjective( new Objective( objective.getName(), objective.getValue(), objective.getType() ) );
                break;
            case 1:
                serverScoreboard.removeObjective(objective.getName());
                break;
            case 2:
                Objective oldObjective = serverScoreboard.getObjective( objective.getName() );
                if ( oldObjective != null )
                {
                    oldObjective.setValue( objective.getValue() );
                }
                break;
            default:
                throw new IllegalArgumentException( "Unknown objective action: " + objective.getAction() );
        }
    }

    @Override
    public void handle(ScoreboardScore score) throws Exception
    {
        Scoreboard serverScoreboard = con.getServerSentScoreboard();
        switch ( score.getAction() )
        {
            case 0:
                Score s = new Score( score.getItemName(), score.getScoreName(), score.getValue() );
                serverScoreboard.removeScore( score.getItemName() );
                serverScoreboard.addScore(s);
                break;
            case 1:
                serverScoreboard.removeScore( score.getItemName() );
                break;
            default:
                throw new IllegalArgumentException( "Unknown scoreboard action: " + score.getAction() );
        }
    }

    @Override
    public void handle(ScoreboardDisplay displayScoreboard) throws Exception
    {
        Scoreboard serverScoreboard = con.getServerSentScoreboard();
        serverScoreboard.setName( displayScoreboard.getName() );
        serverScoreboard.setPosition( Position.values()[displayScoreboard.getPosition()] );
    }

    @Override
    public void handle(net.md_5.bungee.protocol.packet.Team team) throws Exception
    {
        Scoreboard serverScoreboard = con.getServerSentScoreboard();
        // Remove team and move on
        if ( team.getMode() == 1 )
        {
            serverScoreboard.removeTeam( team.getName() );
            return;
        }

        // Create or get old team
        Team t;
        if ( team.getMode() == 0 )
        {
            t = new Team( team.getName() );
            serverScoreboard.addTeam( t );
        } else
        {
            t = serverScoreboard.getTeam( team.getName() );
        }

        if ( t != null )
        {
            if ( team.getMode() == 0 || team.getMode() == 2 )
            {
                t.setDisplayName( team.getDisplayName() );
                t.setPrefix( team.getPrefix() );
                t.setSuffix( team.getSuffix() );
                t.setFriendlyFire( team.getFriendlyFire() );
                t.setNameTagVisibility( team.getNameTagVisibility() );
                t.setColor( team.getColor() );
            }
            if ( team.getPlayers() != null )
            {
                for ( String s : team.getPlayers() )
                {
                    if ( team.getMode() == 0 || team.getMode() == 3 )
                    {
                        t.addPlayer( s );
                    } else
                    {
                        t.removePlayer( s );
                    }
                }
            }
        }
    }

    @Override
    public void handle(PluginMessage pluginMessage) throws Exception {
        if (pluginChannelHandler == null) {
            pluginChannelHandler = new BungeePluginChannelHandler(this);
        }
        DataInput in = pluginMessage.getStream();
        PluginMessageEvent event = new PluginMessageEvent(con.getServer(), con, pluginMessage.getTag(), pluginMessage.getData().clone());

        if (bungee.getPluginManager().callEvent(event).isCancelled()) {
            throw CancelSendSignal.INSTANCE;
        }

        switch (pluginMessage.getTag()) {
            case "MC|Brand":
                handleMCBrand(pluginMessage, event);
                break;
            case "BungeeCord":
                pluginChannelHandler.handle(in, event);
                break;
        }
    }

    private void handleMCBrand(PluginMessage pluginMessage, PluginMessageEvent event) throws Exception {
        if ( con.getPendingConnection().getVersion() >= ProtocolConstants.MINECRAFT_1_8 )
        {
            try
            {
                ByteBuf brand = Unpooled.wrappedBuffer( pluginMessage.getData() );
                String serverBrand = DefinedPacket.readString( brand );
                brand.release();
                brand = ByteBufAllocator.DEFAULT.heapBuffer();
                DefinedPacket.writeString( bungee.getName() + " (" + bungee.getVersion() + ")" + " <- " + serverBrand, brand );
                pluginMessage.setData( brand.array().clone() );
                brand.release();
            } catch ( Exception ignored )
            {
                // TODO: Remove this
                // Older spigot protocol builds sent the brand incorrectly
                return;
            }
        } else
        {
            String serverBrand = new String( pluginMessage.getData(), Charsets.UTF_8 );
            pluginMessage.setData((bungee.getName() + " (" + bungee.getVersion() + ")" + " <- " + serverBrand).getBytes(Charsets.UTF_8));
        }
        // changes in the packet are ignored so we need to send it manually
        con.unsafe().sendPacket( pluginMessage );
        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public void handle(Kick kick) throws Exception
    {
        ServerInfo def = bungee.getServerInfo( con.getPendingConnection().getListener().getFallbackServer() );
        if ( Objects.equal( server.getInfo(), def ) )
        {
            def = null;
        }
        ServerKickEvent event = bungee.getPluginManager().callEvent( new ServerKickEvent( con, server.getInfo(), ComponentSerializer.parse( kick.getMessage() ), def, ServerKickEvent.State.CONNECTED ) );
        if ( event.isCancelled() && event.getCancelServer() != null )
        {
            con.connectNow( event.getCancelServer() );
        } else
        {
            con.disconnect0( event.getKickReasonComponent() ); // TODO: Prefix our own stuff.
        }
        server.setObsolete( true );
        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public void handle(SetCompression setCompression) throws Exception
    {
        con.setCompressionThreshold( setCompression.getThreshold() );
        server.getCh().setCompressionThreshold( setCompression.getThreshold() );
    }

    @Override
    public void handle(TabCompleteResponse tabCompleteResponse) throws Exception
    {
        TabCompleteResponseEvent tabCompleteResponseEvent = new TabCompleteResponseEvent( con.getServer(), con, tabCompleteResponse.getCommands() );

        if ( !bungee.getPluginManager().callEvent( tabCompleteResponseEvent ).isCancelled() )
        {
            con.unsafe().sendPacket( tabCompleteResponse );
        }

        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public String toString()
    {
        return "[" + con.getName() + "] <-> DownstreamBridge <-> [" + server.getInfo().getName() + "]";
    }
}
