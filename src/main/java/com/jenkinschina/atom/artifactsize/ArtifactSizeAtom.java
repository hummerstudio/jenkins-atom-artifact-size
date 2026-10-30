package com.jenkinschina.atom.artifactsize;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Glob;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Artifact size as an atom: sum what the build produced, show the biggest files, and gate on a budget.
 *
 * <p>A build that quietly grows from 40 MB to 400 MB costs time on every agent it touches; this turns that into a
 * number in the log and, if asked, a failed build.
 */
public class ArtifactSizeAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String pattern = context.getParam("pattern", "**/*.jar, **/*.war, **/*.zip, **/*.tar.gz");
        String outDir = context.getParam("outputDir", "out/artifact-size");
        double maxMb = toDouble(context.getParam("maxMb", "0"), 0);
        int topN = Math.max(1, Math.min(context.getIntParam("topN", 20), 200));

        if (pattern.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001, "pattern is required");
        }

        List<Entry> entries = new ArrayList<>();
        // Glob from the SDK: **/ means zero or more directories, the way Ant and Jenkins read it
        for (Path path : Glob.find(workspace, pattern)) {
            if (path.toString().contains("/out/artifact-size/")) {
                continue;
            }
            try {
                entries.add(new Entry(workspace.relativize(path).toString(), Files.size(path)));
            } catch (IOException ignored) {
                // the file disappeared between the walk and the read
            }
        }
        if (entries.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "nothing matched " + pattern);
        }

        entries.sort(Comparator.comparingLong(Entry::bytes).reversed());
        long total = entries.stream().mapToLong(Entry::bytes).sum();
        double totalMb = total / (1024.0 * 1024.0);

        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"), render(pattern, entries, total, topN), StandardCharsets.UTF_8);

        context.log(String.format(Locale.ROOT, "[artifact-size] %d file(s), %s total (%.2f MB), largest: %s (%s)",
                entries.size(), human(total), totalMb, entries.get(0).name(), human(entries.get(0).bytes())));

        AtomResult result = AtomResult.success()
                .data("files", String.valueOf(entries.size()))
                .data("totalBytes", String.valueOf(total))
                .data("totalHuman", human(total))
                .data("totalMb", String.format(Locale.ROOT, "%.2f", totalMb))
                .data("largest", entries.get(0).name())
                .data("largestBytes", String.valueOf(entries.get(0).bytes()))
                .report("report", "Artifact sizes", dir.toString(), "index.html");

        if (maxMb > 0 && totalMb > maxMb) {
            return AtomResult.failure(String.format(Locale.ROOT,
                    "artifacts are %.2f MB, over the %.2f MB budget", totalMb, maxMb))
                    .data("totalBytes", String.valueOf(total))
                    .data("totalMb", String.format(Locale.ROOT, "%.2f", totalMb))
                    .report("report", "Artifact sizes", dir.toString(), "index.html");
        }
        return result;
    }

    private static String render(String pattern, List<Entry> entries, long total, int topN) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<title>Artifact sizes</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 20px}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;font-size:13px}")
                .append("th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #eef0f3}")
                .append("th{background:#fafbfc;color:#6b7280}td.n{text-align:right;font-variant-numeric:tabular-nums}")
                .append(".bar{height:6px;background:#3b82f6;border-radius:3px}")
                .append("</style></head><body>");
        html.append("<h1>Artifact sizes</h1><p class=\"meta\">").append(entries.size()).append(" file(s), ")
                .append(human(total)).append(" total · <code>").append(escape(pattern)).append("</code></p>");
        html.append("<table><tr><th>File</th><th class=\"n\">Size</th><th>Share</th></tr>");
        long largest = entries.get(0).bytes();
        for (int i = 0; i < Math.min(topN, entries.size()); i++) {
            Entry entry = entries.get(i);
            int width = (int) Math.max(1, Math.round(100.0 * entry.bytes() / largest));
            html.append("<tr><td><code>").append(escape(entry.name())).append("</code></td><td class=\"n\">")
                    .append(human(entry.bytes())).append("</td><td><div class=\"bar\" style=\"width:")
                    .append(width).append("%\"></div></td></tr>");
        }
        html.append("</table>");
        if (entries.size() > topN) {
            html.append("<p class=\"meta\">").append(entries.size() - topN).append(" more file(s) not shown</p>");
        }
        return html.append("</body></html>").toString();
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        for (String unit : List.of("KiB", "MiB", "GiB")) {
            value /= 1024;
            if (value < 1024) {
                return String.format(Locale.ROOT, "%.1f %s", value, unit);
            }
        }
        return String.format(Locale.ROOT, "%.1f TiB", value / 1024);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static double toDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record Entry(String name, long bytes) {}
}
