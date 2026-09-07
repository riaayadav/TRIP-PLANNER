package com.tripplanner;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.apache.catalina.startup.Tomcat;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.sql.*;

class WorkflowTest {
    @Test void emptyCatalogListsAreRejectedAndLegacyTripsStillGenerate()throws Exception{
        assertThrows(IllegalArgumentException.class,()->TripService.listText(Map.of("attractions",", , ,"),"attractions",1000));
        assertEquals("Park, Museum",TripService.listText(Map.of("attractions",", Park, ,Museum,"),"attractions",1000));
        Client client=new Client();client.register("legacy-attractions@test.invalid");long destination;
        try(Connection c=DriverManager.getConnection(System.getProperty("DB_URL"),System.getProperty("DB_USER"),System.getProperty("DB_PASSWORD"))){
            destination=Database.insert(c,"INSERT INTO destinations(name,region,description,season,category,daily_cost,duration,activities,attractions,image) VALUES (?,?,?,?,?,?,?,?,?,?)","Legacy place","Region","Description","Summer","Nature",100,1,"Walking",",,,","mountains.jpg");
        }
        try {
            var body=trip();body.put("destination_id",destination);long id=client.post("trip",body,200).path("id").asLong();
            assertEquals("Legacy place",client.get("trip?id="+id,200).path("itinerary").get(0).path("title").asText());
        } finally {
            try(Connection c=DriverManager.getConnection(System.getProperty("DB_URL"),System.getProperty("DB_USER"),System.getProperty("DB_PASSWORD"))){Database.execute(c,"UPDATE destinations SET active=FALSE WHERE id=?",destination);}
        }
    }
    @Test void concurrentWishlistSavesAreIdempotent()throws Exception{
        Client c=new Client();c.register("parallel-wishlist@test.invalid");
        var request=HttpRequest.newBuilder(URI.create(base+"/api/wishlist")).header("Content-Type","application/json").header("X-CSRF-Token",c.csrf).POST(HttpRequest.BodyPublishers.ofString("{\"destination_id\":1,\"saved\":true}")).build();
        var first=c.http.sendAsync(request,HttpResponse.BodyHandlers.ofString());var second=c.http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,first.get(20,java.util.concurrent.TimeUnit.SECONDS).statusCode());assertEquals(200,second.get(20,java.util.concurrent.TimeUnit.SECONDS).statusCode());
        assertEquals(1,c.get("data",200).path("wishlist").size());
    }
    @Test void packingEditsPreserveIdentityAndRejectUnknownActions()throws Exception{
        Client c=new Client();c.register("packing-edit@test.invalid");long id=c.post("trip",trip(),200).path("id").asLong();
        JsonNode before=c.get("trip?id="+id,200);long item=before.path("packing").get(0).path("id").asLong();
        c.post("packing",Map.of("trip_id",id,"id",item,"action","toggle","packed",true),200);
        c.post("packing",Map.of("trip_id",id,"id",item,"category","Other","label","Updated essential"),200);
        JsonNode after=c.get("trip?id="+id,200);assertEquals(before.path("packing").size(),after.path("packing").size());
        assertEquals(item,after.path("packing").get(0).path("id").asLong());assertEquals("Updated essential",after.path("packing").get(0).path("label").asText());assertTrue(after.path("packing").get(0).path("packed").asBoolean());
        c.post("packing",Map.of("trip_id",id,"action","typo","category","Other","label","Must not save"),400);
        assertEquals(5,c.get("trip?id="+id,200).path("packing").size());
    }
    @Test void passwordWhitespaceIsSignificant()throws Exception{
        Client c=new Client();c.session();String password="  A real passphrase 123  ";
        c.post("register",Map.of("name","Whitespace","email","spaces@test.invalid","password",password),200);c.session();c.post("logout",Map.of(),200);c.session();
        c.post("login",Map.of("email","spaces@test.invalid","password",password.trim()),401);
        c.post("login",Map.of("email","spaces@test.invalid","password",password),200);c.session();
        c.post("password",Map.of("current_password",password,"password","  Changed passphrase 456  "),200);
        c.session();c.post("login",Map.of("email","spaces@test.invalid","password","  Changed passphrase 456  "),200);
    }
    @Test void hiddenDestinationsPreserveExistingTripEditing()throws Exception{
        long destination;
        try(Connection c=DriverManager.getConnection(System.getProperty("DB_URL"),System.getProperty("DB_USER"),System.getProperty("DB_PASSWORD"))){
            destination=Database.insert(c,"INSERT INTO destinations(name,region,description,season,category,daily_cost,duration,activities,attractions,image) VALUES (?,?,?,?,?,?,?,?,?,?)","Hidden fixture","Region","Description","Summer","Nature",100,1,"Walking","Park","mountains.jpg");
        }
        Client client=new Client();client.register("hidden-edit@test.invalid");var body=trip();body.put("destination_id",destination);long id=client.post("trip",body,200).path("id").asLong();
        try(Connection c=DriverManager.getConnection(System.getProperty("DB_URL"),System.getProperty("DB_USER"),System.getProperty("DB_PASSWORD"))){Database.execute(c,"UPDATE destinations SET active=FALSE WHERE id=?",destination);}
        client.post("trip",body,400);body.put("id",id);body.put("title","Edited after unpublishing");client.post("trip",body,200);
        assertEquals("Edited after unpublishing",client.get("trip?id="+id,200).path("title").asText());
    }
    @Test void malformedPhotosAndSettingsFailWithoutPartialWrites()throws Exception{
        Client c=new Client();c.register("damaged-photo@test.invalid");long id=c.post("trip",trip(),200).path("id").asLong();c.post("trip-status",Map.of("id",id,"status","COMPLETED"),200);
        byte[] truncated=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB");
        c.post("memory",Map.of("trip_id",id,"title","Broken image","memory_date",LocalDate.now().toString(),"photo","data:image/png;base64,"+Base64.getEncoder().encodeToString(truncated)),400);
        assertEquals(0,c.get("data",200).path("memories").size());
        Client admin=new Client();admin.session();admin.post("login",Map.of("email","admin@test.invalid","password","Admin-test-only-1234"),200);admin.session();
        JsonNode before=admin.get("admin",200).path("settings");
        admin.post("admin/settings",Map.of("announcement","Must not persist","support_email","invalid","registration_enabled","false"),400);
        assertEquals(before,admin.get("admin",200).path("settings"));
    }
    static Tomcat server;static String base;static final ObjectMapper JSON=new ObjectMapper();
    @BeforeAll static void setup()throws Exception{System.setProperty("ADMIN_EMAIL","admin@test.invalid");System.setProperty("ADMIN_PASSWORD","Admin-test-only-1234");server=DevServer.start(0,System.getProperty("TEST_DB_URL","jdbc:h2:mem:test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));base="http://127.0.0.1:"+server.getConnector().getLocalPort();}
    @AfterAll static void stop()throws Exception{server.stop();server.destroy();}
    static class Client {
        HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String csrf;
        JsonNode get(String path,int status)throws Exception{HttpResponse<String> r=http.send(HttpRequest.newBuilder(URI.create(base+"/api/"+path)).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(status,r.statusCode(),r.body());return JSON.readTree(r.body());}
        JsonNode post(String path,Map<String,Object>b,int status)throws Exception{HttpResponse<String> r=http.send(HttpRequest.newBuilder(URI.create(base+"/api/"+path)).header("Content-Type","application/json").header("X-CSRF-Token",csrf==null?"invalid":csrf).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(b))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(status,r.statusCode(),path+": "+r.body());return JSON.readTree(r.body());}
        void session()throws Exception{csrf=get("session",200).path("csrf").asText();}
        void register(String email)throws Exception{session();post("register",Map.of("name","Traveler <safe>","email",email,"password","Strong-test-password-123"),200);session();}
    }
    static Map<String,Object> trip(){return new HashMap<>(Map.of("destination_id",1,"title","Test adventure","start_date",LocalDate.now().toString(),"end_date",LocalDate.now().toString(),"people",2,"budget","10000.00","interests","Nature"));}
    @Test void pageAndHeaders()throws Exception{var r=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+"/")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,r.statusCode());assertTrue(r.body().contains("Roam"));assertTrue(r.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));new Client().get("health",200);}
    @Test void fullTripLifecycleAndIsolation()throws Exception{
        Client a=new Client();a.register("one@test.invalid");Client other=new Client();other.register("two@test.invalid");
        assertEquals(6,a.get("data",200).path("destinations").size());a.get("admin",403);
        long id=a.post("trip",trip(),200).path("id").asLong();JsonNode t=a.get("trip?id="+id,200);assertEquals(1,t.path("itinerary").size());assertEquals(5,t.path("packing").size());
        other.get("trip?id="+id,404);other.post("trip-status",Map.of("id",id,"status","CANCELLED"),404);
        a.post("expenses",Map.of("trip_id",id,"category","Hotel","label","Stay","amount","1200.25"),200);
        a.post("expenses",Map.of("trip_id",id,"category","Food","label","Bad","amount","-1"),400);
        t=a.get("trip?id="+id,200);long expense=t.path("expenses").get(0).path("id").asLong();a.post("expenses",Map.of("id",expense,"trip_id",id,"category","Hotel","label","Updated stay","amount","900.50"),200);
        long pack=t.path("packing").get(0).path("id").asLong();a.post("packing",Map.of("trip_id",id,"id",pack,"action","toggle","packed",true),200);
        long item=t.path("itinerary").get(0).path("id").asLong();a.post("itinerary",Map.of("trip_id",id,"id",item,"day_number",1,"time_slot","11:30","title","A walk","note","Waterfront"),200);
        a.post("itinerary",Map.of("trip_id",id,"day_number",2,"time_slot","11:30","title","Out of range","note",""),400);
        a.post("wishlist",Map.of("destination_id",1,"saved",true),200);a.post("wishlist",Map.of("destination_id",1,"saved",true),200);assertEquals(1,a.get("data",200).path("wishlist").size());
        a.post("memory",Map.of("trip_id",id,"title","Too early","memory_date",LocalDate.now().toString()),400);
        a.post("trip-status",Map.of("id",id,"status","COMPLETED"),200);
        a.post("expenses",Map.of("trip_id",id,"category","Food","label","Late","amount","1"),400);
        a.post("memory",Map.of("trip_id",id,"title","Good day","memory_date",LocalDate.now().toString(),"note","Private memory"),200);
        a.post("feedback",Map.of("trip_id",id,"rating",5,"message","A great experience"),200);a.post("feedback",Map.of("trip_id",id,"rating",5,"message","Duplicate"),409);
        assertEquals(0,other.get("data",200).path("memories").size());
        long cancelled=a.post("trip",trip(),200).path("id").asLong();a.post("trip-status",Map.of("id",cancelled,"status","CANCELLED"),200);a.post("trip-status",Map.of("id",cancelled,"status","COMPLETED"),400);
    }
    @Test void csrfValidationAndPasswordRevocation()throws Exception{
        Client c=new Client();c.session();String csrf=c.csrf;c.csrf="wrong";c.post("register",Map.of("name","No","email","csrf@test.invalid","password","Strong-test-password-123"),403);c.csrf=csrf;
        c.post("register",Map.of("name","No","email","bad","password","short"),400);c.register("password@test.invalid");
        var invalid=trip();invalid.put("end_date",LocalDate.now().minusDays(1).toString());c.post("trip",invalid,400);invalid=trip();invalid.put("people",0);c.post("trip",invalid,400);
        c.post("password",Map.of("current_password","incorrect","password","Replacement-password-123"),400);
        c.post("password",Map.of("current_password","Strong-test-password-123","password","Replacement-password-123"),200);c.get("data",401);
        c.session();c.post("login",Map.of("email","password@test.invalid","password","Strong-test-password-123"),401);c.post("login",Map.of("email","password@test.invalid","password","Replacement-password-123"),200);
    }
    @Test void adminControlsAndSessionRevocation()throws Exception{
        Client admin=new Client();admin.session();admin.post("login",Map.of("email","admin@test.invalid","password","Admin-test-only-1234"),200);admin.session();
        Client client=new Client();client.register("disabled@test.invalid");long uid=client.get("session",200).path("user").path("id").asLong();
        admin.post("admin/user",Map.of("id",uid,"active",false),200);client.get("data",401);admin.post("admin/user",Map.of("id",uid,"active",true),200);
        admin.post("admin/settings",Map.of("announcement","New adventures","support_email","help@test.invalid","registration_enabled","false"),200);
        Client blocked=new Client();blocked.session();blocked.post("register",Map.of("name","Blocked","email","closed@test.invalid","password","Strong-test-password-123"),400);
        admin.post("admin/settings",Map.of("announcement","Your next chapter starts somewhere new.","support_email","help@test.invalid","registration_enabled","true"),200);
        assertTrue(admin.get("admin",200).path("audit").size()>=4);
    }
    @Test void loginThrottle()throws Exception{Client c=new Client();c.session();for(int i=0;i<8;i++)c.post("login",Map.of("email","unknown@test.invalid","password","not-the-password"),401);c.post("login",Map.of("email","unknown@test.invalid","password","not-the-password"),429);}
    @Test void photoPrivacyNormalizationAndRemoval()throws Exception{
        Client c=new Client();c.register("photo@test.invalid");long trip=c.post("trip",trip(),200).path("id").asLong();c.post("trip-status",Map.of("id",trip,"status","COMPLETED"),200);
        var image=new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        String data="data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray());
        c.post("memory",Map.of("trip_id",trip,"title","Photo","memory_date",LocalDate.now().toString(),"photo",data),200);
        long memory=c.get("data",200).path("memories").get(0).path("id").asLong();
        var r=c.http.send(HttpRequest.newBuilder(URI.create(base+"/api/photo?id="+memory)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,r.statusCode());assertEquals("image/jpeg",r.headers().firstValue("content-type").orElse(""));assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(r.body())));
        Client other=new Client();other.register("photo-other@test.invalid");other.get("photo?id="+memory,404);other.post("memory",Map.of("id",memory,"action","delete"),404);
        c.post("memory",Map.of("trip_id",trip,"title","Bad","memory_date",LocalDate.now().toString(),"photo","data:image/png;base64,bm90YW5pbWFnZQ=="),400);
        c.post("memory",Map.of("id",memory,"action","delete"),200);c.get("photo?id="+memory,404);
    }
    @Test void concurrentReadSmoke()throws Exception{
        Client c=new Client();c.register("load@test.invalid");List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> requests=new ArrayList<>();long began=System.nanoTime();
        for(int i=0;i<20;i++)requests.add(c.http.sendAsync(HttpRequest.newBuilder(URI.create(base+"/api/data")).GET().build(),HttpResponse.BodyHandlers.ofString()));
        for(var future:requests){var r=future.get(20,java.util.concurrent.TimeUnit.SECONDS);assertEquals(200,r.statusCode());assertTrue(JSON.readTree(r.body()).path("destinations").size()>0);}
        System.out.println("CONCURRENT_READ_SMOKE: 20 authenticated catalog requests in "+((System.nanoTime()-began)/1_000_000)+" ms (local test, not a capacity certification)");
    }
    @Test void adminCatalogAndFeedbackRoundTrip()throws Exception{
        Client admin=new Client();admin.session();admin.post("login",Map.of("email","admin@test.invalid","password","Admin-test-only-1234"),200);admin.session();
        Map<String,Object> d=new HashMap<>();d.put("name","Test destination");d.put("region","Test region");d.put("description","A curated test destination");d.put("season","Autumn");d.put("category","Nature");d.put("daily_cost","1500.00");d.put("duration",2);d.put("activities","Nature,Food");d.put("attractions","Park,Museum");d.put("image","mountains.jpg");d.put("active",false);
        admin.post("admin/destination",d,200);JsonNode catalog=admin.get("admin",200).path("destinations");long id=0;for(JsonNode row:catalog)if(row.path("name").asText().equals("Test destination"))id=row.path("id").asLong();assertTrue(id>0);
        Client client=new Client();client.register("catalog@test.invalid");assertEquals(6,client.get("data",200).path("destinations").size());d.put("id",id);d.put("active",true);admin.post("admin/destination",d,200);assertEquals(7,client.get("data",200).path("destinations").size());
        long trip=client.post("trip",trip(),200).path("id").asLong();client.post("trip-status",Map.of("id",trip,"status","COMPLETED"),200);client.post("feedback",Map.of("trip_id",trip,"rating",4,"message","Lovely planning tool"),200);long fid=client.get("data",200).path("feedback").get(0).path("id").asLong();admin.post("admin/feedback",Map.of("id",fid,"response","Thank you for traveling with us."),200);assertEquals("Thank you for traveling with us.",client.get("data",200).path("feedback").get(0).path("response").asText());
        d.put("active",false);admin.post("admin/destination",d,200);
    }
    @Test void adminPaginationReachesOlderAccounts()throws Exception{
        String password=Security.hash("Fixture-password-2026");
        try(Connection c=DriverManager.getConnection(System.getProperty("DB_URL"),System.getProperty("DB_USER"),System.getProperty("DB_PASSWORD"))){
            for(int i=0;i<51;i++)Database.execute(c,"INSERT INTO users(name,email,password) VALUES (?,?,?)","page-fixture-"+i,"page-fixture-"+i+"@test.invalid",password);
        }
        Client admin=new Client();admin.session();admin.post("login",Map.of("email","admin@test.invalid","password","Admin-test-only-1234"),200);admin.session();
        JsonNode first=admin.get("admin?q=page-fixture",200),second=admin.get("admin?q=page-fixture&user_page=2",200);
        assertEquals(50,first.path("users").size());assertEquals(51,first.path("pagination").path("user_total").asInt());assertEquals(1,second.path("users").size());
        assertNotEquals(first.path("users").get(0).path("id"),second.path("users").get(0).path("id"));admin.get("admin?user_page=0",400);
    }
    @Test void sessionsSurviveInstanceChangesAndRevokeEverywhere()throws Exception{
        Client client=new Client();client.register("shared-session@test.invalid");
        String csrf=client.csrf;
        String token=((CookieManager)client.http.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream().filter(c->c.getName().equals("ROAMSESSION")).findFirst().orElseThrow().getValue();
        try(Connection c=DriverManager.getConnection(System.getProperty("DB_URL"),System.getProperty("DB_USER"),System.getProperty("DB_PASSWORD"))){
            assertEquals(1,Database.query(c,"SELECT session_key FROM web_sessions WHERE session_key=?",DatabaseSession.digest(token)).size());
            assertTrue(Database.query(c,"SELECT session_key FROM web_sessions WHERE session_key=?",token).isEmpty());
        }
        System.setProperty("DB_INITIALIZE","false");Tomcat second;
        try{second=DevServer.start(0,System.getProperty("DB_URL"));}finally{System.clearProperty("DB_INITIALIZE");}
        try{
            String url="http://127.0.0.1:"+second.getConnector().getLocalPort()+"/api/";
            var r=client.http.send(HttpRequest.newBuilder(URI.create(url+"session")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,r.statusCode());assertEquals("shared-session@test.invalid",JSON.readTree(r.body()).path("user").path("email").asText());assertEquals(csrf,JSON.readTree(r.body()).path("csrf").asText());
            r=client.http.send(HttpRequest.newBuilder(URI.create(url+"logout")).header("Content-Type","application/json").header("X-CSRF-Token",csrf).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,r.statusCode());
            var stale=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+"/api/data")).header("Cookie","ROAMSESSION="+token).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,stale.statusCode());
        }finally{second.stop();second.destroy();}
    }
    @Test void twelveUserMixedWorkload()throws Exception{
        List<Client> clients=new ArrayList<>();List<Long> trips=new ArrayList<>();
        for(int i=0;i<12;i++){Client c=new Client();c.register("capacity-"+i+"@test.invalid");clients.add(c);trips.add(c.post("trip",trip(),200).path("id").asLong());}
        var executor=java.util.concurrent.Executors.newFixedThreadPool(12);
        var gate=new java.util.concurrent.CountDownLatch(1);List<Long> latencies=Collections.synchronizedList(new ArrayList<>());List<java.util.concurrent.Future<?>> jobs=new ArrayList<>();
        try{
            for(int i=0;i<12;i++){final int index=i;jobs.add(executor.submit(()->{try{gate.await();Client c=clients.get(index);long tripId=trips.get(index);for(int round=0;round<3;round++){
                long start=System.nanoTime();c.get("data",200);latencies.add((System.nanoTime()-start)/1_000_000);
                start=System.nanoTime();c.post("expenses",Map.of("trip_id",tripId,"category","Food","label","Meal "+round,"amount","250.00"),200);latencies.add((System.nanoTime()-start)/1_000_000);
                start=System.nanoTime();assertEquals(round+1,c.get("trip?id="+tripId,200).path("expenses").size());latencies.add((System.nanoTime()-start)/1_000_000);
            }}catch(Exception e){throw new RuntimeException(e);}}));}
            gate.countDown();for(var job:jobs)job.get(60,java.util.concurrent.TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
        Collections.sort(latencies);long p95=latencies.get((int)Math.ceil(latencies.size()*.95)-1);
        assertEquals(108,latencies.size());assertTrue(p95<5000,"Local p95 exceeded five-second smoke threshold: "+p95);
        System.out.println("TWELVE_USER_MIXED_WORKLOAD: requests="+latencies.size()+", failures=0, p95_ms="+p95+", max_ms="+latencies.get(latencies.size()-1)+"; local warm-server result, production verification still required");
    }
}
