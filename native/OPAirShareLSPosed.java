import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Root installer. Targeted transactions; LSPosed reloads the configuration on reboot. */
public final class OPAirShareLSPosed {
    private static final String PKG = "com.tlsu.oshareairdrop";
    private static final String DB = "/data/adb/lspd/config/modules_config.db";

    private static String quote(String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("invalid APK path");
        return "'" + value.replace("'", "''") + "'";
    }

    // Shared with the host regression tests; SQL never deletes existing scopes/configs.
    static List<String> statements(String layout, String apk) {
        if (!apk.startsWith("/data/app/") || !apk.endsWith("/base.apk"))
            throw new IllegalArgumentException("unexpected APK path");
        String pkg = quote(PKG);
        List<String> sql = new ArrayList<>();
        sql.add("INSERT OR IGNORE INTO modules (module_pkg_name,apk_path) VALUES (" + pkg + "," + quote(apk) + ")");
        sql.add("UPDATE modules SET apk_path=" + quote(apk) + " WHERE module_pkg_name=" + pkg);
        if (layout.equals("modern")) {
            sql.add("INSERT OR IGNORE INTO modules_state (module_pkg_name,user_id,enabled) VALUES (" + pkg + ",0,1)");
            sql.add("UPDATE modules_state SET enabled=1 WHERE module_pkg_name=" + pkg + " AND user_id=0");
        } else if (layout.equals("legacy")) {
            sql.add("UPDATE modules SET enabled=1 WHERE module_pkg_name=" + pkg);
        } else throw new IllegalArgumentException("unknown LSPosed schema");
        for (String target : new String[]{"com.coloros.oshare", "com.google.android.mosey"}) {
            if (layout.equals("modern")) {
                sql.add("INSERT OR IGNORE INTO scope (module_pkg_name,app_pkg_name,user_id) VALUES (" + pkg + "," + quote(target) + ",0)");
            } else {
                sql.add("INSERT OR IGNORE INTO scope (mid,app_pkg_name,user_id) SELECT mid," + quote(target) + ",0 FROM modules WHERE module_pkg_name=" + pkg);
            }
        }
        return sql;
    }

    private static Set<String> columns(SQLiteDatabase db, String table) {
        Set<String> result = new HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) result.add(c.getString(c.getColumnIndexOrThrow("name")));
        }
        return result;
    }

    private static void require(Set<String> columns, String... names) {
        for (String name : names)
            if (!columns.contains(name)) throw new IllegalStateException("unsupported LSPosed schema: " + name);
    }

    private static long scalar(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            if (!c.moveToFirst()) throw new IllegalStateException("missing query result");
            return c.getLong(0);
        }
    }

    public static void main(String[] args) throws Exception {
        // Allows testing the exact activation SQL with real SQLite on the host.
        if (args.length == 3 && args[0].equals("--sql")) {
            for (String sql : statements(args[1], args[2])) System.out.println(sql + ";");
            return;
        }
        boolean inspect = args.length == 3 && args[0].equals("--check");
        if (inspect) args = new String[]{args[1], args[2]};
        if (android.os.Process.myUid() != 0 || args.length != 2 || !args[0].equals(DB))
            throw new IllegalArgumentException("root installer and LSPosed config path required");
        Path path = Path.of(args[0]);
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path) || !Files.isRegularFile(Path.of(args[1])))
            throw new IllegalArgumentException("missing database or installed APK");
        // Oplus SQLiteGlobal.getDefaultSyncMode() assumes a framework-initialized
        // resource array that standalone app_process does not have. Explicit
        // FULL synchronization bypasses that default without relaxing durability.
        SQLiteDatabase.OpenParams params = new SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(inspect ? SQLiteDatabase.OPEN_READONLY : SQLiteDatabase.OPEN_READWRITE)
            .setSynchronousMode("FULL").build();
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(path.toFile(), params)) {
            Set<String> modules = columns(db, "modules");
            require(modules, "module_pkg_name", "apk_path");
            String layout = modules.contains("mid") && modules.contains("enabled") ? "legacy" : "modern";
            require(columns(db, "scope"), layout.equals("modern") ? "module_pkg_name" : "mid", "app_pkg_name", "user_id");
            if (layout.equals("modern")) require(columns(db, "modules_state"), "module_pkg_name", "user_id", "enabled");
            if (inspect) {
                String enabledQuery = layout.equals("modern")
                    ? "SELECT COUNT(*) FROM modules_state WHERE module_pkg_name='" + PKG + "' AND user_id=0 AND enabled=1"
                    : "SELECT COUNT(*) FROM modules WHERE module_pkg_name='" + PKG + "' AND enabled=1";
                String owner = layout.equals("modern") ? "module_pkg_name='" + PKG + "'"
                    : "mid=(SELECT mid FROM modules WHERE module_pkg_name='" + PKG + "')";
                long scopes = scalar(db, "SELECT COUNT(*) FROM scope WHERE " + owner
                    + " AND user_id=0 AND app_pkg_name IN ('com.coloros.oshare','com.google.android.mosey')");
                long apkMatches = scalar(db, "SELECT COUNT(*) FROM modules WHERE module_pkg_name='" + PKG
                    + "' AND apk_path=" + quote(args[1]));
                System.out.println("LSPOSED_OSHARE_STATE " + layout + " enabled=" + scalar(db, enabledQuery)
                    + " scopes=" + scopes + " apkMatches=" + apkMatches);
                return;
            }
            // A consistent SQLite backup includes committed WAL records; cp would not.
            Path backup = Path.of("/data/adb/opairshare/lsposed-before-activation-" + System.currentTimeMillis() + ".db");
            db.execSQL("VACUUM INTO " + quote(backup.toString()));
            Files.setPosixFilePermissions(backup, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            db.beginTransaction();
            try {
                for (String sql : statements(layout, args[1])) db.execSQL(sql);
                String enabled = layout.equals("modern")
                    ? "SELECT enabled FROM modules_state WHERE module_pkg_name='" + PKG + "' AND user_id=0"
                    : "SELECT enabled FROM modules WHERE module_pkg_name='" + PKG + "'";
                String owner = layout.equals("modern") ? "module_pkg_name='" + PKG + "'"
                    : "mid=(SELECT mid FROM modules WHERE module_pkg_name='" + PKG + "')";
                if (scalar(db, enabled) != 1 || scalar(db, "SELECT COUNT(*) FROM scope WHERE " + owner
                        + " AND user_id=0 AND app_pkg_name IN ('com.coloros.oshare','com.google.android.mosey')") != 2)
                    throw new IllegalStateException("LSPosed activation verification failed");
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            System.out.println("LSPOSED_OSHARE_ENABLED " + layout + " backup=" + backup);
        }
    }
}
