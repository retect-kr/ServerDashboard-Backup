package com.serverdashboard.backup;

import com.google.gson.*;
import com.serverdashboard.DashboardPlugin;
import com.serverdashboard.api.DashboardModule;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public class BackupModule implements DashboardModule {

    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private DashboardPlugin plugin;
    private Path backupsDir;
    private Path serverRoot;
    private int autoTaskId = -1;

    private volatile boolean running = false;
    private volatile String statusMsg = "Idle";

    private final Set<String> cfgTargets = new LinkedHashSet<>(List.of("worlds"));
    private boolean cfgAutoEnabled = false;
    private int cfgIntervalHours = 24;
    private int cfgMaxBackups = 5;

    @Override public String getId()   { return "backup"; }
    @Override public String getName() { return "Backup"; }
    @Override public String getIcon() { return "ti-database"; }

    @Override
    public void onLoad(DashboardPlugin plugin) {
        this.plugin = plugin;
        this.serverRoot = Paths.get("").toAbsolutePath();
        this.backupsDir = plugin.getDataFolder().toPath().resolve("backups");
        try { Files.createDirectories(backupsDir); } catch (IOException ignored) {}
        loadConfig();
        scheduleAuto();
    }

    @Override
    public void onUnload() {
        cancelAuto();
    }

    @Override
    public void handleRoute(String path, String method, HttpExchange ex) throws Exception {
        if      (path.equals("/run")           && method.equals("POST"))   handleRun(ex);
        else if (path.equals("/status")        && method.equals("GET"))    handleStatus(ex);
        else if (path.equals("/list")          && method.equals("GET"))    handleList(ex);
        else if (path.equals("/config")        && method.equals("GET"))    handleGetConfig(ex);
        else if (path.equals("/config")        && method.equals("POST"))   handleSetConfig(ex);
        else if (path.startsWith("/download/") && method.equals("GET"))    handleDownload(ex, path.substring("/download/".length()));
        else if (path.length() > 1            && method.equals("DELETE"))  handleDelete(ex, path.substring(1));
        else sendJson(ex, 404, "{\"error\":\"Not Found\"}");
    }

    // ── handlers ──────────────────────────────────────────────────────────────

    private void handleRun(HttpExchange ex) throws Exception {
        if (running) { sendJson(ex, 409, "{\"error\":\"Backup already in progress\"}"); return; }
        JsonObject body = parseBody(ex);
        List<String> targets = new ArrayList<>();
        if (body.has("targets")) {
            for (JsonElement e : body.getAsJsonArray("targets")) targets.add(e.getAsString());
        } else {
            targets.addAll(cfgTargets);
        }
        if (targets.isEmpty()) { sendJson(ex, 400, "{\"error\":\"No targets selected\"}"); return; }

        String filename = "backup-" + LocalDateTime.now().format(TS) + ".zip";
        Path zipPath = backupsDir.resolve(filename);
        running = true;
        statusMsg = "Saving worlds...";

        // Save worlds on main thread first
        CompletableFuture<Void> saveFuture = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { Bukkit.getWorlds().forEach(org.bukkit.World::save); saveFuture.complete(null); }
            catch (Exception e) { saveFuture.completeExceptionally(e); }
        });
        try { saveFuture.get(30, TimeUnit.SECONDS); }
        catch (Exception ignored) {}

        new Thread(() -> {
            try {
                statusMsg = "Creating ZIP...";
                createZip(targets, zipPath);
                if (cfgMaxBackups > 0) pruneOld();
                statusMsg = "Done: " + filename;
            } catch (Exception e) {
                statusMsg = "Error: " + e.getMessage();
                try { Files.deleteIfExists(zipPath); } catch (IOException ignored) {}
            } finally { running = false; }
        }, "SD-Backup").start();

        JsonObject resp = new JsonObject();
        resp.addProperty("file", filename);
        sendJson(ex, 202, GSON.toJson(resp));
    }

    private void handleStatus(HttpExchange ex) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("running", running);
        o.addProperty("status", statusMsg);
        sendJson(ex, 200, GSON.toJson(o));
    }

    private void handleList(HttpExchange ex) throws Exception {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupsDir, "*.zip")) {
            ds.forEach(files::add);
        } catch (IOException ignored) {}
        files.sort(Comparator.comparing(Path::getFileName).reversed());
        JsonArray arr = new JsonArray();
        for (Path p : files) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getFileName().toString());
            try { o.addProperty("size", Files.size(p)); } catch (IOException e) { o.addProperty("size", 0); }
            try { o.addProperty("modified", Files.getLastModifiedTime(p).toInstant().toString()); }
            catch (IOException e) { o.addProperty("modified", ""); }
            arr.add(o);
        }
        sendJson(ex, 200, GSON.toJson(arr));
    }

    private void handleDownload(HttpExchange ex, String name) throws Exception {
        if (name.contains("..") || name.contains("/") || name.contains("\\") || !name.endsWith(".zip")) {
            sendJson(ex, 400, "{\"error\":\"Invalid filename\"}"); return;
        }
        Path file = backupsDir.resolve(name);
        if (!Files.exists(file)) { sendJson(ex, 404, "{\"error\":\"Not found\"}"); return; }
        long size = Files.size(file);
        ex.getResponseHeaders().add("Content-Type", "application/zip");
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + name + "\"");
        ex.getResponseHeaders().add("Connection", "close");
        ex.sendResponseHeaders(200, size);
        try (OutputStream os = ex.getResponseBody(); InputStream is = Files.newInputStream(file)) {
            is.transferTo(os);
        }
        ex.close();
    }

    private void handleDelete(HttpExchange ex, String name) throws Exception {
        if (name.contains("..") || name.contains("/") || name.contains("\\") || !name.endsWith(".zip")) {
            sendJson(ex, 400, "{\"error\":\"Invalid filename\"}"); return;
        }
        Path file = backupsDir.resolve(name);
        if (Files.deleteIfExists(file)) sendJson(ex, 200, "{\"message\":\"Deleted\"}");
        else sendJson(ex, 404, "{\"error\":\"Not found\"}");
    }

    private void handleGetConfig(HttpExchange ex) throws Exception {
        sendJson(ex, 200, GSON.toJson(buildConfigJson()));
    }

    private void handleSetConfig(HttpExchange ex) throws Exception {
        JsonObject body = parseBody(ex);
        if (body.has("targets")) {
            cfgTargets.clear();
            for (JsonElement e : body.getAsJsonArray("targets")) cfgTargets.add(e.getAsString());
        }
        if (body.has("autoEnabled"))   cfgAutoEnabled   = body.get("autoEnabled").getAsBoolean();
        if (body.has("intervalHours")) cfgIntervalHours = Math.max(1, body.get("intervalHours").getAsInt());
        if (body.has("maxBackups"))    cfgMaxBackups    = Math.max(0, body.get("maxBackups").getAsInt());
        saveConfig();
        cancelAuto();
        scheduleAuto();
        sendJson(ex, 200, GSON.toJson(buildConfigJson()));
    }

    // ── ZIP ───────────────────────────────────────────────────────────────────

    private void createZip(List<String> targets, Path zipPath) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipPath)))) {
            zos.setLevel(1);
            for (String t : targets) {
                statusMsg = "Backing up: " + t + "...";
                switch (t) {
                    case "worlds" -> {
                        for (org.bukkit.World w : Bukkit.getWorlds()) {
                            Path worldDir = serverRoot.resolve(w.getName());
                            if (Files.isDirectory(worldDir)) addDir(zos, worldDir);
                        }
                    }
                    case "plugin-configs" -> {
                        Path pluginsDir = serverRoot.resolve("plugins");
                        if (Files.isDirectory(pluginsDir)) {
                            try (DirectoryStream<Path> ds = Files.newDirectoryStream(pluginsDir)) {
                                for (Path entry : ds) {
                                    if (Files.isDirectory(entry)) addDir(zos, entry);
                                }
                            }
                        }
                    }
                    case "plugin-jars" -> {
                        Path pluginsDir = serverRoot.resolve("plugins");
                        if (Files.isDirectory(pluginsDir)) {
                            try (DirectoryStream<Path> ds = Files.newDirectoryStream(pluginsDir, "*.jar")) {
                                for (Path jar : ds) addFile(zos, jar);
                            }
                        }
                    }
                    case "root-configs" -> {
                        for (String name : List.of("server.properties", "bukkit.yml", "spigot.yml",
                                "paper.yml", "paper-global.yml", "paper-world-defaults.yml")) {
                            Path p = serverRoot.resolve(name);
                            if (Files.isRegularFile(p)) addFile(zos, p);
                        }
                        Path cfgDir = serverRoot.resolve("config");
                        if (Files.isDirectory(cfgDir)) addDir(zos, cfgDir);
                    }
                }
            }
        }
    }

    private void addDir(ZipOutputStream zos, Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> addFile(zos, p));
        } catch (IOException ignored) {}
    }

    private void addFile(ZipOutputStream zos, Path file) {
        try {
            String entry = serverRoot.relativize(file).toString().replace('\\', '/');
            zos.putNextEntry(new ZipEntry(entry));
            Files.copy(file, zos);
            zos.closeEntry();
        } catch (IOException ignored) {}
    }

    private void pruneOld() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupsDir, "*.zip")) {
            ds.forEach(files::add);
        } catch (IOException ignored) { return; }
        files.sort(Comparator.comparing(Path::getFileName).reversed());
        for (int i = cfgMaxBackups; i < files.size(); i++) {
            try { Files.deleteIfExists(files.get(i)); } catch (IOException ignored) {}
        }
    }

    // ── Auto backup ───────────────────────────────────────────────────────────

    private void scheduleAuto() {
        if (!cfgAutoEnabled) return;
        long ticks = cfgIntervalHours * 20L * 60 * 60;
        autoTaskId = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, this::runAutoBackup, ticks, ticks)
                .getTaskId();
    }

    private void cancelAuto() {
        if (autoTaskId != -1) { Bukkit.getScheduler().cancelTask(autoTaskId); autoTaskId = -1; }
    }

    private void runAutoBackup() {
        if (running) return;
        running = true;
        String filename = "auto-" + LocalDateTime.now().format(TS) + ".zip";
        Path zipPath = backupsDir.resolve(filename);
        CompletableFuture<Void> f = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.getWorlds().forEach(org.bukkit.World::save);
            f.complete(null);
        });
        try {
            f.get(30, TimeUnit.SECONDS);
            createZip(new ArrayList<>(cfgTargets), zipPath);
            if (cfgMaxBackups > 0) pruneOld();
            statusMsg = "Done: " + filename;
            plugin.getLogger().info("[Backup] Auto backup complete: " + filename);
        } catch (Exception e) {
            statusMsg = "Error: " + e.getMessage();
            plugin.getLogger().warning("[Backup] Auto backup failed: " + e.getMessage());
            try { Files.deleteIfExists(zipPath); } catch (IOException ignored) {}
        } finally { running = false; }
    }

    // ── Config ────────────────────────────────────────────────────────────────

    private Path configPath() { return plugin.getDataFolder().toPath().resolve("backup-config.json"); }

    private void loadConfig() {
        Path p = configPath();
        if (!Files.exists(p)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (o.has("targets")) {
                cfgTargets.clear();
                o.getAsJsonArray("targets").forEach(e -> cfgTargets.add(e.getAsString()));
            }
            if (o.has("autoEnabled"))   cfgAutoEnabled   = o.get("autoEnabled").getAsBoolean();
            if (o.has("intervalHours")) cfgIntervalHours = o.get("intervalHours").getAsInt();
            if (o.has("maxBackups"))    cfgMaxBackups    = o.get("maxBackups").getAsInt();
        } catch (Exception e) {
            plugin.getLogger().warning("[Backup] Config load failed: " + e.getMessage());
        }
    }

    private void saveConfig() {
        try { Files.writeString(configPath(), GSON.toJson(buildConfigJson())); }
        catch (IOException e) { plugin.getLogger().warning("[Backup] Config save failed: " + e.getMessage()); }
    }

    private JsonObject buildConfigJson() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        cfgTargets.forEach(arr::add);
        o.add("targets", arr);
        o.addProperty("autoEnabled", cfgAutoEnabled);
        o.addProperty("intervalHours", cfgIntervalHours);
        o.addProperty("maxBackups", cfgMaxBackups);
        return o;
    }

    // ── HTTP utils ────────────────────────────────────────────────────────────

    private void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().add("Connection", "close");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        ex.close();
    }

    private JsonObject parseBody(HttpExchange ex) throws IOException {
        byte[] buf = ex.getRequestBody().readNBytes(64 * 1024);
        String s = new String(buf, StandardCharsets.UTF_8);
        return s.isBlank() ? new JsonObject() : JsonParser.parseString(s).getAsJsonObject();
    }

    // ── HTML / JS ─────────────────────────────────────────────────────────────

    @Override public String getSectionHtml() { return HTML; }
    @Override public String getInitScript()  { return JS;   }

    private static final String HTML = """
        <style>
        /* ── Backup module components ──────────────────────── */
        .bk-chk,.bk-radio{
          display:flex;align-items:center;gap:10px;cursor:pointer;
          padding:8px 10px;border-radius:7px;font-size:13px;
          border:1.5px solid transparent;transition:all .13s;user-select:none;
        }
        .bk-chk:hover,.bk-radio:hover{background:var(--surface-2);border-color:var(--border);}
        .bk-chk:has(input:checked),.bk-radio:has(input:checked){
          background:var(--accent-dim);border-color:rgba(99,102,241,.3);
        }
        .bk-chk input,.bk-radio input{display:none;}

        /* custom checkbox box */
        .bk-box{
          width:17px;height:17px;border-radius:4px;flex-shrink:0;
          border:1.5px solid var(--border-2);background:var(--surface-3);
          display:flex;align-items:center;justify-content:center;
          transition:all .13s;
        }
        .bk-chk:hover .bk-box{border-color:var(--accent);}
        .bk-chk:has(input:checked) .bk-box{background:var(--accent);border-color:var(--accent);}
        .bk-chk:has(input:checked) .bk-box::after{
          content:'✓';color:#fff;font-size:10px;font-weight:800;line-height:1;
        }

        /* custom radio dot */
        .bk-dot{
          width:17px;height:17px;border-radius:50%;flex-shrink:0;
          border:1.5px solid var(--border-2);background:var(--surface-3);
          position:relative;transition:all .13s;
        }
        .bk-radio:hover .bk-dot{border-color:var(--accent);}
        .bk-radio:has(input:checked) .bk-dot{border-color:var(--accent);}
        .bk-radio:has(input:checked) .bk-dot::after{
          content:'';width:8px;height:8px;border-radius:50%;
          background:var(--accent);position:absolute;
          top:50%;left:50%;transform:translate(-50%,-50%);
        }

        /* option icon badge */
        .bk-ico{
          width:32px;height:32px;border-radius:7px;flex-shrink:0;
          background:var(--surface-3);
          display:flex;align-items:center;justify-content:center;
          transition:all .13s;
        }
        .bk-ico i{font-size:16px;color:var(--text-2);transition:color .13s;}
        .bk-chk:has(input:checked) .bk-ico,
        .bk-radio:has(input:checked) .bk-ico{background:rgba(99,102,241,.18);}
        .bk-chk:has(input:checked) .bk-ico i,
        .bk-radio:has(input:checked) .bk-ico i{color:var(--accent-2);}

        /* number input */
        .bk-num{
          background:var(--surface-2);border:1px solid var(--border-2);
          border-radius:6px;padding:6px 9px;color:var(--text);
          font-size:13px;font-family:var(--font);
          transition:border-color .12s;text-align:center;
        }
        .bk-num:focus{outline:none;border-color:var(--accent);}

        /* section label */
        .bk-lbl{
          font-size:10.5px;font-weight:600;text-transform:uppercase;
          letter-spacing:.6px;color:var(--text-3);padding:0 2px;margin-bottom:5px;
        }

        /* status bar */
        .bk-status{
          display:none;margin-top:10px;padding:9px 12px;
          border-radius:6px;border-left:3px solid var(--accent);
          background:var(--surface-3);font-size:12px;
          font-family:var(--mono);color:var(--text-2);
        }
        .bk-status.done{border-left-color:var(--green);}
        .bk-status.err{border-left-color:var(--red);}
        </style>

        <div style="display:grid;grid-template-columns:340px 1fr;gap:18px;align-items:start">

          <!-- Left column -->
          <div style="display:flex;flex-direction:column;gap:14px">

            <!-- Create Backup -->
            <div class="card" style="padding:16px">
              <div style="font-size:13.5px;font-weight:600;margin-bottom:14px;display:flex;align-items:center;gap:7px">
                <i class="ti ti-database-export" style="font-size:16px;color:var(--accent-2)"></i> 백업 생성
              </div>

              <div class="bk-lbl">대상</div>
              <div id="bk-targets" style="display:flex;flex-direction:column;gap:3px;margin-bottom:14px">
                <label class="bk-chk">
                  <input type="checkbox" value="worlds">
                  <span class="bk-box"></span>
                  <div class="bk-ico"><i class="ti ti-world"></i></div>
                  <div>
                    <div style="font-size:13px;font-weight:500">Worlds</div>
                    <div style="font-size:11.5px;color:var(--text-2);margin-top:1px">월드 데이터 파일</div>
                  </div>
                </label>
                <label class="bk-chk">
                  <input type="checkbox" value="plugin-configs">
                  <span class="bk-box"></span>
                  <div class="bk-ico"><i class="ti ti-settings"></i></div>
                  <div>
                    <div style="font-size:13px;font-weight:500">Plugin Configs</div>
                    <div style="font-size:11.5px;color:var(--text-2);margin-top:1px">플러그인 설정 폴더</div>
                  </div>
                </label>
                <label class="bk-chk">
                  <input type="checkbox" value="plugin-jars">
                  <span class="bk-box"></span>
                  <div class="bk-ico"><i class="ti ti-package"></i></div>
                  <div>
                    <div style="font-size:13px;font-weight:500">Plugin JARs</div>
                    <div style="font-size:11.5px;color:var(--text-2);margin-top:1px">플러그인 JAR 파일</div>
                  </div>
                </label>
                <label class="bk-chk">
                  <input type="checkbox" value="root-configs">
                  <span class="bk-box"></span>
                  <div class="bk-ico"><i class="ti ti-file-description"></i></div>
                  <div>
                    <div style="font-size:13px;font-weight:500">Root Configs</div>
                    <div style="font-size:11.5px;color:var(--text-2);margin-top:1px">server.properties, bukkit.yml 등</div>
                  </div>
                </label>
              </div>

              <div class="bk-lbl">저장 방식</div>
              <div style="display:flex;flex-direction:column;gap:3px;margin-bottom:16px">
                <label class="bk-radio">
                  <input type="radio" name="bk-storage" value="local" checked>
                  <span class="bk-dot"></span>
                  <div class="bk-ico"><i class="ti ti-server-2"></i></div>
                  <div>
                    <div style="font-size:13px;font-weight:500">서버에 저장</div>
                    <div style="font-size:11.5px;color:var(--text-2);margin-top:1px">backups/ 폴더에 ZIP 저장</div>
                  </div>
                </label>
                <label class="bk-radio">
                  <input type="radio" name="bk-storage" value="download">
                  <span class="bk-dot"></span>
                  <div class="bk-ico"><i class="ti ti-download"></i></div>
                  <div>
                    <div style="font-size:13px;font-weight:500">브라우저 다운로드</div>
                    <div style="font-size:11.5px;color:var(--text-2);margin-top:1px">완료 후 자동 ZIP 다운로드</div>
                  </div>
                </label>
              </div>

              <button id="bk-run" class="btn btn-primary" style="width:100%;justify-content:center;padding:8px">
                <i class="ti ti-database-export"></i> Backup Now
              </button>
              <div id="bk-status-box" class="bk-status">
                <span id="bk-status-txt">—</span>
              </div>
            </div>

            <!-- Auto Backup -->
            <div class="card" style="padding:16px">
              <div style="font-size:13.5px;font-weight:600;margin-bottom:14px;display:flex;align-items:center;gap:7px">
                <i class="ti ti-clock-play" style="font-size:16px;color:var(--accent-2)"></i> 자동 백업
              </div>
              <div style="display:flex;flex-direction:column;gap:12px">
                <div class="toggle-row">
                  <label class="toggle">
                    <input type="checkbox" id="bk-auto-en">
                    <span class="tgl-track"></span>
                    <span class="tgl-thumb"></span>
                  </label>
                  <span style="font-size:13px;font-weight:500">자동 백업 활성화</span>
                </div>
                <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
                  <div>
                    <div class="bk-lbl" style="margin-bottom:6px">주기</div>
                    <div style="display:flex;align-items:center;gap:7px">
                      <input type="number" id="bk-auto-hr" class="bk-num" min="1" max="168" value="24" style="flex:1">
                      <span style="font-size:12px;color:var(--text-2)">시간</span>
                    </div>
                  </div>
                  <div>
                    <div class="bk-lbl" style="margin-bottom:6px">최대 보관</div>
                    <div style="display:flex;align-items:center;gap:7px">
                      <input type="number" id="bk-auto-max" class="bk-num" min="1" max="50" value="5" style="flex:1">
                      <span style="font-size:12px;color:var(--text-2)">개</span>
                    </div>
                  </div>
                </div>
                <button id="bk-cfg-save" class="btn btn-ghost" style="width:100%;justify-content:center">
                  <i class="ti ti-device-floppy"></i> 설정 저장
                </button>
              </div>
            </div>
          </div>

          <!-- Right column: list -->
          <div class="card" style="padding:16px">
            <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:14px">
              <div style="font-size:13.5px;font-weight:600;display:flex;align-items:center;gap:7px">
                <i class="ti ti-history" style="font-size:16px;color:var(--accent-2)"></i> 저장된 백업
              </div>
              <button id="bk-refresh" class="btn btn-ghost btn-sm"><i class="ti ti-refresh"></i> 새로고침</button>
            </div>
            <div id="bk-list" style="display:flex;flex-direction:column;gap:6px">
              <span style="color:var(--text-2);font-size:13px">불러오는 중...</span>
            </div>
          </div>
        </div>
        """;

    private static final String JS = """
        (function(){
          const BASE = '/api/module/backup';
          const tok = () => localStorage.getItem('sd_token') || '';

          async function bkFetch(method, path, body) {
            const opts = { method, headers: { 'Authorization': 'Bearer ' + tok() } };
            if (body) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
            const r = await fetch(BASE + path, opts);
            return r.json().catch(() => ({}));
          }

          function fmtSize(b) {
            if (b < 1024) return b + ' B';
            if (b < 1048576) return (b / 1024).toFixed(1) + ' KB';
            return (b / 1048576).toFixed(1) + ' MB';
          }

          function fmtDate(iso) {
            if (!iso) return '';
            const d = new Date(iso);
            return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
          }

          function setStatus(text, state) {
            const box = document.getElementById('bk-status-box');
            box.style.display = 'block';
            box.className = 'bk-status' + (state ? ' ' + state : '');
            document.getElementById('bk-status-txt').textContent = text;
          }

          async function loadConfig() {
            const cfg = await bkFetch('GET', '/config');
            document.getElementById('bk-auto-en').checked = !!cfg.autoEnabled;
            document.getElementById('bk-auto-hr').value   = cfg.intervalHours || 24;
            document.getElementById('bk-auto-max').value  = cfg.maxBackups ?? 5;
            document.querySelectorAll('#bk-targets input').forEach(cb => {
              cb.checked = (cfg.targets || ['worlds']).includes(cb.value);
            });
          }

          async function loadList() {
            const list = await bkFetch('GET', '/list');
            const el = document.getElementById('bk-list');
            if (!Array.isArray(list) || !list.length) {
              el.innerHTML = '<div class="empty"><i class="ti ti-database-off"></i><p>저장된 백업 없음</p></div>';
              return;
            }
            el.innerHTML = list.map(f => `
              <div style="display:flex;align-items:center;gap:10px;padding:10px 12px;
                background:var(--surface-2);border-radius:8px;border:1px solid var(--border)">
                <div style="width:34px;height:34px;border-radius:7px;background:var(--accent-dim);
                  display:flex;align-items:center;justify-content:center;flex-shrink:0">
                  <i class="ti ti-file-zip" style="font-size:17px;color:var(--accent-2)"></i>
                </div>
                <div style="flex:1;min-width:0">
                  <div style="font-size:12.5px;font-weight:500;font-family:var(--mono);
                    white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${f.name}</div>
                  <div style="font-size:11px;color:var(--text-2);margin-top:2px">
                    ${fmtSize(f.size)}&nbsp;·&nbsp;${fmtDate(f.modified)}
                  </div>
                </div>
                <div class="btn-grp">
                  <button onclick="bkDl('${f.name}')" class="btn btn-ghost btn-sm" title="다운로드">
                    <i class="ti ti-download"></i>
                  </button>
                  <button onclick="bkDel('${f.name}')" class="btn btn-danger btn-sm" title="삭제">
                    <i class="ti ti-trash"></i>
                  </button>
                </div>
              </div>
            `).join('');
          }

          window.bkDl = async function(name) {
            const r = await fetch(BASE + '/download/' + name, { headers: { 'Authorization': 'Bearer ' + tok() } });
            if (!r.ok) { toast('다운로드 실패', 'error'); return; }
            const blob = await r.blob();
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a'); a.href = url; a.download = name; a.click();
            URL.revokeObjectURL(url);
          };

          window.bkDel = async function(name) {
            if (!confirm(name + ' 을(를) 삭제할까요?')) return;
            await bkFetch('DELETE', '/' + name);
            toast('삭제됨', 'success');
            loadList();
          };

          let pollId = null;
          function startPoll(filename, doDownload) {
            if (pollId) clearInterval(pollId);
            pollId = setInterval(async () => {
              const s = await bkFetch('GET', '/status');
              const isDone = !s.running;
              const isErr  = s.status && s.status.startsWith('Error');
              setStatus(s.status || '', isDone ? (isErr ? 'err' : 'done') : '');
              if (isDone) {
                clearInterval(pollId); pollId = null;
                loadList();
                if (doDownload && !isErr) bkDl(filename);
              }
            }, 1000);
          }

          document.getElementById('bk-run').addEventListener('click', async () => {
            const targets = [...document.querySelectorAll('#bk-targets input:checked')].map(c => c.value);
            if (!targets.length) { toast('대상을 선택해주세요', 'error'); return; }
            const storage = document.querySelector('input[name=bk-storage]:checked').value;
            const resp = await bkFetch('POST', '/run', { targets, storage });
            if (resp.error) { toast(resp.error, 'error'); return; }
            toast('백업 시작됨', 'success');
            setStatus('시작 중...', '');
            startPoll(resp.file, storage === 'download');
          });

          document.getElementById('bk-cfg-save').addEventListener('click', async () => {
            const targets = [...document.querySelectorAll('#bk-targets input:checked')].map(c => c.value);
            const body = {
              targets,
              autoEnabled:   document.getElementById('bk-auto-en').checked,
              intervalHours: parseInt(document.getElementById('bk-auto-hr').value) || 24,
              maxBackups:    parseInt(document.getElementById('bk-auto-max').value) || 5
            };
            await bkFetch('POST', '/config', body);
            toast('설정 저장됨', 'success');
          });

          document.getElementById('bk-refresh').addEventListener('click', loadList);

          loadConfig();
          loadList();
        })();
        """;
}
