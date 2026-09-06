package com.tripplanner;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.Context;
import java.io.File;

/** Local-only preview. H2 is test-scoped and never packaged in the production WAR. */
public final class DevServer {
    public static Tomcat start(int port,String database)throws Exception{
        System.setProperty("DB_URL",database);System.setProperty("DB_USER",System.getProperty("TEST_DB_USER","sa"));System.setProperty("DB_PASSWORD",System.getProperty("TEST_DB_PASSWORD",""));
        Tomcat server=new Tomcat();server.setBaseDir(new File("target/tomcat-"+port+"-"+java.util.UUID.randomUUID()).getAbsolutePath());server.setPort(port);server.getConnector().setProperty("address","127.0.0.1");
        Context context=server.addWebapp("",new File("src/main/webapp").getAbsolutePath());context.setParentClassLoader(DevServer.class.getClassLoader());
        server.start();return server;
    }
    public static void main(String[] args)throws Exception{
        new File(".local-data").mkdirs();
        System.setProperty("DB_INITIALIZE","true");
        System.setProperty("APP_HTTPS","false");
        Tomcat server=start(8080,"jdbc:h2:file:./.local-data/dev-db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        System.out.println("Trip Planner preview: http://127.0.0.1:8080 (local H2 development data)");
        server.getServer().await();
    }
}
