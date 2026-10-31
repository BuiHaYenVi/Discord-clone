package com.discordmini.server.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * [HAI-02 & HAI-08] Quản lý kết nối JDBC PostgreSQL / Neon Serverless an toàn.
 * Hỗ trợ đọc cấu hình từ .env, biến môi trường (NEON_JDBC_URL / DB_URL, v.v.), và db.properties.
 * Có cơ chế retry-with-backoff cho Neon cold-start và tự động chạy migration V003 idempotent.
 */
public class DatabaseConnection {

    private static String url;
    private static String user;
    private static String password;
    private static String driver;
    private static boolean driverLoaded = false;
    private static final Properties props = new Properties();
    private static final Map<String, String> dotenvMap = new HashMap<>();
    private static volatile boolean migrationsAttempted = false;

    static {
        loadProperties();
    }

    private static synchronized void loadProperties() {
        loadDotEnvFiles();

        String[] candidatePaths = {
            "config/db.properties",
            "db.properties",
            "com/discordmini/server/config/db.properties"
        };

        InputStream input = null;
        for (String path : candidatePaths) {
            input = DatabaseConnection.class.getClassLoader().getResourceAsStream(path);
            if (input != null) {
                System.out.println("[DB CONFIG] Đã tìm thấy file cấu hình: " + path);
                break;
            }
        }

        if (input == null) {
            String[] fileCandidates = {
                "config/db.properties",
                "Chatserver/config/db.properties",
                "src/com/discordmini/server/config/db.properties",
                "Chatserver/src/com/discordmini/server/config/db.properties"
            };
            for (String fc : fileCandidates) {
                File f = new File(fc);
                if (f.exists() && f.isFile()) {
                    try {
                        input = new FileInputStream(f);
                        System.out.println("[DB CONFIG] Đã tìm thấy file cấu hình trên đĩa: " + f.getAbsolutePath());
                        break;
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        if (input != null) {
            try (InputStream is = input) {
                props.load(is);
            } catch (Exception e) {
                System.err.println("[DB ERROR] Lỗi đọc db.properties: " + e.getMessage() + ". Sử dụng cấu hình mặc định.");
            }
        } else {
            System.err.println("[DB WARNING] Không tìm thấy file db.properties. Sử dụng biến môi trường/.env hoặc mặc định.");
        }

        url = firstNonBlank(
            getEnvOrDotenv("NEON_JDBC_URL"),
            getEnvOrDotenv("DATABASE_URL"),
            getEnvOrDotenv("DB_URL"),
            props.getProperty("db.url"),
            "jdbc:postgresql://localhost:5432/discord_mini"
        );
        user = firstNonBlank(
            getEnvOrDotenv("NEON_DB_USER"),
            getEnvOrDotenv("DB_USER"),
            props.getProperty("db.user"),
            ""
        );
        password = firstNonNull(
            getEnvOrDotenv("NEON_DB_PASSWORD"),
            getEnvOrDotenv("DB_PASSWORD"),
            props.getProperty("db.password"),
            ""
        );
        normalizeConnectionConfig();
        if (user == null || user.isEmpty()) {
            user = "postgres";
        }
        driver = firstNonBlank(
            getEnvOrDotenv("DB_DRIVER"),
            props.getProperty("db.driver"),
            "org.postgresql.Driver"
        );

        try {
            Class.forName(driver);
            driverLoaded = true;
            System.out.println("[DB CONFIG] Driver PostgreSQL đã nạp thành công: " + driver);
        } catch (ClassNotFoundException e) {
            System.err.println("[DB CRITICAL] Không tìm thấy PostgreSQL JDBC Driver! Thư viện org.postgresql.Driver chưa được thêm vào classpath.");
        }
    }

    /**
     * Tự động chuẩn hóa chuỗi kết nối Neon/PostgreSQL (ví dụ: postgresql://user:pass@host/db?sslmode=require)
     * sang chuẩn JDBC (jdbc:postgresql://host/db?sslmode=require) và tách user/password nếu nằm trong URI.
     */
    private static void normalizeConnectionConfig() {
        if (url == null) return;
        String raw = url.trim();
        if (raw.startsWith("psql ")) {
            raw = raw.substring(5).trim();
        }
        if ((raw.startsWith("'") && raw.endsWith("'")) || (raw.startsWith("\"") && raw.endsWith("\""))) {
            raw = raw.substring(1, raw.length() - 1).trim();
        }
        if (raw.startsWith("postgres://")) {
            raw = "jdbc:postgresql://" + raw.substring("postgres://".length());
        } else if (raw.startsWith("postgresql://")) {
            raw = "jdbc:postgresql://" + raw.substring("postgresql://".length());
        }
        if (raw.startsWith("jdbc:postgresql://")) {
            String rest = raw.substring("jdbc:postgresql://".length());
            int slashIdx = rest.indexOf('/');
            int atIdx = (slashIdx >= 0) ? rest.lastIndexOf('@', slashIdx) : rest.lastIndexOf('@');
            if (atIdx > 0) {
                String userInfo = rest.substring(0, atIdx);
                String hostAndPath = rest.substring(atIdx + 1);
                int colonIdx = userInfo.indexOf(':');
                if (colonIdx >= 0) {
                    String extractedUser = userInfo.substring(0, colonIdx);
                    String extractedPass = userInfo.substring(colonIdx + 1);
                    if (user == null || user.isEmpty() || "postgres".equals(user)) {
                        user = extractedUser;
                    }
                    if (password == null || password.isEmpty()) {
                        password = extractedPass;
                    }
                } else if (user == null || user.isEmpty() || "postgres".equals(user)) {
                    user = userInfo;
                }
                raw = "jdbc:postgresql://" + hostAndPath;
            }
        }
        url = raw;
    }

    private static void loadDotEnvFiles() {
        String[] dotenvCandidates = {
            ".env",
            "../.env",
            "Chatserver/.env",
            "Uri/DA_LapTrinhMangMayTinh/.env",
            "Uri/DA_LapTrinhMangMayTinh/Chatserver/.env"
        };
        for (String candidate : dotenvCandidates) {
            File f = new File(candidate);
            if (f.exists() && f.isFile()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                        int eqIdx = trimmed.indexOf('=');
                        if (eqIdx > 0) {
                            String k = trimmed.substring(0, eqIdx).trim();
                            String v = trimmed.substring(eqIdx + 1).trim();
                            if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                                v = v.substring(1, v.length() - 1);
                            }
                            dotenvMap.putIfAbsent(k, v);
                        }
                    }
                    System.out.println("[DB CONFIG] Đã nạp cấu hình từ .env: " + f.getAbsolutePath());
                } catch (Exception e) {
                    System.err.println("[DB WARNING] Không thể đọc file .env (" + candidate + "): " + e.getMessage());
                }
            }
        }
    }

    private static String getEnvOrDotenv(String key) {
        if (key == null || key.isEmpty()) return null;
        String val = System.getenv(key);
        if (val != null && !val.isEmpty()) return val;
        String sysProp = System.getProperty(key);
        if (sysProp != null && !sysProp.isEmpty()) return sysProp;
        return dotenvMap.get(key);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return "";
    }

    /**
     * Đọc giá trị cấu hình ưu tiên từ biến môi trường / .env, sau đó tới db.properties, cuối cùng là defaultVal.
     */
    public static String getEnvOrProp(String envKey, String propKey, String defaultVal) {
        String envVal = getEnvOrDotenv(envKey);
        if (envVal != null && !envVal.isEmpty()) {
            return envVal;
        }
        if (propKey != null && !propKey.isEmpty()) {
            String propVal = props.getProperty(propKey);
            if (propVal != null && !propVal.isEmpty()) {
                return propVal;
            }
        }
        return defaultVal;
    }

    /**
     * Lấy kết nối JDBC đến PostgreSQL với cơ chế retry-with-backoff cho Neon cold-start.
     */
    public static Connection getConnection() throws SQLException {
        if (!driverLoaded) {
            try {
                Class.forName(driver);
                driverLoaded = true;
            } catch (ClassNotFoundException e) {
                System.err.println("[DB ERROR] Driver JDBC chưa được nạp: " + e.getMessage());
                throw new SQLException("PostgreSQL JDBC Driver chưa có trong classpath", e);
            }
        }

        int maxAttempts = 3;
        long backoffMs = 250L;
        SQLException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                DriverManager.setLoginTimeout(5);
                return DriverManager.getConnection(url, user, password);
            } catch (SQLException e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    backoffMs *= 2;
                }
            }
        }

        System.err.println("[DB ERROR] Kết nối CSDL thất bại (URL: " + url + "): " +
                (lastException != null ? lastException.getMessage() : "Unknown error"));
        throw lastException != null ? lastException : new SQLException("Không thể kết nối CSDL");
    }

    /**
     * Kiểm tra kết nối CSDL hiện tại có hoạt động không.
     */
    public static boolean isConnected() {
        try (Connection conn = getConnection()) {
            return conn != null && conn.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Khởi tạo các bảng nền tảng (V001/V002) một cách idempotent nếu CSDL Neon mới tinh chưa chạy schema.sql.
     */
    private static void ensureBaseSchema(Statement stmt) throws SQLException {
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS public.users (
                id SERIAL PRIMARY KEY,
                username character varying(50) NOT NULL UNIQUE,
                password character varying(255) NOT NULL,
                display_name character varying(100) NOT NULL,
                status character varying(50) DEFAULT 'OFFLINE',
                created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
            );
            CREATE TABLE IF NOT EXISTS public.rooms (
                id SERIAL PRIMARY KEY,
                name character varying(100) NOT NULL,
                created_by integer REFERENCES public.users(id) ON DELETE SET NULL,
                created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
                is_private boolean DEFAULT false
            );
            CREATE TABLE IF NOT EXISTS public.room_members (
                id SERIAL PRIMARY KEY,
                room_id integer REFERENCES public.rooms(id) ON DELETE CASCADE,
                user_id integer REFERENCES public.users(id) ON DELETE CASCADE,
                role character varying(20) DEFAULT 'MEMBER',
                UNIQUE (room_id, user_id)
            );
            CREATE TABLE IF NOT EXISTS public.room_roles (
                id SERIAL PRIMARY KEY,
                room_id integer NOT NULL REFERENCES public.rooms(id) ON DELETE CASCADE,
                name character varying(50) NOT NULL,
                can_manage_room boolean DEFAULT false NOT NULL,
                can_manage_messages boolean DEFAULT false NOT NULL,
                can_invite_members boolean DEFAULT false NOT NULL,
                created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                UNIQUE (room_id, name)
            );
            CREATE TABLE IF NOT EXISTS public.room_member_roles (
                room_id integer NOT NULL,
                user_id integer NOT NULL,
                role_id integer NOT NULL REFERENCES public.room_roles(id) ON DELETE CASCADE,
                granted_by integer,
                granted_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                PRIMARY KEY (room_id, user_id, role_id)
            );
            CREATE TABLE IF NOT EXISTS public.messages (
                id SERIAL PRIMARY KEY,
                room_id integer REFERENCES public.rooms(id) ON DELETE CASCADE,
                user_id integer REFERENCES public.users(id) ON DELETE CASCADE,
                content text NOT NULL,
                created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
                sender_id integer REFERENCES public.users(id) ON DELETE CASCADE,
                sent_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
            );
            CREATE TABLE IF NOT EXISTS public.message_reactions (
                message_id integer NOT NULL,
                user_id integer NOT NULL,
                reaction character varying(32) NOT NULL,
                created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                PRIMARY KEY (message_id, user_id, reaction)
            );
            CREATE TABLE IF NOT EXISTS public.friendships (
                id SERIAL PRIMARY KEY,
                requester_id integer NOT NULL,
                addressee_id integer NOT NULL,
                status character varying(20) DEFAULT 'PENDING' NOT NULL,
                created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
                updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT no_self_friend CHECK (requester_id <> addressee_id),
                CONSTRAINT unique_friendship UNIQUE (requester_id, addressee_id)
            );
            CREATE TABLE IF NOT EXISTS public.direct_conversations (
                id BIGSERIAL PRIMARY KEY,
                user_low_id integer NOT NULL,
                user_high_id integer NOT NULL,
                created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                CONSTRAINT direct_conversations_check CHECK (user_low_id < user_high_id),
                UNIQUE (user_low_id, user_high_id)
            );
            CREATE TABLE IF NOT EXISTS public.direct_messages (
                id BIGSERIAL PRIMARY KEY,
                conversation_id bigint NOT NULL REFERENCES public.direct_conversations(id) ON DELETE CASCADE,
                sender_id integer NOT NULL,
                content text NOT NULL,
                sent_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                edited_at timestamp without time zone,
                deleted_at timestamp without time zone,
                reply_to_message_id bigint REFERENCES public.direct_messages(id) ON DELETE SET NULL
            );
            CREATE TABLE IF NOT EXISTS public.direct_message_reactions (
                direct_message_id bigint NOT NULL REFERENCES public.direct_messages(id) ON DELETE CASCADE,
                user_id integer NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
                reaction character varying(32) NOT NULL,
                created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                PRIMARY KEY (direct_message_id, user_id, reaction),
                CONSTRAINT direct_message_reactions_nonempty CHECK (length(TRIM(BOTH FROM reaction)) > 0)
            );
            CREATE TABLE IF NOT EXISTS public.attachments (
                id BIGSERIAL PRIMARY KEY,
                uploader_id integer NOT NULL,
                room_id integer,
                direct_conversation_id bigint REFERENCES public.direct_conversations(id) ON DELETE CASCADE,
                message_id integer,
                original_name character varying(255) NOT NULL,
                stored_name character varying(255) NOT NULL UNIQUE,
                content_type character varying(150) DEFAULT 'application/octet-stream' NOT NULL,
                size_bytes bigint NOT NULL,
                sha256 character(64) NOT NULL,
                created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
            );
            CREATE TABLE IF NOT EXISTS public.user_room_reads (
                user_id integer NOT NULL,
                room_id integer NOT NULL,
                last_read_message_id integer,
                last_read_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                PRIMARY KEY (user_id, room_id)
            );
            CREATE TABLE IF NOT EXISTS public.room_audit_log (
                id BIGSERIAL PRIMARY KEY,
                room_id integer,
                actor_user_id integer,
                action character varying(80) NOT NULL,
                target_type character varying(40),
                target_id character varying(100),
                details jsonb DEFAULT '{}'::jsonb NOT NULL,
                created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
            );
            """);
    }

    /**
     * Tự động chạy file migration V003__guilds_channels.sql một cách idempotent nếu DB kết nối được.
     */
    public static synchronized void runMigrationsIfAvailable() {
        if (migrationsAttempted) {
            return;
        }
        migrationsAttempted = true;

        String[] migrationCandidates = {
            "sql/V003__guilds_channels.sql",
            "Chatserver/sql/V003__guilds_channels.sql",
            "../Chatserver/sql/V003__guilds_channels.sql",
            "Uri/DA_LapTrinhMangMayTinh/Chatserver/sql/V003__guilds_channels.sql"
        };

        File sqlFile = null;
        for (String candidate : migrationCandidates) {
            File f = new File(candidate);
            if (f.exists() && f.isFile()) {
                sqlFile = f;
                break;
            }
        }

        if (sqlFile == null) {
            System.out.println("[DB MIGRATION] Không tìm thấy file V003__guilds_channels.sql để tự động migrate.");
            return;
        }

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            ensureBaseSchema(stmt);
            String sqlContent = Files.readString(sqlFile.toPath(), StandardCharsets.UTF_8);
            stmt.execute(sqlContent);
            File v004File = findMigrationFile("V004__direct_conversation_reads.sql");
            if (v004File != null) {
                stmt.execute(Files.readString(v004File.toPath(), StandardCharsets.UTF_8));
                System.out.println("[DB MIGRATION] Applied idempotent migration V004: " + v004File.getPath());
            }
            System.out.println("[DB MIGRATION] Đã thực thi idempotent migration (Base Schema + V003/V004) thành công: " + sqlFile.getPath());
        } catch (Exception e) {
            System.err.println("[DB MIGRATION WARNING] Không thể chạy tự động V003__guilds_channels.sql: " + e.getMessage());
            throw new RuntimeException("Migration failed: " + e.getMessage(), e);
        }
    }

    private static File findMigrationFile(String fileName) {
        String[] candidates = {
            "sql/" + fileName,
            "Chatserver/sql/" + fileName,
            "../Chatserver/sql/" + fileName,
            "Uri/DA_LapTrinhMangMayTinh/Chatserver/sql/" + fileName
        };
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.exists() && file.isFile()) return file;
        }
        return null;
    }

    public static String getUrl() { return url; }
    public static String getUser() { return user; }

    public static void main(String[] args) {
        System.out.println("[MIGRATE] Testing DB connectivity: " + getUrl());
        if (!isConnected()) {
            System.err.println("[MIGRATE ERROR] Cannot connect to PostgreSQL database. Check .env / db.properties.");
            System.exit(1);
        }
        migrationsAttempted = false;
        runMigrationsIfAvailable();
        System.out.println("[MIGRATE] Done.");
    }
}
