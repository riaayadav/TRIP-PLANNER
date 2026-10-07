package com.tripplanner;

import java.io.File;
import java.util.UUID;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

// DevServer runs an embedded Tomcat server for local testing and development
public final class DevServer {

    public static Tomcat start(int port, String database) throws Exception {
        String testUser = System.getProperty("TEST_DB_USER", "sa");
        String testPassword = System.getProperty("TEST_DB_PASSWORD", "");
        return start(port, database, testUser, testPassword);
    }

    public static Tomcat start(int port, String database, String user, String password)
        throws Exception {
        System.setProperty("DB_URL", database);
        System.setProperty("DB_USER", user);
        System.setProperty("DB_PASSWORD", password);

        Tomcat server = new Tomcat();
        String tempDir = "target/tomcat-" + port + "-" + UUID.randomUUID();
        server.setBaseDir(new File(tempDir).getAbsolutePath());
        server.setPort(port);
        server.getConnector().setProperty("address", "127.0.0.1");

        File webappDir = new File("src/main/webapp");
        Context context = server.addWebapp("", webappDir.getAbsolutePath());
        context.setParentClassLoader(DevServer.class.getClassLoader());

        server.start();
        return server;
    }

    public static void main(String[] args) throws Exception {
        File localDataDir = new File(".local-data");
        localDataDir.mkdirs();

        System.setProperty("DB_INITIALIZE", Database.env("DB_INITIALIZE", "true"));
        System.setProperty("APP_HTTPS", "false");

        boolean isMySql = "mysql".equals(System.getenv("LOCAL_DATABASE"));
        String url;
        String user;
        String password;

        if (isMySql) {
            url = Database.env("DB_URL", "");
            user = Database.env("DB_USER", "");
            password = Database.env("DB_PASSWORD", "");
            if (!url.startsWith("jdbc:mysql://")) {
                throw new IllegalArgumentException("MySQL mode requires DB_URL starting with jdbc:mysql://");
            }
        } else {
            url = "jdbc:h2:file:./.local-data/dev-db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
            user = "sa";
            password = "";
        }

        Tomcat server = start(8080, url, user, password);
        String dbInfo = isMySql ? "configured MySQL database" : "local H2 development data";
        System.out.println("Trip Planner: http://127.0.0.1:8080 (" + dbInfo + ")");
        server.getServer().await();
    }
}
