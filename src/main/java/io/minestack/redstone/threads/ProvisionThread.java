package io.minestack.redstone.threads;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import io.minestack.doublechest.DoubleChest;
import io.minestack.doublechest.databases.rabbitmq.Publisher;
import io.minestack.doublechest.databases.rabbitmq.WorkerQueue;
import io.minestack.doublechest.model.bungee.Bungee;
import io.minestack.doublechest.model.network.Network;
import io.minestack.doublechest.model.node.NetworkNode;
import io.minestack.doublechest.model.node.Node;
import io.minestack.doublechest.model.node.NodePublicAddress;
import io.minestack.doublechest.model.pluginhandler.bungeetype.BungeeType;
import io.minestack.doublechest.model.pluginhandler.servertype.NetworkServerType;
import io.minestack.doublechest.model.pluginhandler.servertype.ServerType;
import io.minestack.doublechest.model.server.Server;
import io.minestack.redstone.Redstone;
import lombok.extern.log4j.Log4j2;
import org.bson.types.ObjectId;
import org.json.JSONObject;

import java.io.IOException;
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
            serverWorkerQueue = new WorkerQueue(DoubleChest.INSTANCE.getRabbitMQDatabase(), "serverBuild") {
                @Override
                public void messageDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties basicProperties, byte[] bytes) throws IOException {
                    JSONObject jsonObject = new JSONObject(new String(bytes));

                    Network network = DoubleChest.INSTANCE.getMongoDatabase().getNetworkRepository().getModel(new ObjectId(jsonObject.getString("network")));
                    ServerType serverType = DoubleChest.INSTANCE.getMongoDatabase().getServerTypeRepository().getModel(new ObjectId(jsonObject.getString("serverType")));

                    boolean success = false;

                    if (network != null && serverType != null) {
                        success = redstone.getServerManager().createServer(network, serverType);
                    }

                    if (success == true) {
                        getChannel().basicAck(envelope.getDeliveryTag(), false);
                    } else {
                        getChannel().basicNack(envelope.getDeliveryTag(), false, false);
                    }
                }
            };
        } catch (IOException e) {
            log.error("Threw a Exception in ProvisionThread, full stack trace follows: ", e);
        }

        try {
            bungeeWorkerQueue = new WorkerQueue(DoubleChest.INSTANCE.getRabbitMQDatabase(), "bungeeBuild") {
                @Override
                public void messageDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties basicProperties, byte[] bytes) throws IOException {
                    JSONObject jsonObject = new JSONObject(new String(bytes));
                    Network network = DoubleChest.INSTANCE.getMongoDatabase().getNetworkRepository().getModel(new ObjectId(jsonObject.getString("network")));
                    BungeeType bungeeType = DoubleChest.INSTANCE.getMongoDatabase().getBungeeTypeRepository().getModel(new ObjectId(jsonObject.getString("bungeeType")));
                    Node node = DoubleChest.INSTANCE.getMongoDatabase().getNodeRepository().getModel(new ObjectId(jsonObject.getString("node")));

                    boolean success = false;

                    if (network != null && bungeeType != null && node != null) {
                        NodePublicAddress publicAddress = node.getPublicAddresses().get(new ObjectId(jsonObject.getString("publicAddress")));
                        if (publicAddress != null) {
                            success = redstone.getBungeeManager().createBungee(network, bungeeType, node, publicAddress);
                        }
                    }

                    if (success == true) {
                        getChannel().basicAck(envelope.getDeliveryTag(), false);
                    } else {
                        getChannel().basicNack(envelope.getDeliveryTag(), false, false);
                    }
                }
            };
        } catch (IOException e) {
            log.error("Threw a Exception in ProvisionThread, full stack trace follows: ", e);
        }
    }

    public void run() {
        while (true) {

            for (Network network : DoubleChest.INSTANCE.getMongoDatabase().getNetworkRepository().getModels()) {
                for (NetworkNode networkNode : network.getNodes().values()) {
                    if (networkNode.getBungeeType() == null) {
                        continue;
                    }

                    Bungee runningBungee = DoubleChest.INSTANCE.getMongoDatabase().getBungeeRepository().getNetworkNodeBungee(network, networkNode.getNode());
                    try {
                        Publisher bungeePublisher = new Publisher(DoubleChest.INSTANCE.getRabbitMQDatabase(), "bungeeBuild");
                        boolean publish = false;
                        if (runningBungee != null) {
                            if (runningBungee.getUpdated_at().getTime() < System.currentTimeMillis() + 30000) {
                                //bungee hasn't updated is 30 seconds. probably dead
                                try {
                                    redstone.getBungeeManager().removeContainer(runningBungee);
                                    DoubleChest.INSTANCE.getMongoDatabase().getBungeeRepository().removeModel(runningBungee);
                                    //redstone.getBungeeManager().createBungee(network, networkNode.getBungeeType(), networkNode.getNode(), networkNode.getNodePublicAddress());
                                    publish = true;
                                } catch (Exception e) {
                                    log.error("Threw a Exception in ProvisionThread::run, full stack trace follows: ", e);
                                }
                            }
                        } else {
                            //redstone.getBungeeManager().createBungee(network, networkNode.getBungeeType(), networkNode.getNode(), networkNode.getNodePublicAddress());
                            publish = true;
                        }

                        if (publish == true) {
                            JSONObject message = new JSONObject();

                            message.put("network", network.getId().toString());
                            message.put("bungeeType", networkNode.getBungeeType().getId().toString());
                            message.put("node", networkNode.getNode().getId().toString());
                            message.put("publicAddress", networkNode.getNodePublicAddress());

                            bungeePublisher.publish(message);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
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
                        try {
                            Publisher serverPublisher = new Publisher(DoubleChest.INSTANCE.getRabbitMQDatabase(), "serverBuild");
                            for (int i = 0; i < diff; i++) {
                                //redstone.getServerManager().createServer(network, networkServerType.getServerType());
                                JSONObject message = new JSONObject();

                                message.put("network", network.getId().toString());
                                message.put("serverType", networkServerType.getServerType().getId().toString());

                                serverPublisher.publish(message);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
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
