package io.minestack.redstone.managers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DockerClientBuilder;
import io.minestack.doublechest.DoubleChest;
import io.minestack.doublechest.model.network.Network;
import io.minestack.doublechest.model.node.Node;
import io.minestack.doublechest.model.pluginhandler.servertype.ServerType;
import io.minestack.doublechest.model.server.Server;
import lombok.extern.log4j.Log4j2;
import org.bson.types.ObjectId;

import java.util.Arrays;
import java.util.Date;

@Log4j2
public class ServerManager {

    public boolean createServer(Network network, ServerType serverType) {
        log.info("Creating Server " + serverType.getName() + " for network " + network.getName());
        Node node = network.findNodeForServer(serverType);

        if (node == null) {
            log.error("Could not find a node to place " + serverType.getName() + " for network " + network.getName() + " on. Is the network over provisioned?");
            return false;
        }

        log.info("Placing Server " + serverType.getName() + " on node " + node.getName() + " for network " + network.getName());
        int number = DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().getNextNumber(network, serverType);

        ObjectId objectId = new ObjectId();
        Server server = new Server(objectId, new Date(System.currentTimeMillis()));
        server.setNetwork(network);
        server.setNode(node);
        server.setNumber(number);
        server.setServerType(serverType);
        server.setPort(-1);
        server.setUpdated_at(new Date(System.currentTimeMillis() + 300000));//add 5 minutes for server to start up

        DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().insertModel(server);

        log.info("Removing any old Docker Containers for " + serverType.getName() + ". " + server.getNumber() + " for network " + network.getName());
        try {
            removeContainer(server);
        } catch (Exception e) {
            log.error("Threw a Exception in ServerManager::createServer, full stack trace follows: ", e);
            return false;
        }

        log.info("Setting up Docker Container for " + serverType.getName() + "." + server.getNumber() + " for network " + network.getName());

        DockerClient dockerClient = DockerClientBuilder.getInstance("http://" + server.getNode().getPrivateAddress() + ":4243").build();
        CreateContainerResponse response;

        try {
            CreateContainerCmd cmd = dockerClient.createContainerCmd("minestack/bukkit")
                    .withEnv("mongo_addresses=" + System.getenv("mongo_addresses"))
                    .withEnv("rabbit_addresses="+System.getenv("rabbit_addresses"))
                    .withEnv("rabbit_username="+System.getenv("rabbit_username"))
                    .withEnv("rabbit_password="+System.getenv("rabbit_password"));

            if (System.getenv("mongo_username") != null) {
                cmd.withEnv("mongo_username=" + System.getenv("mongo_username"))
                        .withEnv("mongo_password=" + System.getenv("mongo_password"));
            }

            response = cmd.withEnv("server_id=" + server.getId())
                    .withName(serverType.getName() + "." + server.getNumber())
                    .withStdinOpen(true)
                    .exec();
        } catch (Exception e) {
            log.error("Threw a Exception in ServerManager::createServer, full stack trace follows: ", e);
            return false;
        }

        String containerId = response.getId();
        server = DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().getModel(objectId);
        server.setContainerId(containerId);

        DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().saveModel(server);

        log.info("Starting Docker Container for " + serverType.getName() + "." + server.getNumber() + " for network " + network.getName());
        try {
            dockerClient.startContainerCmd(containerId).withPublishAllPorts(true).withBinds(new Bind("/mnt/minestack", new Volume("/mnt/minestack"))).exec();
        } catch (Exception e) {
            log.error("Threw a Exception in ServerManager::createServer, full stack trace follows: ", e);
            return false;
        }

        return true;
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
