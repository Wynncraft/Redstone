package io.minestack.redstone.managers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DockerClientBuilder;
import io.minestack.doublechest.DoubleChest;
import io.minestack.doublechest.model.node.NetworkNode;
import io.minestack.doublechest.model.node.Node;
import io.minestack.doublechest.model.server.Server;
import io.minestack.redstone.Redstone;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.kencochrane.raven.event.Event;
import net.kencochrane.raven.event.EventBuilder;
import net.kencochrane.raven.event.interfaces.ExceptionInterface;

import java.util.*;
import java.util.function.Predicate;

@Log4j2
@AllArgsConstructor
public class ServerManager {

    private Redstone redstone;

    public boolean createServer(Server server) {
        return createServer(server, (node) -> false, 0);
    }

    public boolean createServer(Server server, Predicate<Node> filter, int times) {
        if (server.getNode() != null && times == 0) {
            log.error("Tried to create a already running server.");
            return false;
        }
        if (server.getNetwork() == null) {
            log.error("Tried to create a server with a null network.");
            return false;
        }
        if (server.getServerType() == null) {
            log.error("Tried to create a server with a null server type.");
            return false;
        }
        if (server.getNetwork().getServerTypes().containsKey(server.getServerType().getId()) == false) {
            log.error("Tried to create " + server.getServerType().getName() + " on network " + server.getNetwork().getName() + " when it has not been added.");
            return false;
        }
        if (DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().getNetworkServerTypeServers(server.getNetwork(), server.getServerType(), true).size() > server.getNetwork().getServerTypes().get(server.getServerType().getId()).getAmount()) {
            log.error("Tried to create more servers "+server.getServerType().getName()+" then provisioned on network " + server.getNetwork().getName());
            return false;
        }

        int number = DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().getNextNumber(server.getNetwork(), server.getServerType());
        server.setNumber(number);

        log.info("Creating Server " + server.getServerType().getName() + " for network " + server.getNetwork().getName());

        Node node = server.getNode();

        if (node == null) {
            Iterator<NetworkNode> iterator = server.getNetwork().getNodes()
                    .values()
                    .iterator();

            while (iterator.hasNext()) {
                NetworkNode next = iterator.next();

                if (next.getNode().canFitServer(server.getServerType())) {
                    if (filter.test(next.getNode()))
                        continue;

                    if (node == null) {
                        node = next.getNode();
                    } else if (next.getNode().getFreeRam() > node.getFreeRam()) {
                        node = next.getNode();
                    }
                }
            }
        }

        if (node == null) {
            log.error("Could not find a node to place " + server.getServerType().getName() + " for network " + server.getNetwork().getName() + " on. Is the network over provisioned?");
            return false;
        }
        server.setNode(node);

        log.info("Placing Server " + server.getServerType().getName() + " on node " + node.getName() + " for network " + server.getNetwork().getName());
        log.info("Removing any old Docker Containers for " + server.getServerType().getName() + "." + server.getNumber() + " for network " + server.getNetwork().getName());
        try {
            removeContainer(server);
        } catch (Exception e) {
            log.error("Threw a Exception in ServerManager::createServer, full stack trace follows: ", e);
            return false;
        }

        log.info("Setting up Docker Container for " + server.getServerType().getName() + "." + server.getNumber() + " for network " + server.getNetwork().getName());

        DockerClient dockerClient = DockerClientBuilder.getInstance("http://" + server.getNode().getPrivateAddress() + ":4243").build();
        CreateContainerResponse response;

        try {
            List<String> env = new ArrayList<>();
            env.add("mongo_addresses=" + System.getenv("mongo_addresses"));
            env.add("mongo_database=" + System.getenv("mongo_database"));
            if (System.getenv("mongo_username") != null) {
                env.add("mongo_username=" + System.getenv("mongo_username"));
                env.add("mongo_password=" + System.getenv("mongo_password"));
            }
            env.add("rabbit_addresses=" + System.getenv("rabbit_addresses"));
            env.add("rabbit_username=" + System.getenv("rabbit_username"));
            env.add("rabbit_password=" + System.getenv("rabbit_password"));
            env.add("server_id=" + server.getId());

            response = dockerClient.createContainerCmd("minestack/bukkit")
                    .withEnv(env.toArray(new String[env.size()]))
                    .withName(server.getServerType().getName() + "." + server.getNumber())
                    .withStdinOpen(true)
                    .withHostName(server.getServerType().getName() + "." + server.getNumber())
                    .exec();
        } catch (Exception e) {
            log.error("Could not create server on node " + node.getName() + ", attempting to start on another node");
            final Node no = node;
            server.setNode(null);

            createServer(server, (n) -> n.getName().equals(no.getName()), 0);
            redstone.getRaven().sendEvent(createEvent(e, node));
            return false;
        }

        String containerId = response.getId();

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        log.info("Starting Docker Container for " + server.getServerType().getName() + "." + server.getNumber() + " for network " + server.getNetwork().getName());
        try {
            dockerClient.startContainerCmd(containerId).withPublishAllPorts(true).withBinds(new Bind("/mnt/minestack", new Volume("/mnt/minestack"))).exec();
        } catch (Exception e) {
            if (times < 3) {
                return createServer(server, (n) -> false, ++times);
            }

            log.error("Could not create server on node " + node.getName() + ", attempting to start on another node");
            final Node no = node;
            server.setNode(null);

            createServer(server, (n) -> n.getName().equals(no.getName()), 0);
            redstone.getRaven().sendEvent(createEvent(e, node));
            return false;
        }

        server.setContainerId(containerId);
        server.setUpdated_at(new Date(System.currentTimeMillis() + 300000));//add 5 minutes for server to start up
        DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().saveModel(server);
        return true;
    }

    private Event createEvent(Exception e, Node node) {
        return new EventBuilder()
                .withSentryInterface(new ExceptionInterface(e))
                .withLevel(Event.Level.ERROR)
                .withExtra("node", node.getName())
                .build();
    }

    public void removeContainer(Server server) throws Exception {
        DockerClient dockerClient = DockerClientBuilder.getInstance("http://" + server.getNode().getPrivateAddress() + ":4243").build();

        for (Container container : dockerClient.listContainersCmd().withShowAll(true).exec()) {
            String name = container.getNames()[0];
            if (name.equals("/" + server.getServerType().getName() + "." + server.getNumber())) {
                log.info("Deleting " + Arrays.toString(container.getNames()));
                try {
                    dockerClient.killContainerCmd(container.getId()).exec();
                } catch (Exception ignored) {
                }
                dockerClient.removeContainerCmd(container.getId()).withForce(true).exec();
                break;
            }
        }
    }

}
