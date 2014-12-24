package io.minestack.redstone.threads;

import io.minestack.doublechest.DoubleChest;
import io.minestack.doublechest.model.bungee.Bungee;
import io.minestack.doublechest.model.network.Network;
import io.minestack.doublechest.model.node.NetworkNode;
import io.minestack.doublechest.model.pluginhandler.servertype.NetworkServerType;
import io.minestack.doublechest.model.server.Server;
import io.minestack.redstone.Redstone;
import lombok.extern.log4j.Log4j2;

import java.util.Iterator;
import java.util.List;

@Log4j2
public class ProvisionThread extends Thread {

    private final Redstone redstone;

    public ProvisionThread(Redstone redstone) {
        this.redstone = redstone;
    }

    public void run() {
        while (true) {

            for (Network network : DoubleChest.INSTANCE.getMongoDatabase().getNetworkRepository().getModels()) {
                for (NetworkNode networkNode : network.getNodes().values()) {
                    if (networkNode.getBungeeType() == null) {
                        continue;
                    }

                    Bungee runningBungee = DoubleChest.INSTANCE.getMongoDatabase().getBungeeRepository().getNetworkNodeBungee(network, networkNode.getNode());
                    if (runningBungee != null) {
                        if (runningBungee.getUpdated_at().getTime() < System.currentTimeMillis()+30000) {
                            //bungee hasn't updated is 30 seconds. probably dead
                            try {
                                redstone.getBungeeManager().removeContainer(runningBungee);
                                DoubleChest.INSTANCE.getMongoDatabase().getBungeeRepository().removeModel(runningBungee);
                                redstone.getBungeeManager().createBungee(network, networkNode.getBungeeType(), networkNode.getNode(), networkNode.getNodePublicAddress());
                            } catch (Exception e) {
                                log.error("Threw a Exception in ProvisionThread::run, full stack trace follows: ", e);
                            }
                        }
                    } else {
                        redstone.getBungeeManager().createBungee(network, networkNode.getBungeeType(), networkNode.getNode(), networkNode.getNodePublicAddress());
                    }
                }

                List<Server> servers = DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().getNetworkServers(network);
                Iterator<Server> serverIterator = servers.iterator();
                while (serverIterator.hasNext()) {
                    Server server = serverIterator.next();
                    if (server.getUpdated_at().getTime() < System.currentTimeMillis()+30000) {
                        //server hasn't updated in 30 seconds. probably dead
                        try {
                            redstone.getServerManager().removeContainer(server);
                            DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().removeModel(server);
                            serverIterator.remove();
                        } catch (Exception e) {
                            log.error("Threw a Exception in ProvisionThread::run, full stack trace follows: ", e);
                        }
                    }
                }

                for (NetworkServerType networkServerType : network.getServerTypes().values()) {
                    servers = DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().getNetworkServerTypeServers(network, networkServerType.getServerType());

                    if (servers.size() < networkServerType.getAmount()) {
                        int diff = networkServerType.getAmount() - servers.size();
                        for (int i = 0; i < diff; i++) {
                            redstone.getServerManager().createServer(network, networkServerType.getServerType());
                        }
                    }
                }
            }

            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                log.error("Threw a ProvisionThread in ServerManager::run, full stack trace follows: ", e);
            }
        }
    }

}
