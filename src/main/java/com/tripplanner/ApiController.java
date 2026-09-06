package com.tripplanner;

import jakarta.servlet.http.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.time.*;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import static com.tripplanner.Database.*;
import static com.tripplanner.TripService.*;

/** MVC HTTP boundary. All writes are POST, CSRF checked and transactional. */
public final class ApiController extends HttpServlet {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String DUMMY=Security.hash("dummy-password-for-timing-only");
    private static final TypeReference<Map<String,Object>> MAP=new TypeReference<>(){};
    private Database db(){return (Database)getServletContext().getAttribute("db");}
    protected void service(HttpServletRequest req,HttpServletResponse res)throws IOException{
        req.setCharacterEncoding("UTF-8");res.setCharacterEncoding("UTF-8");res.setContentType("application/json");
        res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");
        res.setHeader("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'");
        String path=Objects.toString(req.getPathInfo(),"");
        if(!Set.of("GET","POST").contains(req.getMethod())){error(res,405,"Method not allowed.");return;}
        try(Connection c=db().connection()){
            if(path.equals("/health")&&req.getMethod().equals("GET")){query(c,"SELECT 1");write(res,Map.of("status","ok"));return;}
            DatabaseSession session=DatabaseSession.load(c,req,res);
            Map<String,Object> user=null;
            if(session.getAttribute("uid") instanceof Number uid){
                List<Map<String,Object>> rows=query(c,"SELECT id,name,email,role,auth_version FROM users WHERE id=? AND active=TRUE",uid.longValue());
                if(!rows.isEmpty()&&Objects.equals(rows.get(0).get("auth_version"),session.getAttribute("version")))user=rows.get(0);
                else {session.invalidate();error(res,401,"Please sign in again.");return;}
            }
            if(path.equals("/session")&&req.getMethod().equals("GET")){
                Map<String,Object> out=new LinkedHashMap<>();out.put("csrf",session.getAttribute("csrf"));out.put("user",user);out.put("settings",settings(c));write(res,out);return;
            }
            Map<String,Object> b=new LinkedHashMap<>();
            if(req.getMethod().equals("POST")){
                if(!Objects.equals(session.getAttribute("csrf"),req.getHeader("X-CSRF-Token"))){error(res,403,"Your session changed. Refresh and try again.");return;}
                if(req.getContentType()==null||!req.getContentType().startsWith("application/json")){error(res,415,"JSON content is required.");return;}
                byte[] bytes=req.getInputStream().readNBytes(4_400_001);if(bytes.length>4_400_000){error(res,413,"Upload is too large.");return;}
                try{b=JSON.readValue(bytes,MAP);}catch(Exception e){throw new IllegalArgumentException("Invalid JSON request.");}
                if(b==null)throw new IllegalArgumentException("A JSON object is required.");
                if(path.equals("/login")||path.equals("/register")){authenticate(c,session,res,b,path);return;}
                if(path.equals("/logout")){session.invalidate();write(res,Map.of("ok",true));return;}
            }
            if(user==null){error(res,401,"Please sign in to continue.");return;}
            long uid=((Number)user.get("id")).longValue();
            if(path.startsWith("/admin")&&!user.get("role").equals("ADMIN")){error(res,403,"Administrator access is required.");return;}
            if(req.getMethod().equals("GET")){
                if(path.equals("/data")){write(res,data(c,uid));return;}
                if(path.equals("/trip")){
                    long id=parseId(req.getParameter("id"));Map<String,Object> t=owned(c,uid,id);
                    t.put("itinerary",query(c,"SELECT * FROM itinerary WHERE trip_id=? ORDER BY day_number,time_slot,id",id));
                    t.put("expenses",query(c,"SELECT * FROM expenses WHERE trip_id=? ORDER BY id",id));
                    t.put("packing",query(c,"SELECT * FROM packing WHERE trip_id=? ORDER BY id",id));write(res,t);return;
                }
                if(path.equals("/photo")){
                    List<Map<String,Object>> rows=query(c,"SELECT photo FROM memories WHERE id=? AND user_id=?",parseId(req.getParameter("id")),uid);
                    if(rows.isEmpty()||rows.get(0).get("photo")==null)throw new Missing();
                    Object raw=rows.get(0).get("photo");byte[] photo=raw instanceof Blob blob?blob.getBytes(1,(int)blob.length()):(byte[])raw;
                    res.setCharacterEncoding(null);res.setContentType("image/jpeg");res.setContentLength(photo.length);res.getOutputStream().write(photo);return;
                }
                if(path.equals("/admin")){
                    write(res,adminData(c,req));return;
                }
                throw new Missing();
            }
            c.setAutoCommit(false);
            try{
                // Serialize one user's writes so quota checks and idempotent saves cannot race.
                query(c,"SELECT id FROM users WHERE id=? FOR UPDATE",uid);
                Map<String,Object> out=new LinkedHashMap<>();out.put("ok",true);
                switch(path){
                    case "/trip" -> out.put("id",saveTrip(c,uid,b));
                    case "/trip-status" -> {long id=number(b,"id",1,Long.MAX_VALUE);Map<String,Object> t=owned(c,uid,id);editable(t);String status=choice(b,"status","CANCELLED","COMPLETED");if(status.equals("COMPLETED")&&LocalDate.parse(t.get("end_date").toString()).isAfter(LocalDate.now()))throw new IllegalArgumentException("A trip can be completed on or after its end date.");execute(c,"UPDATE trips SET status=? WHERE id=?",status,id);}
                    case "/expenses","/itinerary","/packing" -> child(c,uid,path.substring(1),b);
                    case "/wishlist" -> {long id=number(b,"destination_id",1,Long.MAX_VALUE);if(Boolean.TRUE.equals(b.get("saved"))){if(query(c,"SELECT id FROM destinations WHERE id=? AND active=TRUE",id).isEmpty())throw new Missing();if(query(c,"SELECT user_id FROM wishlist WHERE user_id=? AND destination_id=?",uid,id).isEmpty())execute(c,"INSERT INTO wishlist VALUES (?,?)",uid,id);}else execute(c,"DELETE FROM wishlist WHERE user_id=? AND destination_id=?",uid,id);}
                    case "/memory" -> {
                        if("delete".equals(b.get("action"))){if(execute(c,"DELETE FROM memories WHERE id=? AND user_id=?",number(b,"id",1,Long.MAX_VALUE),uid)==0)throw new Missing();}
                        else{long trip=number(b,"trip_id",1,Long.MAX_VALUE);Map<String,Object> t=owned(c,uid,trip);completed(t);LocalDate date=date(b,"memory_date");if(date.isBefore(LocalDate.parse(t.get("start_date").toString()))||date.isAfter(LocalDate.parse(t.get("end_date").toString())))throw new IllegalArgumentException("Memory date must be within the trip dates.");
                        if(((Number)query(c,"SELECT COUNT(*) AS n FROM memories WHERE user_id=?",uid).get(0).get("n")).longValue()>=200)throw new IllegalArgumentException("Your gallery has reached its 200-memory limit.");
                        execute(c,"INSERT INTO memories(user_id,trip_id,title,memory_date,note,photo) VALUES (?,?,?,?,?,?)",uid,trip,text(b,"title",150),date,optional(b,"note",2000),photo(b));}
                    }
                    case "/feedback" -> {long trip=number(b,"trip_id",1,Long.MAX_VALUE);completed(owned(c,uid,trip));execute(c,"INSERT INTO feedback(user_id,trip_id,rating,message) VALUES (?,?,?,?)",uid,trip,number(b,"rating",1,5),text(b,"message",2000));}
                    case "/profile" -> {String name=text(b,"name",100),email=email(b);execute(c,"UPDATE users SET name=?,email=? WHERE id=?",name,email,uid);}
                    case "/password" -> {String old=password(b,"current_password"),password=password(b,"password");Security.validatePassword(password);Map<String,Object> row=query(c,"SELECT password FROM users WHERE id=?",uid).get(0);if(!Security.verify(old,row.get("password").toString()))throw new IllegalArgumentException("Current password is incorrect.");execute(c,"UPDATE users SET password=?,auth_version=auth_version+1 WHERE id=?",Security.hash(password),uid);out.put("sign_in_again",true);}
                    case "/admin/destination" -> {long id=b.containsKey("id")?number(b,"id",1,Long.MAX_VALUE):0;String name=text(b,"name",120),region=text(b,"region",120),description=text(b,"description",4000),season=choice(b,"season","Summer","Monsoon","Autumn","Winter"),category=choice(b,"category","Adventure","Beach","Nature","Culture","Relaxation"),activities=text(b,"activities",1000),attractions=text(b,"attractions",1000),img=choice(b,"image","mountains.jpg","coast.jpg","city.jpg");Object[] args={name,region,description,season,category,money(b,"daily_cost"),number(b,"duration",1,60),activities,attractions,img,Boolean.TRUE.equals(b.get("active"))};if(id==0)id=insert(c,"INSERT INTO destinations(name,region,description,season,category,daily_cost,duration,activities,attractions,image,active) VALUES (?,?,?,?,?,?,?,?,?,?,?)",args);else{List<Object> a=new ArrayList<>(Arrays.asList(args));a.add(id);if(execute(c,"UPDATE destinations SET name=?,region=?,description=?,season=?,category=?,daily_cost=?,duration=?,activities=?,attractions=?,image=?,active=? WHERE id=?",a.toArray())==0)throw new Missing();}audit(c,uid,"Saved destination",id);}
                    case "/admin/user" -> {long id=number(b,"id",1,Long.MAX_VALUE);if(id==uid)throw new IllegalArgumentException("You cannot change your own access here.");Map<String,Object> target=query(c,"SELECT role FROM users WHERE id=?",id).stream().findFirst().orElseThrow(Missing::new);if(target.get("role").equals("ADMIN"))throw new IllegalArgumentException("Administrator accounts must be managed by the operator.");execute(c,"UPDATE users SET active=?,auth_version=auth_version+1 WHERE id=?",Boolean.TRUE.equals(b.get("active")),id);audit(c,uid,"Changed user access",id);}
                    case "/admin/settings" -> {email(Map.of("email",text(b,"support_email",254)));for(String key:List.of("announcement","support_email","registration_enabled")){String val=key.equals("registration_enabled")?choice(b,key,"true","false"):text(b,key,key.equals("announcement")?2000:254);execute(c,"UPDATE settings SET setting_value=? WHERE setting_key=?",val,key);}audit(c,uid,"Updated experience settings",0);}
                    case "/admin/feedback" -> {long id=number(b,"id",1,Long.MAX_VALUE);if(execute(c,"UPDATE feedback SET response=? WHERE id=?",text(b,"response",2000),id)==0)throw new Missing();audit(c,uid,"Responded to feedback",id);}
                    default -> throw new Missing();
                }
                c.commit();if(Boolean.TRUE.equals(out.get("sign_in_again")))session.invalidate();write(res,out);
            }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
        }catch(Missing e){error(res,404,"This item was not found.");}
        catch(IllegalArgumentException e){error(res,400,e.getMessage());}
        catch(SQLException e){if(e.getSQLState()!=null&&e.getSQLState().startsWith("23"))error(res,409,"This record already exists or conflicts with related data.");else{getServletContext().log("Database request failed",e);error(res,503,"The database is temporarily unavailable. Please try again.");}}
        catch(Exception e){getServletContext().log("Request failed",e);error(res,500,"We could not complete that request. Please try again.");}
    }
    private void authenticate(Connection c,DatabaseSession session,HttpServletResponse res,Map<String,Object>b,String path)throws Exception{
        String email=email(b),password=password(b,"password");String key=email;long now=System.currentTimeMillis();
        // Persisted account throttle works across restarts and application instances.
        c.setAutoCommit(false);
        try {
            execute(c,"DELETE FROM login_attempts WHERE window_start<?",now-900000);
            List<Map<String,Object>> attempts=query(c,"SELECT attempts FROM login_attempts WHERE attempt_key=? FOR UPDATE",key);
            if(!attempts.isEmpty()&&((Number)attempts.get(0).get("attempts")).intValue()>=8){c.commit();res.setHeader("Retry-After","900");error(res,429,"Too many attempts. Try again in 15 minutes.");return;}
            if(attempts.isEmpty())execute(c,"INSERT INTO login_attempts VALUES (?,1,?)",key,now);else execute(c,"UPDATE login_attempts SET attempts=attempts+1 WHERE attempt_key=?",key);
            c.commit();
        }finally{c.setAutoCommit(true);}
        if(path.equals("/register")){
            if(!"true".equals(settings(c).get("registration_enabled")))throw new IllegalArgumentException("Registration is currently closed.");
            Security.validatePassword(password);execute(c,"INSERT INTO users(name,email,password) VALUES (?,?,?)",text(b,"name",100),email,Security.hash(password));
        }
        List<Map<String,Object>> rows=query(c,"SELECT * FROM users WHERE email=?",email);
        boolean valid=Security.verify(password,rows.isEmpty()?DUMMY:rows.get(0).get("password").toString());
        if(!valid||rows.isEmpty()||!Boolean.TRUE.equals(rows.get(0).get("active"))){error(res,401,"Email or password is incorrect.");return;}
        execute(c,"DELETE FROM login_attempts WHERE attempt_key=?",key);
        session.authenticate(((Number)rows.get(0).get("id")).longValue(),((Number)rows.get(0).get("auth_version")).intValue());write(res,Map.of("ok",true));
    }
    private Map<String,Object> data(Connection c,long uid)throws SQLException{
        return Map.of("destinations",query(c,"SELECT * FROM destinations WHERE active=TRUE ORDER BY name"),"trips",query(c,"SELECT t.*,d.name AS destination,d.image,d.season,COALESCE((SELECT SUM(amount) FROM expenses e WHERE e.trip_id=t.id),0) AS estimated_total FROM trips t JOIN destinations d ON d.id=t.destination_id WHERE t.user_id=? ORDER BY t.start_date DESC",uid),"wishlist",query(c,"SELECT d.* FROM wishlist w JOIN destinations d ON d.id=w.destination_id WHERE w.user_id=? ORDER BY d.name",uid),"memories",query(c,"SELECT m.id,m.trip_id,m.title,m.memory_date,m.note,CASE WHEN m.photo IS NULL THEN FALSE ELSE TRUE END AS has_photo,t.title AS trip_title FROM memories m JOIN trips t ON t.id=m.trip_id WHERE m.user_id=? ORDER BY m.memory_date DESC",uid),"feedback",query(c,"SELECT f.*,t.title FROM feedback f JOIN trips t ON t.id=f.trip_id WHERE f.user_id=? ORDER BY f.id DESC",uid),"settings",settings(c));
    }
    private Map<String,Object> adminData(Connection c,HttpServletRequest req)throws SQLException {
        String search=Objects.toString(req.getParameter("q"),"").trim();
        if(search.length()>100)throw new IllegalArgumentException("Search must be at most 100 characters.");
        int usersPage=page(req,"user_page"),feedbackPage=page(req,"feedback_page");
        String match="%"+search+"%";
        long users=((Number)query(c,"SELECT COUNT(*) AS n FROM users WHERE name LIKE ? OR email LIKE ?",match,match).get(0).get("n")).longValue();
        long feedback=((Number)query(c,"SELECT COUNT(*) AS n FROM feedback").get(0).get("n")).longValue();
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("users",query(c,"SELECT id,name,email,role,active,created_at FROM users WHERE name LIKE ? OR email LIKE ? ORDER BY id DESC LIMIT 50 OFFSET ?",match,match,(usersPage-1)*50));
        out.put("feedback",query(c,"SELECT f.*,u.name,t.title FROM feedback f JOIN users u ON u.id=f.user_id JOIN trips t ON t.id=f.trip_id ORDER BY f.id DESC LIMIT 50 OFFSET ?",(feedbackPage-1)*50));
        out.put("destinations",query(c,"SELECT * FROM destinations ORDER BY name"));
        out.put("audit",query(c,"SELECT a.*,u.name FROM audit_log a JOIN users u ON u.id=a.actor_id ORDER BY a.id DESC LIMIT 100"));
        out.put("stats",query(c,"SELECT (SELECT COUNT(*) FROM users) AS users,(SELECT COUNT(*) FROM trips) AS trips,(SELECT COUNT(*) FROM destinations WHERE active=TRUE) AS destinations,(SELECT AVG(rating) FROM feedback) AS rating").get(0));
        out.put("settings",settings(c));
        out.put("pagination",Map.of("user_page",usersPage,"feedback_page",feedbackPage,"user_total",users,"feedback_total",feedback,"q",search));
        return out;
    }
    private static int page(HttpServletRequest req,String key){String value=req.getParameter(key);if(value==null)return 1;try{int page=Integer.parseInt(value);if(page<1||page>100000)throw new NumberFormatException();return page;}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid page number.");}}
    private static Map<String,String> settings(Connection c)throws SQLException{Map<String,String> out=new LinkedHashMap<>();for(Map<String,Object> row:query(c,"SELECT * FROM settings"))out.put(row.get("setting_key").toString(),row.get("setting_value").toString());return out;}
    private static String email(Map<String,Object>b){String s=text(b,"email",254).toLowerCase(Locale.ROOT);if(!s.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException("Enter a valid email address.");return s;}
    private static long parseId(String s){try{long id=Long.parseLong(s);if(id<1)throw new NumberFormatException();return id;}catch(Exception e){throw new IllegalArgumentException("Invalid identifier.");}}
    private static void audit(Connection c,long user,String action,long target)throws SQLException{execute(c,"INSERT INTO audit_log(actor_id,action,target_id) VALUES (?,?,?)",user,action,target);}
    private static byte[] photo(Map<String,Object>b)throws IOException{
        String raw=optional(b,"photo",4_100_000);if(raw.isBlank())return null;
        if(!raw.matches("data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/=\\r\\n]+"))throw new IllegalArgumentException("Upload a JPEG or PNG photograph.");
        byte[] bytes=Base64.getDecoder().decode(raw.substring(raw.indexOf(',')+1));if(bytes.length>3_000_000)throw new IllegalArgumentException("Photos must be under 3 MB.");
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IllegalArgumentException("That photo format cannot be read. Use JPEG or PNG.");var reader=readers.next();try{reader.setInput(stream);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||(long)w*h>20_000_000)throw new IllegalArgumentException("Photos must contain at most 20 million pixels.");BufferedImage input=reader.read(0);double scale=Math.min(1,1600.0/Math.max(w,h));BufferedImage clean=new BufferedImage(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)),BufferedImage.TYPE_INT_RGB);Graphics2D g=clean.createGraphics();try{g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,clean.getWidth(),clean.getHeight());g.drawImage(input,0,0,clean.getWidth(),clean.getHeight(),null);}finally{g.dispose();}ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(clean,"jpg",out);return out.toByteArray();}finally{reader.dispose();}}catch(IOException e){throw new IllegalArgumentException("That photo is damaged or incomplete. Use a valid JPEG or PNG.");}
    }
    private static void write(HttpServletResponse res,Object o)throws IOException{JSON.writeValue(res.getOutputStream(),o);}
    private static void error(HttpServletResponse res,int status,String message)throws IOException{res.setStatus(status);write(res,Map.of("error",message));}
}
