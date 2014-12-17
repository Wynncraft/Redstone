package io.minestack.redstone;

import io.minestack.doublechest.DoubleChest;
import io.minestack.doublechest.model.network.Network;
import io.minestack.doublechest.model.node.NetworkNode;
import io.minestack.doublechest.model.node.Node;
import io.minestack.doublechest.model.node.NodePublicAddress;
import io.minestack.doublechest.model.plugin.Plugin;
import io.minestack.doublechest.model.plugin.PluginConfig;
import io.minestack.doublechest.model.plugin.PluginHolderPlugin;
import io.minestack.doublechest.model.plugin.PluginVersion;
import io.minestack.doublechest.model.pluginhandler.bungeetype.BungeeType;
import io.minestack.doublechest.model.pluginhandler.servertype.NetworkServerType;
import io.minestack.doublechest.model.pluginhandler.servertype.ServerType;
import io.minestack.doublechest.model.world.ServerTypeWorld;
import io.minestack.doublechest.model.world.World;
import io.minestack.doublechest.model.world.WorldVersion;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class MySQLImport extends Thread {

    public void run() {
        while (true) {
            long start = System.currentTimeMillis();
            log.info("Re-Importing data from MySQL");

            //Insert data from MySQL
            try {
                for (Plugin plugin : DoubleChest.INSTANCE.getMySQLDatabase().getPluginRepository().getModels()) {
                    log.info("Importing Plugin " + plugin.getName());
                    for (PluginConfig pluginConfig : plugin.getConfigs()) {
                        log.info("Importing Plugin Config " + pluginConfig.getName());
                        DoubleChest.INSTANCE.getRedisDatabase().getPluginConfigRepository().saveModel(pluginConfig);
                    }
                    for (PluginVersion pluginVersion : plugin.getVersions()) {
                        log.info("Importing Plugin Version " + pluginVersion.getVersion());
                        DoubleChest.INSTANCE.getRedisDatabase().getPluginVersionRepository().saveModel(pluginVersion);
                    }
                    DoubleChest.INSTANCE.getRedisDatabase().getPluginRepository().saveModel(plugin);
                }

                for (World world : DoubleChest.INSTANCE.getMySQLDatabase().getWorldRepository().getModels()) {
                    log.info("Importing World " + world.getName());
                    for (WorldVersion worldVersion : world.getVersions()) {
                        log.info("Importing World Version " + worldVersion.getVersion());
                        DoubleChest.INSTANCE.getRedisDatabase().getWorldVersionRepository().saveModel(worldVersion);
                    }
                    DoubleChest.INSTANCE.getRedisDatabase().getWorldRepository().saveModel(world);
                }

                for (Node node : DoubleChest.INSTANCE.getMySQLDatabase().getNodeRepository().getModels()) {
                    log.info("Importing Node " + node.getName());

                    for (NodePublicAddress nodePublicAddress : node.getPublicAddresses()) {
                        log.info("Importing Node Public Address " + nodePublicAddress.getPublicAddress());
                        DoubleChest.INSTANCE.getRedisDatabase().getNodePublicAddressRepository().saveModel(nodePublicAddress);
                    }
                    DoubleChest.INSTANCE.getRedisDatabase().getNodeRepository().saveModel(node);
                }

                for (ServerType serverType : DoubleChest.INSTANCE.getMySQLDatabase().getServerTypeRepository().getModels()) {
                    log.info("Importing Server Type "+serverType.getName());
                    for (ServerTypeWorld serverTypeWorld : serverType.getWorlds()) {
                        DoubleChest.INSTANCE.getRedisDatabase().getServerTypeWorldRepository().saveModel(serverTypeWorld);
                    }

                    for (PluginHolderPlugin pluginHolderPlugin : serverType.getPlugins()) {
                        DoubleChest.INSTANCE.getRedisDatabase().getPluginHolderPluginRepository().saveModel(pluginHolderPlugin);
                    }

                    DoubleChest.INSTANCE.getRedisDatabase().getServerTypeRepository().saveModel(serverType);
                }

                for (BungeeType bungeeType : DoubleChest.INSTANCE.getMySQLDatabase().getBungeeTypeRepository().getModels()) {
                    log.info("Importing Bungee Type "+bungeeType.getName());
                    for (PluginHolderPlugin pluginHolderPlugin : bungeeType.getPlugins()) {
                        DoubleChest.INSTANCE.getRedisDatabase().getPluginHolderPluginRepository().saveModel(pluginHolderPlugin);
                    }

                    DoubleChest.INSTANCE.getRedisDatabase().getBungeeTypeRepository().saveModel(bungeeType);
                }

                for (Network network : DoubleChest.INSTANCE.getMySQLDatabase().getNetworkRepository().getModels()) {
                    log.info("Importing Network: "+network.getName());
                    for (NetworkNode networkNode : network.getNodes()) {
                        DoubleChest.INSTANCE.getRedisDatabase().getNetworkNodeRepository().saveModel(networkNode);
                    }

                    for (NetworkServerType networkServerType : network.getServerTypes()) {
                        DoubleChest.INSTANCE.getRedisDatabase().getNetworkServerTypeRepository().saveModel(networkServerType);
                    }
                    DoubleChest.INSTANCE.getRedisDatabase().getNetworkRepository().saveModel(network);
                }
            } catch (Exception e) {
                log.error("Threw a Exception in MySQLImport::run, full stack trace follows: ", e);
            }

            //Sleep before doing another MySQLImport
            long end = System.currentTimeMillis();
            log.info("Done MySQL Re-Import ms: " + (end - start));
            try {
                Thread.sleep(300000);//sleep for 5 mins
            } catch (InterruptedException e) {
                log.error("Threw a Exception in MySQLImport::run, full stack trace follows: ", e);
            }
        }
    }

}
