package io.minestack.redstone;

import io.minestack.doublechest.DoubleChest;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class Redstone {

    public static void main(String[] args) {
        new Redstone();
    }

    public Redstone() {
        log.info("Started Redstone - Minestack Controller");

        log.info("Init MySQL Database");
        DoubleChest.INSTANCE.initMySQLDatabase(System.getenv("mysql_username"), System.getenv("mysql_password"), System.getenv("mysql_database"), System.getenv("mysql_host"), 3306);

    }

}
