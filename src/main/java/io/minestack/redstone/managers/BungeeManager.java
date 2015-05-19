package io.minestack.redstone.managers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import io.minestack.doublechest.DoubleChest;
import io.minestack.doublechest.model.bungee.Bungee;
import io.minestack.redstone.Redstone;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

@Log4j2
@AllArgsConstructor
public class BungeeManager {

    private Redstone redstone;

    public boolean createBungee(Bungee bungee) {
        if (bungee.getNode() == null) {
            log.error("Tried to create a bungee with a null node.");
            return false;
        }
        if (bungee.getPublicAddress() == null) {
            log.error("Tried to create a bungee with a null public address.");
            return false;
        }
        if (bungee.getNetwork() == null) {
            log.error("Tried to create a bungee with a null network.");
            return false;
        }
        if (bungee.getBungeeType() == null) {
            log.error("Tried to create a bungee with a null bungee type.");
            return false;
        }
        if (bungee.getNode().canFitBungee(bungee.getBungeeType()) == false) {
            log.error("Cannot fit bungee type "+bungee.getBungeeType().getName()+" for network "+bungee.getNetwork().getName()+" on node "+bungee.getNode().getName());
            return false;
        }
        log.info("Creating Bungee " + bungee.getBungeeType().getName() + " for network " + bungee.getNetwork().getName() + " on node "+bungee.getNode().getName());

        log.info("Removing any old Docker Containers for " + bungee.getBungeeType().getName() + "."+bungee.getPublicAddress().getPublicAddress()+" for network " + bungee.getNetwork().getName() + " on node "+bungee.getNode().getName());
        try {
            removeContainer(bungee);
        } catch (Exception e) {
            log.error("Threw a Exception in BungeeManager::createServer, full stack trace follows: ", e);
            return false;
        }

        log.info("Setting up Docker Container for " + bungee.getBungeeType().getName() + "." + bungee.getPublicAddress().getPublicAddress() + " for network " + bungee.getNetwork().getName() + " on node "+bungee.getNode().getName());

        DockerClient dockerClient = DockerClientBuilder.getInstance("http://" + bungee.getNode().getPrivateAddress() + ":4243").build();
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
            env.add("rabbit_username="+System.getenv("rabbit_username"));
            env.add("rabbit_password="+System.getenv("rabbit_password"));
            env.add("bungee_id=" + bungee.getId());

            CreateContainerCmd cmd = dockerClient.createContainerCmd("minestack/bungee")
                    .withEnv(env.toArray(new String[env.size()]))
                    .withName(bungee.getBungeeType().getName() + "." + bungee.getPublicAddress().getPublicAddress())
                    .withExposedPorts(new ExposedPort(25565, InternetProtocol.TCP))
                    .withStdinOpen(true);

            cmd.getHostConfig().setPortBindings(new Ports(new ExposedPort(25565, InternetProtocol.TCP), new Ports.Binding(bungee.getPublicAddress().getPublicAddress(), 25565)));
            cmd.withHostName(bungee.getBungeeType().getName()+"."+bungee.getPublicAddress().getPublicAddress());

            response = cmd.exec();
        } catch (Exception e) {
            log.error("Threw a Exception in BungeeManager::createBungee, full stack trace follows: ", e);
            redstone.getRaven().sendException(e);
            return false;
        }

        String containerId = response.getId();
        bungee.setContainerId(containerId);
        bungee.setUpdated_at(new Date(System.currentTimeMillis() + 300000));//add 5 minutes for bungee to start up
        DoubleChest.INSTANCE.getMongoDatabase().getBungeeRepository().saveModel(bungee);

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        log.info("Starting Docker Container for " + bungee.getBungeeType().getName() + "." + bungee.getPublicAddress().getPublicAddress()+ " for network " + bungee.getNetwork().getName()+ " on node "+bungee.getNode().getName());
        try {
            dockerClient.startContainerCmd(containerId)
                    .withPublishAllPorts(true)
                    .withPortBindings(new PortBinding(new Ports.Binding(bungee.getPublicAddress().getPublicAddress(), 25565), new ExposedPort(25565, InternetProtocol.TCP)))
                    .withBinds(new Bind("/mnt/minestack", new Volume("/mnt/minestack"))).exec();
        } catch (Exception e) {
            log.error("Threw a Exception in BungeeManager::createBungee, full stack trace follows: ", e);
            redstone.getRaven().sendException(e);
            return false;
        }
        return true;
    }

    public void removeContainer(Bungee bungee) {
        DockerClient dockerClient = DockerClientBuilder.getInstance("http://" + bungee.getNode().getPrivateAddress() + ":4243").build();

        for (Container container : dockerClient.listContainersCmd().withShowAll(true).exec()) {
            String name = container.getNames()[0];
            if (name.equals("/" + bungee.getBungeeType().getName()+"."+bungee.getPublicAddress().getPublicAddress()) || name.contains(bungee.getPublicAddress().getPublicAddress())) {
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
