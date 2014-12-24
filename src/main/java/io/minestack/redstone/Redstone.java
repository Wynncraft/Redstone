package io.minestack.redstone;

import com.mongodb.ServerAddress;
import io.minestack.doublechest.DoubleChest;
import io.minestack.redstone.threads.ServerManager;
import lombok.extern.log4j.Log4j2;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

@Log4j2
public class Redstone {

    public static void main(String[] args) {
        new Redstone();
    }

    public Redstone() {
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

        ServerManager serverManager = new ServerManager();
        serverManager.start();
    }

}
