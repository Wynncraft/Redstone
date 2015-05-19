package io.minestack.redstone;

import com.mongodb.ServerAddress;
import com.rabbitmq.client.Address;
import io.minestack.doublechest.DoubleChest;
import io.minestack.redstone.managers.BungeeManager;
import io.minestack.redstone.managers.ServerManager;
import io.minestack.redstone.threads.ProvisionThread;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.kencochrane.raven.Raven;
import net.kencochrane.raven.RavenFactory;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Log4j2
public class Redstone {

    public static void main(String[] args) {
        new Redstone(args[0]);
    }

    @Getter
    private final ServerManager serverManager;

    @Getter
    private final BungeeManager bungeeManager;

    @Getter
    private final Raven raven;

    public Redstone(String dsn) {
        log.info("Started Redstone - Minestack Controller");

        log.info("Init Mongo Database");
        List<ServerAddress> addresses = new ArrayList<>();
        String mongoAddresses = System.getenv("mongo_addresses");
        for (String mongoAddress : mongoAddresses.split(",")) {
            String[] split = mongoAddress.split(":");
            int port = 27017;
            if (split.length == 2) {
                port = Integer.parseInt(split[1]);
            }
            try {
                addresses.add(new ServerAddress(split[0], port));
            } catch (UnknownHostException e) {
                log.error("Threw a UnknownHostException in Redstone::main, full stack trace follows: ", e);
            }
        }
        if (System.getenv("mongo_username") == null) {
            DoubleChest.INSTANCE.initMongoDatabase(addresses, System.getenv("mongo_database"));
        } else {
            DoubleChest.INSTANCE.initMongoDatabase(addresses, System.getenv("mongo_username"), System.getenv("mongo_password"), System.getenv("mongo_database"));
        }

        log.info("Init RabbitMQ");
        List<Address> addressList = new ArrayList<>();
        String rabbitAddresses = System.getenv("rabbit_addresses");
        for (String rabbitAddress : rabbitAddresses.split(",")) {
            String[] split = rabbitAddress.split(":");
            int port = 5672;
            if (split.length == 2) {
                port = Integer.parseInt(split[1]);
            }
            addressList.add(new Address(split[0], port));
        }
        DoubleChest.INSTANCE.initRabbitMQDatabase(addressList, System.getenv("rabbit_username"), System.getenv("rabbit_password"));

        raven = RavenFactory.ravenInstance(dsn);

        serverManager = new ServerManager(this);
        bungeeManager = new BungeeManager(this);

        ProvisionThread provisionThread = new ProvisionThread(this);
        provisionThread.start();
    }

}
