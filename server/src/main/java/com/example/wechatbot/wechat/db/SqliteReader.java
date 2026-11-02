package com.example.wechatbot.wechat.db;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 极简 SQLite 只读解析器：只做「按表名扫行」这一件事。
 *
 * <p>为什么不用 JDBC：微信的库是 SQLCipher 加密的，先要自己解密页；解出来的明文页再交给
 * 一个 driver 又要额外依赖。这里需要的只是 {@code VoiceInfo} 一张表的整表扫描，直接按
 * B-tree 页面格式读更省事，也不用把明文数据库落到磁盘上。</p>
 *
 * <p>支持：表 B-tree 的叶子页（13）与内部页（5）、payload 溢出链、记录头里的全部
 * serial type（null / int / float / text / blob）。</p>
 */
public final class SqliteReader {
    private static final int LEAF_TABLE = 13;
    private static final int INTERIOR_TABLE = 5;
    private static final int MAX_DEPTH = 24;

    private final SqlCipherFile file;
    private final int usableSize;

    public SqliteReader(SqlCipherFile file) {
        this.file = file;
        this.usableSize = file.usableSize();
    }

    /** sqlite_master 里的表名 → 根页号。 */
    public Map<String, Integer> tables() {
        Map<String, Integer> tables = new LinkedHashMap<>();
        for (Object[] row : scan(1)) {
            if (row.length < 5 || !"table".equalsIgnoreCase(String.valueOf(row[0]))) {
                continue;
            }
            String name = String.valueOf(row[1]);
            Object root = row[3];
            if (root instanceof Long value) {
                tables.put(name, value.intValue());
            }
        }
        return tables;
    }

    /** 表定义 SQL（用来拿列名）。 */
    public Optional<String> schema(String table) {
        for (Object[] row : scan(1)) {
            if (row.length >= 5 && "table".equalsIgnoreCase(String.valueOf(row[0]))
                    && table.equalsIgnoreCase(String.valueOf(row[1]))) {
                Object sql = row[4];
                return sql == null ? Optional.empty() : Optional.of(String.valueOf(sql));
            }
        }
        return Optional.empty();
    }

    /** 列名（按 CREATE TABLE 里的顺序；解析不出来就返回空表）。 */
    public List<String> columns(String table) {
        Optional<String> sql = schema(table);
        if (sql.isEmpty()) {
            return List.of();
        }
        return parseColumns(sql.get());
    }

    /** 整表扫描；列值类型：null / Long / Double / String / byte[]。 */
    public List<Object[]> rows(String table) {
        Integer root = tables().get(table);
        if (root == null) {
            return List.of();
        }
        return scan(root);
    }

    /** 按列名取值（列名大小写不敏感）。 */
    public static Object value(List<String> columns, Object[] row, String column) {
        for (int i = 0; i < columns.size() && i < row.length; i++) {
            if (columns.get(i).equalsIgnoreCase(column)) {
                return row[i];
            }
        }
        return null;
    }

    public static int indexOf(List<String> columns, String column) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(column)) {
                return i;
            }
        }
        return -1;
    }

    private List<Object[]> scan(int rootPage) {
        List<Object[]> rows = new ArrayList<>();
        walk(rootPage, rows, 0);
        return rows;
    }

    private void walk(int pageNo, List<Object[]> rows, int depth) {
        if (depth > MAX_DEPTH || pageNo < 1 || pageNo > file.pageCount()) {
            return;
        }
        byte[] page = file.page(pageNo);
        int headerOffset = pageNo == 1 ? 100 : 0;
        int type = page[headerOffset] & 0xff;
        int cellCount = readU16(page, headerOffset + 3);
        int pointerBase = headerOffset + (type == INTERIOR_TABLE ? 12 : 8);
        if (type == INTERIOR_TABLE) {
            int rightMost = readU32(page, headerOffset + 8);
            for (int i = 0; i < cellCount; i++) {
                int cell = readU16(page, pointerBase + i * 2);
                if (cell <= 0 || cell >= page.length) {
                    continue;
                }
                walk(readU32(page, cell), rows, depth + 1);
            }
            walk(rightMost, rows, depth + 1);
            return;
        }
        if (type != LEAF_TABLE) {
            return;
        }
        for (int i = 0; i < cellCount; i++) {
            int cell = readU16(page, pointerBase + i * 2);
            if (cell <= 0 || cell >= page.length) {
                continue;
            }
            // 表格叶子页：varint payload 长度 + varint rowid + payload
            int[] cursor = {cell};
            long payloadLength = readVarint(page, cursor);
            readVarint(page, cursor); // rowid，用不到
            byte[] payload = readPayload(page, cursor[0], (int) payloadLength);
            if (payload != null) {
                rows.add(decodeRecord(payload));
            }
        }
    }

    /** 读 payload；超出本页容量的部分沿溢出页链拼回来。 */
    private byte[] readPayload(byte[] page, int start, int payloadLength) {
        if (payloadLength <= 0) {
            return new byte[0];
        }
        int maxLocal = usableSize - 35;
        int minLocal = ((usableSize - 12) * 32 / 255) - 23;
        int local = payloadLength;
        if (payloadLength > maxLocal) {
            local = minLocal + (payloadLength - minLocal) % (usableSize - 4);
            if (local > maxLocal) {
                local = minLocal;
            }
        }
        if (start + local > page.length) {
            return null;
        }
        byte[] payload = new byte[payloadLength];
        System.arraycopy(page, start, payload, 0, local);
        if (local == payloadLength) {
            return payload;
        }
        int overflowPage = readU32(page, start + local);
        int written = local;
        int guard = 0;
        while (overflowPage > 0 && written < payloadLength && guard++ < 10_000) {
            if (overflowPage < 1 || overflowPage > file.pageCount()) {
                break;
            }
            byte[] overflow = file.page(overflowPage);
            int chunk = Math.min(usableSize - 4, payloadLength - written);
            System.arraycopy(overflow, 4, payload, written, chunk);
            written += chunk;
            overflowPage = readU32(overflow, 0);
        }
        return written == payloadLength ? payload : null;
    }

    private static Object[] decodeRecord(byte[] payload) {
        int[] cursor = {0};
        long headerSize = readVarint(payload, cursor);
        int headerStart = cursor[0];
        int headerEnd = (int) Math.min(payload.length, headerSize);
        List<Long> serialTypes = new ArrayList<>();
        while (cursor[0] < headerEnd) {
            serialTypes.add(readVarint(payload, cursor));
        }
        Object[] values = new Object[serialTypes.size()];
        int body = headerEnd;
        for (int i = 0; i < serialTypes.size(); i++) {
            long type = serialTypes.get(i);
            int length = serialTypeLength(type);
            if (body + length > payload.length) {
                values[i] = null;
                body = payload.length;
                continue;
            }
            values[i] = serialTypeValue(type, payload, body, length);
            body += length;
        }
        return values;
    }

    private static int serialTypeLength(long type) {
        return switch ((int) type) {
            case 0 -> 0;            // null
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 3;
            case 4 -> 4;
            case 5 -> 6;
            case 6, 7 -> 8;         // 8 字节整数 / float64
            case 8, 9 -> 0;         // 常量 0 / 1
            case 10, 11 -> 0;       // 保留
            default -> (int) ((type - 12) / 2); // 12/13 起：blob/文本，偶数 blob、奇数文本
        };
    }

    private static Object serialTypeValue(long type, byte[] payload, int offset, int length) {
        switch ((int) type) {
            case 0:
                return null;
            case 8:
                return 0L;
            case 9:
                return 1L;
            case 7:
                return Double.longBitsToDouble(readLong(payload, offset, 8));
            case 12:
                return new byte[0];
            case 13:
                return "";
            default:
                break;
        }
        if (type >= 1 && type <= 6) {
            return readLong(payload, offset, length);
        }
        byte[] slice = java.util.Arrays.copyOfRange(payload, offset, offset + length);
        if (type % 2 == 0) {
            return slice;
        }
        return new String(slice, StandardCharsets.UTF_8);
    }

    private static long readLong(byte[] buffer, int offset, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (buffer[offset + i] & 0xff);
        }
        // 大端补码：长度小于 8 时补上符号位
        int shift = (8 - length) * 8;
        return (value << shift) >> shift;
    }

    static long readVarint(byte[] buffer, int[] cursor) {
        long value = 0;
        for (int i = 0; i < 9; i++) {
            if (cursor[0] >= buffer.length) {
                return value;
            }
            int b = buffer[cursor[0]++] & 0xff;
            if (i == 8) {
                value = (value << 8) | b;
                return value;
            }
            value = (value << 7) | (b & 0x7f);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        return value;
    }

    private static int readU16(byte[] buffer, int offset) {
        if (offset + 2 > buffer.length) {
            return 0;
        }
        return ((buffer[offset] & 0xff) << 8) | (buffer[offset + 1] & 0xff);
    }

    private static int readU32(byte[] buffer, int offset) {
        if (offset + 4 > buffer.length) {
            return 0;
        }
        return ((buffer[offset] & 0xff) << 24) | ((buffer[offset + 1] & 0xff) << 16)
                | ((buffer[offset + 2] & 0xff) << 8) | (buffer[offset + 3] & 0xff);
    }

    /** 从 {@code CREATE TABLE x(a INTEGER, b TEXT, ...)} 里抠列名。 */
    static List<String> parseColumns(String sql) {
        int open = sql.indexOf('(');
        int close = sql.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return List.of();
        }
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = open + 1; i < close; i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                // 单/双引号里的逗号是默认值的一部分，不能当分隔符
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                addColumn(columns, current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        addColumn(columns, current.toString());
        return columns;
    }

    private static void addColumn(List<String> columns, String definition) {
        String trimmed = definition.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String keyword = trimmed.toUpperCase(Locale.ROOT);
        for (String reserved : List.of("PRIMARY", "UNIQUE", "CHECK", "FOREIGN", "CONSTRAINT")) {
            if (keyword.startsWith(reserved)) {
                return;
            }
        }
        int space = 0;
        while (space < trimmed.length() && !Character.isWhitespace(trimmed.charAt(space))) {
            space++;
        }
        String name = trimmed.substring(0, space);
        while (name.length() >= 2 && (name.startsWith("\"") || name.startsWith("`") || name.startsWith("["))) {
            name = name.substring(1, name.length() - 1);
        }
        if (!name.isEmpty()) {
            columns.add(name);
        }
    }
}
