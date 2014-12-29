package io.minestack.redstone.managers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import io.minestack.doublechest.DoubleChest;
import io.minestack.doublechest.model.bungee.Bungee;
import io.minestack.doublechest.model.network.Network;
import io.minestack.doublechest.model.node.Node;
import io.minestack.doublechest.model.node.NodePublicAddress;
import io.minestack.doublechest.model.pluginhandler.bungeetype.BungeeType;
import lombok.extern.log4j.Log4j2;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

@Log4j2
public class BungeeManager {

    public boolean createBungee(Network network, BungeeType bungeeType, Node node, NodePublicAddress nodePublicAddress) {
        log.info("Creating Bungee " + bungeeType.getName() + " for network " + network.getName() + " on node "+node.getName());

        Bungee runningBungee = DoubleChest.INSTANCE.getMongoDatabase().getBungeeRepository().getNetworkNodeBungee(network, node);

        if (runningBungee != null) {
            log.error("Tried to create multiple bungees on node "+node.getName()+" for network "+network.getName());
            return false;
        }

        if (node.canFitBungee(bungeeType) == false) {
            log.error("Cannot fit bungee type "+bungeeType.getName()+" for network "+network.getName()+" on node "+node.getName());
            return false;
        }

        Bungee bungee = new Bungee(new ObjectId(), new Date(System.currentTimeMillis()));
        bungee.setNetwork(network);
        bungee.setNode(node);
        bungee.setBungeeType(bungeeType);
        bungee.setPublicAddress(nodePublicAddress);
        bungee.setUpdated_at(new Date(System.currentTimeMillis() + 300000));//add 5 minutes for server to start up

        DoubleChest.INSTANCE.getMongoDatabase().getBungeeRepository().insertModel(bungee);

        log.info("Removing any old Docker Containers for " + bungeeType.getName() + "."+nodePublicAddress.getPublicAddress()+" for network " + network.getName() + " on node "+node.getName());
        try {
            removeContainer(bungee);
        } catch (Exception e) {
            log.error("Threw a Exception in BungeeManager::createServer, full stack trace follows: ", e);
            return false;
        }

        log.info("Setting up Docker Container for " + bungeeType.getName() + "." + nodePublicAddress.getPublicAddress() + " for network " + network.getName() + " on node "+node.getName());

        DockerClient dockerClient = DockerClientBuilder.getInstance("http://" + bungee.getNode().getPrivateAddress() + ":4243").build();
        CreateContainerResponse response;

        try {
            List<String> env = new ArrayList<>();
            env.add("mongo_addresses=" + System.getenv("mongo_addresses"));
            env.add("mongo_database=" + System.getenv("mongo_database"));
            env.add("rabbit_addresses=" + System.getenv("rabbit_addresses"));
            env.add("rabbit_username="+System.getenv("rabbit_username"));
            env.add("rabbit_password="+System.getenv("rabbit_password"));
            env.add("bungee_id=" + bungee.getId());

            if (System.getenv("mongo_username") != null) {
                env.add("mongo_username=" + System.getenv("mongo_username"));
                env.add("mongo_password=" + System.getenv("mongo_password"));
            }

            CreateContainerCmd cmd = dockerClient.createContainerCmd("minestack/bungee")
                    .withEnv(env.toArray(new String[env.size()]))
                    .withName(bungeeType.getName() + "." + nodePublicAddress.getPublicAddress())
                    .withExposedPorts(new ExposedPort(25565, InternetProtocol.TCP))
                    .withStdinOpen(true);

            cmd.getHostConfig().setPortBindings(new Ports(new ExposedPort(25565, InternetProtocol.TCP), new Ports.Binding(bungee.getPublicAddress().getPublicAddress(), 25565)));

            response = cmd.exec();
        } catch (Exception e) {
            log.error("Threw a Exception in BungeeManager::createBungee, full stack trace follows: ", e);
            return false;
        }

        String containerId = response.getId();
        bungee.setContainerId(containerId);
        DoubleChest.INSTANCE.getMongoDatabase().getBungeeRepository().saveModel(bungee);

        log.info("Starting Docker Container for " + bungeeType.getName() + "." + nodePublicAddress.getPublicAddress()+ " for network " + network.getName()+ " on node "+node.getName());
        try {
            dockerClient.startContainerCmd(containerId)
                    .withPublishAllPorts(true)
                    .withPortBindings(new PortBinding(new Ports.Binding(bungee.getPublicAddress().getPublicAddress(), 25565), new ExposedPort(25565, InternetProtocol.TCP)))
                    .withBinds(new Bind("/mnt/minestack", new Volume("/mnt/minestack"))).exec();
        } catch (Exception e) {
            log.error("Threw a Exception in BungeeManager::createBungee, full stack trace follows: ", e);
            return false;
        }
        return true;
    }

    public void removeContainer(Bungee bungee) {
        DockerClient dockerClient = DockerClientBuilder.getInstance("http://" + bungee.getNode().getPrivateAddress() + ":4243").build();

        for (Container container : dockerClient.listContainersCmd().withShowAll(true).exec()) {
            String name = container.getNames()[0];
            if (name.equals("/" + bungee.getBungeeType().getName()+"."+bungee.getPublicAddress().getPublicAddress())) {
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
