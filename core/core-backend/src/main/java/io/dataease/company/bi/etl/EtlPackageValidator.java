package io.dataease.company.bi.etl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.zip.*;
import static io.dataease.company.bi.etl.EtlModel.*;

public final class EtlPackageValidator {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final List<String> NAMES = List.of("销售订单数据-线下", "全渠道-销售", "产品分渠道销售");
    public static final List<String> TABLES = List.of("销售订单数据_线下", "全渠道_销售", "产品分渠道销售");
    private EtlPackageValidator() {}
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException("SHA256 unavailable"); }
    }
    public static Map<String, String> unpack(byte[] bytes) throws IOException {
        require(bytes.length <= MAX_BYTES, "SQL 包超过 16 MB");
        Map<String, String> files = new LinkedHashMap<>();
        int total = 0, entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                require(++entries <= 32, "SQL 包文件过多");
                String path = entry.getName();
                require(path != null && path.length()<=255 && !path.startsWith("/") && !path.contains("\\") && !path.contains(":")
                        && Arrays.stream(path.split("/")).noneMatch(p -> p.equals("..") || p.equals(".")), "SQL 包路径无效");
                if (entry.isDirectory()) continue;
                require(!files.containsKey(path), "SQL 包包含重复路径");
                byte[] content = zip.readNBytes(MAX_BYTES - total + 1);
                total += content.length;
                require(total <= MAX_BYTES, "SQL 包解压内容超过 16 MB");
                String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)).toString();
                require(!text.startsWith("\uFEFF"), "SQL 文件请保存为 UTF-8 无 BOM");
                files.put(path, text);
            }
        }
        require(files.containsKey("manifest.json"), "SQL 包缺少 manifest.json");
        return Map.copyOf(files);
    }
    public static Manifest validate(Map<String, String> files) {
        Manifest m = read(files.get("manifest.json"), Manifest.class);
        require(m.schemaVersion() == 1 && m.windowDays() == 35, "清单版本或刷新窗口不支持");
        boolean test = "test".equals(m.packageKind());
        require(test || "formal".equals(m.packageKind()), "包类型无效");
        require((test ? "refresh_test" : "refresh").equals(m.runKind()), "包类型与 kind 不匹配");
        require(m.source() != null && "etl_migration".equals(m.source().repo()) && m.source().commit() != null
                && m.source().commit().matches("[0-9a-fA-F]{7,40}"), "来源提交无效");
        require(m.source().generatedAt() != null && m.source().generatedAt().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), "生成时间无效");
        require(m.tasks() != null && m.tasks().size() == 3, "清单必须包含三项刷新任务");
        Set<String> paths = new HashSet<>(Set.of("manifest.json"));
        Set<String> visited = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            Task task = m.tasks().get(i);
            require(NAMES.get(i).equals(task.etlName()) && visited.add(task.etlName()), "任务名称或拓扑顺序无效");
            require("qiwei_new_bi".equals(task.targetSchema()) && (TABLES.get(i) + (test ? "_dataease_test" : "")).equals(task.targetTable()), "目标表与包类型不匹配");
            require(task.dependsOn() != null && task.dependsOn().equals(i == 0 ? List.of() : List.of(NAMES.get(i - 1))), "任务依赖与原执行链不一致");
            require((i == 2 ? "支付时间(天)" : "orderDate").equals(task.windowField()), "窗口字段无效");
            require(task.acceptance() != null && "actualMoney".equals(task.acceptance().moneyField()), "验收金额字段无效");
            Acceptance acceptance = task.acceptance();
            LocalDate.parse(acceptance.historyStart());
            require("2026-01-01".equals(acceptance.historyStart()), "历史起点无效");
            Baseline baseline = acceptance.baseline();
            require(baseline != null && baseline.money() != null && baseline.tolerance() != null, "缺少验收基线");
            YearMonth.parse(baseline.month());
            require("2026-07".equals(baseline.month()) && baseline.rows() == new long[]{20826,132194,24272}[i]
                    && baseline.money().compareTo(new java.math.BigDecimal(i == 0 ? "1901999.6700" : "5973923.4500")) == 0
                    && baseline.tolerance().compareTo(new java.math.BigDecimal("0.01")) == 0, "验收基线与已确认口径不一致");
            String sql = file(files, paths, task.sql(), task.sqlSha256());
            for (String placeholder : List.of("__REQUIRED_WINDOW_START__", "__REQUIRED_WINDOW_END__", "__SAFETY_SMOKE_PASSED__"))
                require(sql.contains(placeholder), "刷新 SQL 缺少必要占位符");
            EtlSqlScript.validate(sql);
            if (test) validateTestSql(sql, false);
        }
        require(m.init() != null, "清单缺少 init 数组");
        require(test || m.init().isEmpty(), "正式包不允许初始化脚本");
        require(m.init().size() <= 1, "最多允许一个初始化脚本");
        for (Init init : m.init()) validateTestSql(file(files, paths, init.sql(), init.sqlSha256()), true);
        require(paths.equals(files.keySet()), "SQL 包含清单之外的文件");
        return m;
    }
    private static String file(Map<String, String> files, Set<String> paths, String path, String sha) {
        require(path != null && path.endsWith(".sql") && paths.add(path), "SQL 文件路径无效或重复");
        String content = files.get(path);
        require(content != null && sha != null && sha.equalsIgnoreCase(hash(content.getBytes(StandardCharsets.UTF_8))), "SQL 文件哈希不匹配");
        return content;
    }
    static void validateTestSql(String sql, boolean initialization) {
        // Initializers may SELECT/LIKE the formal tables, but their write targets must be test tables.
        if (!initialization) {
            for (String table : TABLES) {
                require(!sql.contains("`" + table + "`") && !sql.contains("`" + table + "_refresh_stage`"), "测试 SQL 引用了正式目标或暂存表");
            }
        }
        Set<String> targets = new HashSet<>();
        for (String table : TABLES) { targets.add(table + "_dataease_test"); targets.add(table + "_dataease_test_refresh_stage"); }
        String ident = "(?:`[^`]+`|[\\p{L}\\p{N}_]+)";
        var write = java.util.regex.Pattern.compile("(?is)^(?:INSERT\\s+INTO|REPLACE\\s+INTO|DELETE\\s+FROM|UPDATE|CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?|DROP\\s+TABLE(?:\\s+IF\\s+EXISTS)?|TRUNCATE(?:\\s+TABLE)?|ALTER\\s+TABLE)\\s+(" + ident + ")(?:\\s*\\.\\s*(" + ident + "))?(?=\\s|\\(|$)");
        for (String statement : EtlSqlScript.statements(sql)) {
            String upper = statement.toUpperCase(Locale.ROOT);
            if (upper.startsWith("SET ") || upper.startsWith("SELECT ") || upper.equals("COMMIT") || upper.equals("ROLLBACK") || upper.equals("START TRANSACTION")) continue;
            var match = write.matcher(statement);
            require(match.find(), "测试 SQL 含不支持的语句");
            String schema = match.group(2) == null ? "qiwei_new_bi" : match.group(1).replace("`", "");
            String table = (match.group(2) == null ? match.group(1) : match.group(2)).replace("`", "");
            require("qiwei_new_bi".equals(schema) && targets.contains(table), "测试 SQL 写入目标必须为测试表");
            require(!upper.matches("(?s)^(UPDATE|DELETE).*\\bJOIN\\b.*") && !statement.substring(match.end()).stripLeading().startsWith(","), "测试 SQL 不支持多表写入");
        }
    }
}
