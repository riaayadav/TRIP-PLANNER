package com.tripplanner;

import com.zaxxer.hikari.*;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** JDBC DAO: bound parameters, bounded result sets and explicit transaction ownership. */
public final class Database implements AutoCloseable {
    private final HikariDataSource pool;
    public static String env(String key, String fallback) { return System.getProperty(key, System.getenv().getOrDefault(key, fallback)); }
    public Database() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(env("DB_URL", "jdbc:mysql://localhost:3306/trip_planner?connectionTimeZone=UTC"));
        cfg.setUsername(env("DB_USER", "trip_planner")); cfg.setPassword(env("DB_PASSWORD", ""));
        int poolSize=Integer.parseInt(env("DB_POOL_SIZE","5"));
        if(poolSize<1||poolSize>20)throw new IllegalArgumentException("DB_POOL_SIZE must be between 1 and 20");
        cfg.setMaximumPoolSize(poolSize); cfg.setMinimumIdle(0); cfg.setConnectionTimeout(10000);
        pool = new HikariDataSource(cfg);
    }
    public Connection connection() throws SQLException { return pool.getConnection(); }
    public static List<Map<String,Object>> query(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s,args); s.setMaxRows(1000); s.setQueryTimeout(10);
            try (ResultSet r=s.executeQuery()) {
                List<Map<String,Object>> out=new ArrayList<>(); ResultSetMetaData m=r.getMetaData();
                while(r.next()) { Map<String,Object> row=new LinkedHashMap<>(); for(int i=1;i<=m.getColumnCount();i++) {
                    Object v=r.getObject(i); if(v instanceof java.sql.Date || v instanceof Timestamp) v=v.toString();
                    row.put(m.getColumnLabel(i).toLowerCase(Locale.ROOT),v);
                } out.add(row); } return out;
            }
        }
    }
    public static int execute(Connection c,String sql,Object... args) throws SQLException { try(PreparedStatement s=c.prepareStatement(sql)){ bind(s,args); s.setQueryTimeout(10); return s.executeUpdate(); } }
    public static long insert(Connection c,String sql,Object... args) throws SQLException { try(PreparedStatement s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){bind(s,args);s.executeUpdate();try(ResultSet r=s.getGeneratedKeys()){if(!r.next())throw new SQLException("No generated key");return r.getLong(1);}} }
    private static void bind(PreparedStatement s,Object[] args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    public void initialize() throws Exception {
        try(Connection c=connection()) {
            if("false".equals(env("DB_INITIALIZE","true"))) {
                if(query(c,"SELECT version FROM schema_version WHERE version=2").isEmpty())throw new SQLException("Schema version 2 is required; run the controlled migration before deployment");
                return;
            }
            execute(c,"CREATE TABLE IF NOT EXISTS schema_version (version INT PRIMARY KEY)");
            if(query(c,"SELECT version FROM schema_version WHERE version=1").isEmpty()) {
                String schema=new String(Objects.requireNonNull(getClass().getResourceAsStream("/schema.sql")).readAllBytes(),StandardCharsets.UTF_8);
                for(String part:schema.split(";")) if(!part.isBlank()) {
                    // MySQL DDL commits independently. Safely resume a partial first startup.
                    if(part.trim().startsWith("CREATE INDEX")) {
                        boolean exists=false;
                        try(ResultSet indexes=c.getMetaData().getIndexInfo(c.getCatalog(),null,"trips",false,false)){
                            while(indexes.next())if("idx_trips_user".equalsIgnoreCase(indexes.getString("INDEX_NAME")))exists=true;
                        }
                        if(exists)continue;
                    }
                    execute(c,part);
                }
                execute(c,"INSERT INTO schema_version(version) VALUES (1)");
            }
            if(query(c,"SELECT version FROM schema_version WHERE version=2").isEmpty()){
                execute(c,"CREATE TABLE IF NOT EXISTS web_sessions (session_key VARCHAR(64) PRIMARY KEY,uid BIGINT,auth_version INT,csrf VARCHAR(64) NOT NULL,expires_at BIGINT NOT NULL)");
                boolean indexed=false;
                try(ResultSet indexes=c.getMetaData().getIndexInfo(c.getCatalog(),null,"web_sessions",false,false)){
                    while(indexes.next())if("idx_web_sessions_expiry".equalsIgnoreCase(indexes.getString("INDEX_NAME")))indexed=true;
                }
                if(!indexed)execute(c,"CREATE INDEX idx_web_sessions_expiry ON web_sessions(expires_at)");
                execute(c,"INSERT INTO schema_version(version) VALUES (2)");
            }
            if(query(c,"SELECT id FROM destinations LIMIT 1").isEmpty()) seed(c);
            if(query(c,"SELECT setting_key FROM settings LIMIT 1").isEmpty()) {
                execute(c,"INSERT INTO settings VALUES (?,?)","announcement","Your next chapter starts somewhere new.");
                execute(c,"INSERT INTO settings VALUES (?,?)","registration_enabled","true");
                execute(c,"INSERT INTO settings VALUES (?,?)","support_email","support@example.com");
            }
            String email=env("ADMIN_EMAIL", ""), password=env("ADMIN_PASSWORD", "");
            if(!email.isBlank() && query(c,"SELECT id FROM users WHERE role='ADMIN' LIMIT 1").isEmpty()) {
                Security.validatePassword(password);
                execute(c,"INSERT INTO users(name,email,password,role) VALUES (?,?,?,'ADMIN')","Administrator",email.toLowerCase(Locale.ROOT),Security.hash(password));
            }
        }
    }
    private void seed(Connection c)throws SQLException {
        String[][] rows={
            {"Manali","Himachal Pradesh, India","Pine forests, mountain trails and slow mornings in the Himalayas.","Summer","Adventure","2800","5","Hiking,Photography,Nature","Solang Valley,Old Manali,Jogini Falls","mountains.jpg"},
            {"Goa","Western India","A coastal escape with beach walks, heritage streets and local food.","Winter","Beach","3500","4","Beach,Food,Culture","Fontainhas,Palolem Beach,Fort Aguada","coast.jpg"},
            {"Munnar","Kerala, India","Rolling tea gardens, misty viewpoints and peaceful hill-country stays.","Monsoon","Nature","2600","3","Nature,Photography,Relaxation","Tea Museum,Mattupetty Dam,Top Station","mountains.jpg"},
            {"Jaipur","Rajasthan, India","Explore pink sandstone architecture, royal courtyards and colorful markets.","Winter","Culture","3000","3","Culture,Food,Shopping","Amber Fort,City Palace,Hawa Mahal","city.jpg"},
            {"Udaipur","Rajasthan, India","Lakeside sunsets, palaces and unhurried walks through the old city.","Autumn","Relaxation","3200","3","Culture,Relaxation,Photography","Lake Pichola,City Palace,Saheliyon Ki Bari","city.jpg"},
            {"Rishikesh","Uttarakhand, India","Riverside walks, yoga and outdoor adventures in the Himalayan foothills.","Autumn","Adventure","2200","3","Adventure,Nature,Wellness","Triveni Ghat,Beatles Ashram,Neer Garh Falls","mountains.jpg"}
        };
        for(String[] r:rows)execute(c,"INSERT INTO destinations(name,region,description,season,category,daily_cost,duration,activities,attractions,image) VALUES (?,?,?,?,?,?,?,?,?,?)",(Object[])r);
    }
    public void close(){pool.close();}
}
