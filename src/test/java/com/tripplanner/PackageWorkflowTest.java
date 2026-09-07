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

class PackageWorkflowTest {
    static Tomcat server;static String base;static final ObjectMapper JSON=new ObjectMapper();
    @BeforeAll static void setup()throws Exception{System.setProperty("ADMIN_EMAIL","admin@test.invalid");System.setProperty("ADMIN_PASSWORD","Admin-test-only-1234");server=DevServer.start(0,System.getProperty("TEST_DB_URL","jdbc:h2:mem:packages;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));base="http://127.0.0.1:"+server.getConnector().getLocalPort();}
    @AfterAll static void stop()throws Exception{server.stop();server.destroy();}
    static class Client {
        HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String csrf;
        JsonNode get(String path,int status)throws Exception{HttpResponse<String> r=http.send(HttpRequest.newBuilder(URI.create(base+"/api/"+path)).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(status,r.statusCode(),r.body());return JSON.readTree(r.body());}
        JsonNode post(String path,Map<String,Object>b,int status)throws Exception{HttpResponse<String> r=http.send(HttpRequest.newBuilder(URI.create(base+"/api/"+path)).header("Content-Type","application/json").header("X-CSRF-Token",csrf==null?"invalid":csrf).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(b))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(status,r.statusCode(),path+": "+r.body());return JSON.readTree(r.body());}
        void session()throws Exception{csrf=get("session",200).path("csrf").asText();}
        void register(String email)throws Exception{session();post("register",Map.of("name","Traveler <safe>","email",email,"password","Strong-test-password-123"),200);session();}
    }
    @Test void packageLifecyclePermissionsSnapshotsAndDemoLedger()throws Exception{
        Client admin=new Client();admin.session();admin.post("login",Map.of("email","admin@test.invalid","password","Admin-test-only-1234"),200);admin.session();
        Client traveler=new Client();traveler.register("package-owner@test.invalid");Client stranger=new Client();stranger.register("package-stranger@test.invalid");
        traveler.get("admin/packages",403);traveler.post("admin/hotel",Map.of(),403);
        long hotel=admin.post("admin/hotel",Map.of("name","Test lodge","location","Test valley","description","Rooms","amenities","Breakfast","active",true),200).path("id").asLong();
        long transport=admin.post("admin/transport",Map.of("name","Test transfer","mode","Car","route_details","Station to lodge","description","Shared car","active",true),200).path("id").asLong();
        var pkg=new LinkedHashMap<String,Object>();pkg.put("destination_id",1);pkg.put("hotel_id",hotel);pkg.put("transport_id",transport);pkg.put("name","Test escape");pkg.put("description","A complete package");pkg.put("inclusions","Hotel, transport and breakfast");pkg.put("exclusions","Airfare");pkg.put("itinerary","Day 1: arrival");pkg.put("terms","Demo only; subject to confirmation");pkg.put("price","1000.00");pkg.put("duration",2);pkg.put("max_people",4);pkg.put("image","mountains.jpg");pkg.put("active",false);
        long pid=admin.post("admin/package",pkg,200).path("id").asLong();assertEquals(0,traveler.get("packages",200).path("packages").size());
        var request=new LinkedHashMap<String,Object>();request.put("package_id",pid);request.put("version",1);request.put("people",2);request.put("start_date",LocalDate.now().plusDays(5).toString());request.put("accepted_terms",true);request.put("request_key",UUID.randomUUID().toString());
        traveler.post("booking",request,400);pkg.put("id",pid);pkg.put("version",1);pkg.put("active",true);admin.post("admin/package",pkg,200);
        assertEquals(1,traveler.get("packages",200).path("packages").size());traveler.post("booking",request,400);request.put("version",2);request.put("people",5);traveler.post("booking",request,400);request.put("people",2);
        admin.post("admin/hotel",Map.of("id",hotel,"action","delete"),400);
        long bid=traveler.post("booking",request,200).path("id").asLong();assertEquals(bid,traveler.post("booking",request,200).path("id").asLong());
        assertEquals(0,stranger.get("packages",200).path("bookings").size());stranger.post("booking-status",Map.of("id",bid),404);stranger.post("booking-demo-payment",Map.of("booking_id",bid,"request_key",UUID.randomUUID().toString()),404);
        var booked=traveler.get("packages",200).path("bookings").get(0);assertEquals(2000,booked.path("total").asInt());assertEquals("UNPAID",booked.path("payment_status").asText());
        pkg.put("version",2);pkg.put("price","1500.00");pkg.put("inclusions","Changed offer");admin.post("admin/package",pkg,200);
        booked=traveler.get("packages",200).path("bookings").get(0);assertEquals(2000,booked.path("total").asInt());assertTrue(booked.path("snapshot").asText().contains("Hotel, transport and breakfast"));
        var receipt=Map.<String,Object>of("booking_id",bid,"kind","RECEIPT","amount","500.00","reference","test-receipt-"+bid,"note","Simulated receipt");long payment=admin.post("admin/payment",receipt,200).path("id").asLong();assertEquals(payment,admin.post("admin/payment",receipt,200).path("id").asLong());
        assertEquals("PARTIAL",traveler.get("packages",200).path("bookings").get(0).path("payment_status").asText());
        admin.post("admin/payment",Map.of("booking_id",bid,"kind","REFUND","amount","501.00","reference","too-much-refund","note","Demo"),400);
        var demo=Map.<String,Object>of("booking_id",bid,"request_key",UUID.randomUUID().toString());long demoId=traveler.post("booking-demo-payment",demo,200).path("id").asLong();assertEquals(demoId,traveler.post("booking-demo-payment",demo,200).path("id").asLong());assertEquals("PAID",traveler.get("packages",200).path("bookings").get(0).path("payment_status").asText());
        admin.post("admin/payment",Map.of("booking_id",bid,"kind","RECEIPT","amount","1.00","reference","overpay","note","Demo"),400);
        admin.post("admin/booking-status",Map.of("id",bid,"status","CONFIRMED"),200);traveler.post("booking-status",Map.of("id",bid),400);admin.post("admin/booking-status",Map.of("id",bid,"status","COMPLETED"),400);
        admin.post("admin/booking-status",Map.of("id",bid,"status","CANCELLED"),200);
        admin.post("admin/payment",Map.of("booking_id",bid,"kind","REFUND","amount","2000.00","reference","refund-"+bid,"note","Demo cancellation refund"),200);assertEquals("REFUNDED",traveler.get("packages",200).path("bookings").get(0).path("payment_status").asText());
        admin.post("admin/package",Map.of("id",pid,"version",2,"action","delete"),400);admin.post("admin/package",Map.of("id",pid,"version",3,"action","delete"),200);
        assertEquals(0,traveler.get("packages",200).path("packages").size());assertEquals(1,traveler.get("packages",200).path("bookings").size());admin.post("admin/hotel",Map.of("id",hotel,"action","delete"),200);
    }

}
