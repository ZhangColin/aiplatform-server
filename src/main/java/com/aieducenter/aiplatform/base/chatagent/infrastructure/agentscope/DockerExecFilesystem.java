package com.aieducenter.aiplatform.base.chatagent.infrastructure.agentscope;

import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentWorkspace;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * 项目 dev 工作区文件面（#45 工作区桥）：{@link AbstractFilesystem} 的 docker exec
 * 实现——全部文件操作落在既有 dev 容器的 {@code /workspace}（Docker 常开口径），
 * 与编码引擎同视图同物理落点（写入即进源码包），不新建容器、不改容器拓扑
 * （编码引擎零变化）。
 *
 * <p>语义对齐 AgentScope 本地文件系统：路径为工作区锚定形（{@code /docs/PRD.md}，
 * 根 = 容器工作区根；{@code ..} 阻断）；ls 缺失目录回空、write 对既有文件拒绝、
 * delete 幂等、read 按扩展名判二进制走 base64。已知取舍：glob 以 Java PathMatcher
 * 匹配（{@code **} 跨目录，不带零段特例）；grep 结果按 {@code 路径:行:文本} 解析，
 * 文件名含冒号会错位（Phase A 项目工作区可接受）。</p>
 *
 * <p>{@link ExecCommand} 是执行缝（单测注入替身）；每条命令带超时保护，容器不在
 * （docker exec 非零退出）即以 fail 结果如实暴露，不抛出。</p>
 */
@Slf4j
public final class DockerExecFilesystem implements AbstractFilesystem {

    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(30);
    /** read 分页缺省与 LocalFilesystem 同口径（AbstractFilesystem 契约注释）。 */
    private static final int READ_DEFAULT_LINES = 2000;

    private final ExecCommand exec;

    public DockerExecFilesystem(String containerName) {
        this(new DockerExecCommand(containerName, ChatAgentWorkspace.ProjectDev.CONTAINER_ROOT));
    }

    DockerExecFilesystem(ExecCommand exec) {
        this.exec = exec;
    }

    /**
     * 执行缝：给定容器内 shell 命令（已按根目录锚定）与可选 stdin，返回退出码 /
     * stdout 字节 / stderr。真实实现走 docker exec -i。
     */
    interface ExecCommand {

        ExecOutput run(String command, byte[] stdin);
    }

    /** 一次 exec 的结果（stdout 字节保真，文本/二进制由调用方解释）。 */
    record ExecOutput(int exitCode, byte[] stdout, String stderr) {

        boolean ok() {
            return exitCode == 0;
        }
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        String containerPath = containerPathOrNull(path);
        if (containerPath == null) {
            return LsResult.fail("路径非法: " + path);
        }
        ExecOutput out = exec.run(
                "find " + sh(containerPath) + " -maxdepth 1 -mindepth 1 -printf '%y\\t%s\\t%T@\\t%f\\n'",
                null);
        if (!out.ok()) {
            // 目录不存在等：与本地实现同口径回空（ls 不区分不存在与空目录）
            return LsResult.success(List.of());
        }
        String parent = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        List<FileInfo> entries = new ArrayList<>();
        for (String line : new String(out.stdout(), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 4);
            if (parts.length != 4) {
                continue;
            }
            String type = parts[0];
            long size = Long.parseLong(parts[1]);
            long modifiedMs = (long) Double.parseDouble(parts[2]);
            String name = parts[3];
            if ("d".equals(type)) {
                entries.add(FileInfo.ofDir(parent + "/" + name + "/",
                        Instant.ofEpochMilli(modifiedMs).toString()));
            }
            else {
                entries.add(FileInfo.ofFile(parent + "/" + name, size,
                        Instant.ofEpochMilli(modifiedMs).toString()));
            }
        }
        entries.sort(Comparator.comparing(FileInfo::path));
        return LsResult.success(entries);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        String containerPath = containerPathOrNull(filePath);
        if (containerPath == null) {
            return ReadResult.fail("路径非法: " + filePath);
        }
        ExecOutput out = exec.run(
                "test -f " + sh(containerPath) + " && cat " + sh(containerPath), null);
        if (out.exitCode() == 1) {
            // sh 语义：test 失败（文件不存在）退出码恰 1；cat 权限错也归此类
            return ReadResult.fail("File '" + filePath + "' not found");
        }
        if (!out.ok()) {
            // docker exec 自身失败（容器不在/daemon 不在，退出码 125/126 等）——如实暴露
            return ReadResult.fail("读取失败（容器不可达？）: " + out.stderr());
        }
        if (!isTextFile(filePath)) {
            return ReadResult.success(new FileData(
                    Base64.getEncoder().encodeToString(out.stdout()), "base64"));
        }
        String content = new String(out.stdout(), StandardCharsets.UTF_8);
        if (content.isEmpty()) {
            return ReadResult.success(new FileData("System reminder: File exists but has empty contents", "utf-8"));
        }
        String[] lines = content.split("\n", -1);
        int startIdx = Math.max(0, offset);
        int endIdx = limit > 0 ? Math.min(startIdx + limit, lines.length) : lines.length;
        if (startIdx >= lines.length) {
            return ReadResult.fail("Line offset " + offset + " exceeds file length ("
                    + lines.length + " lines)");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < endIdx; i++) {
            if (i > startIdx) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return ReadResult.success(new FileData(sb.toString(), "utf-8"));
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        return create(filePath, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public EditResult edit(RuntimeContext runtimeContext, String filePath, String oldString,
            String newString, boolean replaceAll) {
        ReadResult read = read(runtimeContext, filePath, 0, 0);
        if (!read.isSuccess() || read.fileData() == null || !"utf-8".equals(read.fileData().encoding())) {
            return EditResult.fail(read.isSuccess() ? "仅支持文本文件编辑"
                    : read.error());
        }
        String content = read.fileData().content();
        int count = countOccurrences(content, oldString);
        if (count == 0) {
            return EditResult.fail("oldString not found in " + filePath);
        }
        if (!replaceAll && count > 1) {
            return EditResult.fail("oldString is not unique in " + filePath
                    + " (" + count + " occurrences)");
        }
        String replaced = replaceAll
                ? content.replace(oldString, newString)
                : replaceFirst(content, oldString, newString);
        WriteResult written = put(filePath, replaced.getBytes(StandardCharsets.UTF_8));
        if (!written.isSuccess()) {
            return EditResult.fail(written.error());
        }
        return EditResult.ok(filePath, replaceAll ? count : 1);
    }

    @Override
    public GrepResult grep(RuntimeContext runtimeContext, String pattern, String path, String glob) {
        String containerPath = containerPathOrNull(path != null ? path : ".");
        if (containerPath == null) {
            return GrepResult.fail("路径非法: " + path);
        }
        StringBuilder cmd = new StringBuilder("grep -rFIn ");
        if (glob != null && !glob.isBlank()) {
            cmd.append("--include=").append(sh(glob)).append(' ');
        }
        cmd.append("-- ").append(sh(pattern)).append(' ').append(sh(containerPath));
        ExecOutput out = exec.run(cmd.toString(), null);
        // grep 无命中退出码 1（不是错误）；容器不可达（125/126）才是失败
        if (out.exitCode() > 1) {
            return GrepResult.fail("grep 失败: " + out.stderr());
        }
        String root = ChatAgentWorkspace.ProjectDev.CONTAINER_ROOT;
        List<GrepMatch> matches = new ArrayList<>();
        for (String line : new String(out.stdout(), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int first = line.indexOf(':');
            int second = line.indexOf(':', first + 1);
            if (first < 0 || second < 0) {
                continue;
            }
            String containerFile = line.substring(0, first);
            String inputPath = containerFile.startsWith(root + "/")
                    ? containerFile.substring(root.length())
                    : containerFile;
            matches.add(new GrepMatch(inputPath,
                    Integer.parseInt(line.substring(first + 1, second)),
                    line.substring(second + 1)));
        }
        return GrepResult.success(matches);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        String containerPath = containerPathOrNull(path != null && !path.isBlank() ? path : ".");
        if (containerPath == null) {
            return GlobResult.fail("路径非法: " + path);
        }
        ExecOutput out = exec.run(
                "find " + sh(containerPath) + " -type f -printf '%s\\t%T@\\t%p\\0'", null);
        if (!out.ok()) {
            return GlobResult.success(List.of());
        }
        String root = ChatAgentWorkspace.ProjectDev.CONTAINER_ROOT;
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<FileInfo> matches = new ArrayList<>();
        for (String record : new String(out.stdout(), StandardCharsets.UTF_8).split("\0")) {
            if (record.isBlank()) {
                continue;
            }
            String[] parts = record.split("\t", 3);
            if (parts.length != 3) {
                continue;
            }
            String containerFile = parts[2];
            String inputPath = containerFile.startsWith(root + "/")
                    ? containerFile.substring(root.length())
                    : containerFile;
            if (matcher.matches(java.nio.file.Path.of(inputPath))) {
                matches.add(FileInfo.ofFile(inputPath, Long.parseLong(parts[0]),
                        Instant.ofEpochMilli((long) Double.parseDouble(parts[1])).toString()));
            }
        }
        matches.sort(Comparator.comparing(FileInfo::path));
        return GlobResult.success(matches);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(RuntimeContext runtimeContext,
            List<Map.Entry<String, byte[]>> files) {
        List<FileUploadResponse> responses = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : files) {
            WriteResult result = put(file.getKey(), file.getValue());
            responses.add(result.isSuccess()
                    ? FileUploadResponse.success(file.getKey())
                    : FileUploadResponse.fail(file.getKey(), result.error()));
        }
        return responses;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> responses = new ArrayList<>();
        for (String path : paths) {
            String containerPath = containerPathOrNull(path);
            if (containerPath == null) {
                responses.add(FileDownloadResponse.fail(path, "路径非法"));
                continue;
            }
            ExecOutput out = exec.run("cat " + sh(containerPath) + " 2>/dev/null", null);
            responses.add(out.ok()
                    ? FileDownloadResponse.success(path, out.stdout())
                    : FileDownloadResponse.fail(path, "File '" + path + "' not found"));
        }
        return responses;
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        String containerPath = containerPathOrNull(path);
        if (containerPath == null) {
            return WriteResult.fail("路径非法: " + path);
        }
        // 幂等：不存在也算成功（与本地实现同口径）
        ExecOutput out = exec.run("rm -rf -- " + sh(containerPath), null);
        return out.ok() ? WriteResult.ok(path)
                : WriteResult.fail("Error deleting '" + path + "': " + out.stderr());
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        String from = containerPathOrNull(fromPath);
        String to = containerPathOrNull(toPath);
        if (from == null || to == null) {
            return WriteResult.fail("路径非法: " + fromPath + " -> " + toPath);
        }
        ExecOutput out = exec.run(
                "test -e " + sh(from) + " && mkdir -p " + sh(parentOf(to))
                        + " && mv -- " + sh(from) + " " + sh(to),
                null);
        return out.ok() ? WriteResult.ok(toPath)
                : WriteResult.fail("Error moving '" + fromPath + "' to '" + toPath + "': "
                        + out.stderr());
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        String containerPath = containerPathOrNull(path);
        return containerPath != null
                && exec.run("test -e " + sh(containerPath), null).ok();
    }

    // ---------- 内部 ----------

    /** 覆盖写（write 的 create-only 语义之外的内通道：edit 回写 / upload）。 */
    private WriteResult put(String filePath, byte[] content) {
        String containerPath = containerPathOrThrow(filePath);
        ExecOutput out = exec.run(overwriteWriteCommand(containerPath), content);
        return out.ok() ? WriteResult.ok(filePath)
                : WriteResult.fail("Error writing file '" + filePath + "': " + out.stderr());
    }

    /**
     * 覆盖写命令（mkdir 兜底父目录 + {@code cat >} 覆盖，stdin 灌内容）：
     * 文件面写路径的单一缝——edit 回写/upload 与 SavePrdTool 的 PRD 落盘共用同形。
     */
    static String overwriteWriteCommand(String containerPath) {
        return "mkdir -p " + sh(parentOf(containerPath)) + " && cat > " + sh(containerPath);
    }

    /** create-only 写：noclobber（set -C）保证「已存在即拒绝」原子成立（防 test/cat 间隙竞态）。 */
    private WriteResult create(String filePath, byte[] content) {
        String containerPath = containerPathOrThrow(filePath);
        ExecOutput out = exec.run(
                "mkdir -p " + sh(parentOf(containerPath)) + " && (set -C; cat > "
                        + sh(containerPath) + ")",
                content);
        return out.ok() ? WriteResult.ok(filePath)
                : WriteResult.fail("Cannot write to " + filePath
                        + " because it already exists. Read and then make an edit,"
                        + " or write to a new path.");
    }

    /** 输入路径（工作区锚定形）→ 容器绝对路径；非法（含 ..）返回 null。根（"/" 或 "."）即容器工作区根本身。 */
    private String containerPathOrNull(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            AbstractFilesystem.validatePath(path);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
        String rel = normalizeInputPath(path);
        return rel.equals(".")
                ? ChatAgentWorkspace.ProjectDev.CONTAINER_ROOT
                : ChatAgentWorkspace.ProjectDev.CONTAINER_ROOT + "/" + rel;
    }

    private String containerPathOrThrow(String path) {
        String containerPath = containerPathOrNull(path);
        if (containerPath == null) {
            throw new IllegalArgumentException("Path invalid: " + path);
        }
        return containerPath;
    }

    /** 去掉前导斜杠的输入路径（"/docs/a.md" → "docs/a.md"，"." 保持 "."）。 */
    private static String normalizeInputPath(String path) {
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        return stripped.isEmpty() ? "." : stripped;
    }

    private static String parentOf(String containerPath) {
        int idx = containerPath.lastIndexOf('/');
        return idx <= 0 ? "/" : containerPath.substring(0, idx);
    }

    /** shell 单引号包裹（内嵌单引号转义为 '\''），杜绝注入。包内共用（SavePrdTool 同式调用）。 */
    static String sh(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static int countOccurrences(String content, String target) {
        if (target.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int idx = content.indexOf(target); idx >= 0;
                idx = content.indexOf(target, idx + target.length())) {
            count++;
        }
        return count;
    }

    private static String replaceFirst(String content, String target, String replacement) {
        int idx = content.indexOf(target);
        return idx < 0 ? content
                : content.substring(0, idx) + replacement + content.substring(idx + target.length());
    }

    /** 二进制判定按扩展名（与 FilesystemUtils 文本扩展表同思路，取常用集）。 */
    private static boolean isTextFile(String path) {
        String lower = path.toLowerCase();
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        return switch (ext) {
            case "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "pdf", "zip", "gz", "tar",
                    "tgz", "jar", "woff", "woff2", "ttf", "eot", "mp3", "mp4", "exe" -> false;
            default -> true;
        };
    }

    /**
     * 真实执行：{@code docker exec -i <容器> sh -c <命令>}（-i 供 stdin 直灌）。
     * stderr 异步读、stdin 异步写防管道缓冲死锁；超时强杀。
     */
    static final class DockerExecCommand implements ExecCommand {

        private final String[] prefix;

        DockerExecCommand(String containerName, String workdir) {
            this.prefix = new String[]{"docker", "exec", "-i", "-w", workdir, containerName,
                    "sh", "-c"};
        }

        @Override
        public ExecOutput run(String command, byte[] stdin) {
            String[] cmd = new String[prefix.length + 1];
            System.arraycopy(prefix, 0, cmd, 0, prefix.length);
            cmd[prefix.length] = command;
            try {
                Process p = new ProcessBuilder(cmd).start();
                CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> {
                    try {
                        return new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                    }
                    catch (Exception e) {
                        return "";
                    }
                });
                if (stdin != null) {
                    CompletableFuture.runAsync(() -> {
                        try (OutputStream in = p.getOutputStream()) {
                            in.write(stdin);
                        }
                        catch (Exception ignored) {
                            // 容器侧提前退出：写断由退出码表达
                        }
                    });
                }
                byte[] stdout = p.getInputStream().readAllBytes();
                if (!p.waitFor(EXEC_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return new ExecOutput(124, new byte[0], "docker exec 超时");
                }
                return new ExecOutput(p.exitValue(), stdout, stderr.join());
            }
            catch (Exception e) {
                return new ExecOutput(1, new byte[0], String.valueOf(e.getMessage()));
            }
        }
    }
}
