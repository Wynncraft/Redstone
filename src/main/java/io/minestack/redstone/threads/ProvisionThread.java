package io.minestack.redstone.threads;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import io.minestack.doublechest.DoubleChest;
import io.minestack.doublechest.databases.rabbitmq.publishers.BungeeCreatePublisher;
import io.minestack.doublechest.databases.rabbitmq.publishers.ServerCreatePublisher;
import io.minestack.doublechest.databases.rabbitmq.worker.WorkerQueue;
import io.minestack.doublechest.databases.rabbitmq.worker.WorkerQueues;
import io.minestack.doublechest.model.bungee.Bungee;
import io.minestack.doublechest.model.network.Network;
import io.minestack.doublechest.model.pluginhandler.bungeetype.NetworkBungeeType;
import io.minestack.doublechest.model.pluginhandler.bungeetype.NetworkBungeeTypeAddress;
import io.minestack.doublechest.model.pluginhandler.servertype.NetworkServerType;
import io.minestack.doublechest.model.server.Server;
import io.minestack.redstone.Redstone;
import lombok.extern.log4j.Log4j2;
import org.bson.types.ObjectId;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Log4j2
public class ProvisionThread extends Thread {

    private final Redstone redstone;
    private WorkerQueue serverWorkerQueue;
    private WorkerQueue bungeeWorkerQueue;

    public ProvisionThread(Redstone redstone) {
        this.redstone = redstone;

        try {
            serverWorkerQueue = new WorkerQueue(DoubleChest.INSTANCE.getRabbitMQDatabase(), WorkerQueues.SERVER_BUILD.name()) {

                ArrayList<ObjectId> creatingServers = new ArrayList<>();

                @Override
                public void messageDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties basicProperties, byte[] bytes) throws IOException {
                    JSONObject jsonObject = new JSONObject(new String(bytes));

                    ObjectId objectId = new ObjectId(jsonObject.getString("server"));

                    if (creatingServers.contains(objectId)) {
                        log.warn("Already creating the server with the objectId of " + objectId.toString());
                        getChannel().basicNack(envelope.getDeliveryTag(), false, false);
                        return;
                    }

                    creatingServers.add(objectId);
                    Server server = DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().getModel(objectId);
                    boolean success = false;

                    if (server != null) {
                        success = redstone.getServerManager().createServer(server);
                    }

                    if (success == true) {
                        getChannel().basicAck(envelope.getDeliveryTag(), false);
                    } else {
                        getChannel().basicNack(envelope.getDeliveryTag(), false, false);
                    }
                    creatingServers.remove(objectId);
                }
            };
        } catch (IOException e) {
            log.error("Threw a Exception in ProvisionThread, full stack trace follows: ", e);
        }

        try {
            bungeeWorkerQueue = new WorkerQueue(DoubleChest.INSTANCE.getRabbitMQDatabase(), WorkerQueues.BUNGEE_BUILD.name()) {

                ArrayList<ObjectId> creatingBungees = new ArrayList<>();

                @Override
                public void messageDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties basicProperties, byte[] bytes) throws IOException {
                    JSONObject jsonObject = new JSONObject(new String(bytes));

                    ObjectId objectId = new ObjectId(jsonObject.getString("bungee"));

                    if (creatingBungees.contains(objectId)) {
                        log.warn("Already creating the bungee with the objectId of " + objectId.toString());
                        getChannel().basicNack(envelope.getDeliveryTag(), false, false);
                        return;
                    }

                    Bungee bungee = DoubleChest.INSTANCE.getMongoDatabase().getBungeeRepository().getModel(objectId);

                    boolean success = false;

                    if (bungee != null) {
                        success = redstone.getBungeeManager().createBungee(bungee);
                    }

                    if (success == true) {
                        getChannel().basicAck(envelope.getDeliveryTag(), false);
                    } else {
                        getChannel().basicNack(envelope.getDeliveryTag(), false, false);
                    }
                    creatingBungees.remove(objectId);
                }
            };
        } catch (IOException e) {
            log.error("Threw a Exception in ProvisionThread, full stack trace follows: ", e);
        }
    }

    public void run() {
        while (true) {

            for (Network network : DoubleChest.INSTANCE.getMongoDatabase().getNetworkRepository().getModels()) {

                List<Bungee> bungees = DoubleChest.INSTANCE.getMongoDatabase().getBungeeRepository().getNetworkBungees(network);
                Iterator<Bungee> bungeeIterator = bungees.iterator();
                while (bungeeIterator.hasNext()) {
                    Bungee bungee = bungeeIterator.next();
                    if (bungee.getUpdated_at().getTime() < System.currentTimeMillis() - 30000) {
                        //bungee hasn't updated in 30 seconds. probably dead
                        try {
                            if (bungee.getNode() != null) {
                                try {
                                    redstone.getBungeeManager().removeContainer(bungee);
                                } catch (Exception e) {
                                    log.error("Threw a Exception in ProvisionThread::run, full stack trace follows: ", e);
                                }
                            }
                            DoubleChest.INSTANCE.getMongoDatabase().getBungeeRepository().removeModel(bungee);
                            bungeeIterator.remove();
                        } catch (Exception e) {
                            log.error("Threw a Exception in ProvisionThread::run, full stack trace follows: ", e);
                        }
                    }
                }

                for (NetworkBungeeType networkBungeeType : network.getBungeeTypes().values()) {
                    for (NetworkBungeeTypeAddress address : networkBungeeType.getAddresses().values()) {
                        Bungee runningBungee = DoubleChest.INSTANCE.getMongoDatabase().getBungeeRepository().getNetworkNodeAddressBungee(network, address.getNode(), address.getPublicAddress());

                        if (runningBungee == null) {
                            try {
                                new BungeeCreatePublisher().createBungee(networkBungeeType.getBungeeType(), network, address.getPublicAddress());
                            } catch (IOException e) {
                                log.error("Threw a Exception in ProvisionThread::run, full stack trace follows: ", e);
                            }
                        }
                    }
                }

                List<Server> servers = DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().getNetworkServers(network, false);
                Iterator<Server> serverIterator = servers.iterator();
                while (serverIterator.hasNext()) {
                    Server server = serverIterator.next();
                    if (server.getUpdated_at().getTime() < System.currentTimeMillis() - 30000) {
                        //server hasn't updated in 30 seconds. probably dead
                        try {
                            if (server.getNode() != null) {
                                try {
                                    redstone.getServerManager().removeContainer(server);
                                } catch (Exception e) {
                                    log.error("Threw a Exception in ProvisionThread::run, full stack trace follows: ", e);
                                }
                            }
                            DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().removeModel(server);
                            serverIterator.remove();
                        } catch (Exception e) {
                            log.error("Threw a Exception in ProvisionThread::run, full stack trace follows: ", e);
                        }
                    }
                }

                for (NetworkServerType networkServerType : network.getServerTypes().values()) {
                    if (networkServerType.isManualStart() == true) {
                        continue;
                    }
                    servers = DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().getNetworkServerTypeServers(network, networkServerType.getServerType(), false);

                    if (servers.size() < networkServerType.getAmount()) {
                        int diff = networkServerType.getAmount() - servers.size();
                        for (int i = 0; i < diff; i++) {
                            try {
                                new ServerCreatePublisher().createServer(networkServerType.getServerType(), network);
                            } catch (IOException e) {
                                log.error("Threw a Exception in ProvisionThread::run, full stack trace follows: ", e);
                            }
                        }
                    }
                }
            }

            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                log.info("Stopping Provision Thread");
                serverWorkerQueue.stopWorking();
                bungeeWorkerQueue.stopWorking();
                break;
            }
        }
    }

}
