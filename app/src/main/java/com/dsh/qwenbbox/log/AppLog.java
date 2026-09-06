package com.dsh.qwenbbox.log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 极简运行日志：线程安全地写入内存环形缓冲（供日志页查看），并同步追加到指定的日志文件
 * （供 adb pull / 文件管理器导出排查）。纯 Java 实现、无 Android 依赖，可被 JVM 单测直接加载。
 *
 * <p>用法：入口处 {@link #init(File)} 指定日志文件（Android 端由 MainActivity 传入
 * {@code externalFilesDir/logs/app.log}）；之后任意线程 {@link #i}/{@link #w}/{@link #e} 记录；
 * {@link #dump()} 读全文、{@link #file()} 拿文件、{@link #clear()} 清空。未 init（或传 null）时
 * 仅写内存、不落盘。</p>
 */
public final class AppLog {
    private AppLog() {}

    /** 内存最多保留的日志行数（超出丢弃最旧）。 */
    private static final int MAX_LINES = 4000;

    private static final Object LOCK = new Object();
    private static final Deque<String> MEMORY = new ArrayDeque<String>();

    private static File file;

    /** 指定日志文件（可传 null 表示只写内存）。自动创建父目录。 */
    public static synchronized void init(File logFile) {
        file = logFile;
        if (file != null) {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
        }
    }

    public static void i(String tag, String msg) { log('I', tag, msg, null); }
    public static void w(String tag, String msg) { log('W', tag, msg, null); }
    public static void e(String tag, String msg) { log('E', tag, msg, null); }
    public static void e(String tag, String msg, Throwable t) { log('E', tag, msg, t); }

    private static void log(char level, String tag, String msg, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()));
        sb.append(' ').append(level).append('/');
        sb.append(tag == null ? "" : tag).append(": ");
        sb.append(msg == null ? "" : msg);
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append('\n').append(sw.toString());
        }
        final String line = sb.toString();

        synchronized (LOCK) {
            MEMORY.addLast(line);
            while (MEMORY.size() > MAX_LINES) MEMORY.removeFirst();
        }

        File f = file;
        if (f != null) {
            try {
                FileWriter fw = new FileWriter(f, true);
                fw.write(line + "\n");
                fw.close();
            } catch (IOException ignore) { }
        }
    }

    /** 内存里全部日志（最新在末尾）。 */
    public static String dump() {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder();
            for (String s : MEMORY) sb.append(s).append('\n');
            return sb.toString();
        }
    }

    /** 日志文件（可能为 null，表示未落盘）。 */
    public static File file() {
        return file;
    }

    /** 清空内存与文件。 */
    public static void clear() {
        synchronized (LOCK) {
            MEMORY.clear();
        }
        File f = file;
        if (f != null) {
            try {
                FileWriter fw = new FileWriter(f, false); // 截断
                fw.close();
            } catch (IOException ignore) { }
        }
    }
}
