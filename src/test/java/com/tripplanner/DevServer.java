package com.tripplanner;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.Context;
import java.io.File;

/** Local-only preview. H2 is test-scoped and never packaged in the production WAR. */
public final class DevServer {
    public static Tomcat start(int port,String database)throws Exception{
        return start(port,database,System.getProperty("TEST_DB_USER","sa"),System.getProperty("TEST_DB_PASSWORD",""));
    }
    public static Tomcat start(int port,String database,String user,String password)throws Exception{
        System.setProperty("DB_URL",database);System.setProperty("DB_USER",user);System.setProperty("DB_PASSWORD",password);
        Tomcat server=new Tomcat();server.setBaseDir(new File("target/tomcat-"+port+"-"+java.util.UUID.randomUUID()).getAbsolutePath());server.setPort(port);server.getConnector().setProperty("address","127.0.0.1");
        Context context=server.addWebapp("",new File("src/main/webapp").getAbsolutePath());context.setParentClassLoader(DevServer.class.getClassLoader());
        server.start();return server;
    }
    public static void main(String[] args)throws Exception{
        new File(".local-data").mkdirs();
        System.setProperty("DB_INITIALIZE",Database.env("DB_INITIALIZE","true"));
        System.setProperty("APP_HTTPS","false");
        boolean mysql="mysql".equals(System.getenv("LOCAL_DATABASE"));
        String url=mysql?Database.env("DB_URL",""):"jdbc:h2:file:./.local-data/dev-db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        if(mysql&&!url.startsWith("jdbc:mysql://"))throw new IllegalArgumentException("MySQL mode requires DB_URL starting with jdbc:mysql://");
        Tomcat server=start(8080,url,mysql?Database.env("DB_USER",""):"sa",mysql?Database.env("DB_PASSWORD",""):"");
        System.out.println("Trip Planner: http://127.0.0.1:8080 ("+(mysql?"configured MySQL database":"local H2 development data")+")");
        server.getServer().await();
    }
}
