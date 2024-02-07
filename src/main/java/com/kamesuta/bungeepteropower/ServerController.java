package com.kamesuta.bungeepteropower;

import com.kamesuta.bungeepteropower.api.PowerSignal;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.kamesuta.bungeepteropower.BungeePteroPower.plugin;

/**
 * Provides a function to send power signals to the server and join the server when it is started
 */
public class ServerController {

    /**
     * Send a power signal to the server and join the server when it is started
     *
     * @param sender     The command sender
     * @param serverName The name of the server to send the signal
     * @param serverId   The server ID to send the signal to
     * @param signalType The power signal to send
     */
    public static void sendPowerSignal(CommandSender sender, String serverName, String serverId, PowerSignal signalType) {
        // Get signal
        String signal = signalType.getSignal();

        // Send signal
        plugin.config.getPowerController().sendPowerSignal(serverName, serverId, signalType).thenRun(() -> {
            // Start auto stop task and send warning
            if (signalType == PowerSignal.START) {
                // If auto join is configured, join the server when it is started
                if (sender instanceof ProxiedPlayer && plugin.config.startupJoinTimeout > 0) {
                    // If auto join is configured, join the server when it is started
                    sender.sendMessage(plugin.messages.success("server_startup_join", serverName));

                    // Get the server info
                    ServerInfo serverInfo = plugin.getProxy().getServerInfo(serverName);
                    // ServerInfo is null if the server is not found on bungeecord config
                    if (serverInfo != null) {
                        // Wait until the server is started
                        onceStarted(serverInfo).thenRun(() -> {
                            // Move player to the started server
                            ProxiedPlayer player = (ProxiedPlayer) sender;
                            player.connect(serverInfo);
                        }).exceptionally((Throwable e) -> {
                            sender.sendMessage(plugin.messages.warning("server_startup_join_failed", serverName));
                            return null;
                        });
                    }

                } else {
                    // Otherwise, just send a message
                    sender.sendMessage(plugin.messages.success("server_start", serverName));
                }

                // Stop the server if nobody joins after a while
                stopAfterWhile(sender, serverName, serverId, signalType);
            } else {
                // When stopping the server
                sender.sendMessage(plugin.messages.success("server_stop", serverName));
            }

        }).exceptionally(e -> {
            sender.sendMessage(plugin.messages.error("server_" + signal + "_failed", serverName));
            return null;

        });
    }

    /**
     * Stop the server after a while
     *
     * @param sender     The command sender
     * @param serverName The name of the server to stop
     * @param serverId   The server ID to stop
     * @param signalType Is this executed while stopping or starting?
     */
    public static void stopAfterWhile(CommandSender sender, String serverName, String serverId, PowerSignal signalType) {
        // Get signal
        String signal = signalType.getSignal();

        // Get the auto stop time
        Integer serverTimeout = plugin.config.getServerTimeout(serverName);
        if (serverTimeout != null && serverTimeout >= 0) {
            // Stop the server after a while
            plugin.delay.stopAfterWhile(serverName, serverTimeout, () -> {
                // Stop the server
                sendPowerSignal(sender, serverName, serverId, PowerSignal.STOP);

                // Record statistics
                plugin.statistics.actionCounter.increment(Statistics.ActionCounter.ActionType.STOP_SERVER_NOBODY);
                plugin.statistics.startReasonRecorder.recordStop(serverName);
            });

            // Send message
            sender.sendMessage(plugin.messages.warning("server_" + signal + "_warning", serverName, serverTimeout));
        }
    }

    /**
     * Wait until the server is started
     *
     * @param serverInfo The server to wait for
     * @return A future that completes when the server is started
     */
    private static CompletableFuture<Void> onceStarted(ServerInfo serverInfo) {
        CompletableFuture<Void> future = new CompletableFuture<Void>();

        // The timestamp when the server is expected to be started within
        Instant timeout = Instant.now().plusSeconds(plugin.config.startupJoinTimeout);

        // Ping the server and check if it is started
        Callback<ServerPing> callback = new Callback<>() {
            @Override
            public void done(ServerPing serverPing, Throwable throwable) {
                // If the server is started, complete the future
                if (throwable == null && serverPing != null) {
                    future.complete(null);
                    return;
                }
                // Not started yet, retry after a while
                if (Instant.now().isBefore(timeout)) {
                    ProxyServer.getInstance().getScheduler()
                            .schedule(plugin, () -> serverInfo.ping(this), plugin.config.pingInterval, TimeUnit.SECONDS);
                    return;
                }

                // If the server is not started within the timeout, complete the future exceptionally
                future.completeExceptionally(new RuntimeException("Server did not start in autoJoinTimeout"));
            }
        };
        serverInfo.ping(callback);

        return future;

    }

}
