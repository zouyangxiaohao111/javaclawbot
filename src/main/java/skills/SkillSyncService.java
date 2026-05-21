package skills;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

/**
 * 技能同步服务
 * 对比内置技能（resources/skills/）与工作空间技能（workspace/skills/）
 */
@Slf4j
public class SkillSyncService {

    private final Path workspaceSkills;
    private final Path builtinSkillsDir;

    public SkillSyncService(Path workspace, Path builtinSkillsDir) {
        this.workspaceSkills = workspace.resolve("skills");
        this.builtinSkillsDir = builtinSkillsDir != null ? builtinSkillsDir : SkillsLoader.BUILTIN_SKILLS_DIR;
    }

    public SkillSyncService(Path workspace) {
        this(workspace, null);
    }

    /**
     * 计算文件内容的 SHA-256 哈希
     */
    public String calculateHash(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(filePath);
            byte[] hashBytes = digest.digest(fileBytes);
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 计算 classpath 资源的 SHA-256 哈希
     */
    public String calculateClasspathHash(String resourcePath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("资源不存在: " + resourcePath);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] allBytes = is.readAllBytes();
            byte[] hashBytes = digest.digest(allBytes);
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 扫描工作空间技能目录，返回 {skillName: skillDirPath}
     */
    public Map<String, Path> scanWorkspaceSkills() {
        Map<String, Path> skills = new HashMap<>();
        if (!Files.exists(workspaceSkills)) {
            log.info("工作空间技能目录不存在: {}", workspaceSkills);
            return skills;
        }

        try (Stream<Path> paths = Files.walk(workspaceSkills)) {
            paths.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                 .forEach(skillMd -> {
                     Path skillDir = skillMd.getParent();
                     String relativePath = workspaceSkills.relativize(skillDir).toString();
                     // 标准化路径分隔符
                     String skillName = relativePath.replace('\\', '/');
                     skills.put(skillName, skillDir);
                 });
        } catch (IOException e) {
            log.error("扫描工作空间技能失败", e);
        }

        log.debug("扫描到 {} 个工作空间技能", skills.size());
        return skills;
    }

    /**
     * 扫描内置技能（从 classpath）
     * 返回 {skillName: classpathResourcePath}
     */
    public Map<String, String> scanBuiltinSkills() {
        Map<String, String> skills = new HashMap<>();

        try {
            // 使用 BuiltinSkillsInstaller 的发现逻辑
            List<cli.BuiltinSkillsInstaller.SkillResource> discovered =
                cli.BuiltinSkillsInstaller.discoverBuiltinSkills();

            for (cli.BuiltinSkillsInstaller.SkillResource resource : discovered) {
                String name = resource.getName();
                String classpathDir = resource.getClasspathDir();
                skills.put(name, classpathDir);
            }
        } catch (Exception e) {
            log.error("扫描内置技能失败", e);
        }

        log.debug("扫描到 {} 个内置技能", skills.size());
        return skills;
    }

    /**
     * 对比内置技能与工作空间技能，返回差异列表
     */
    public List<SkillDifference> findDifferences() {
        List<SkillDifference> differences = new ArrayList<>();

        Map<String, String> builtinSkills = scanBuiltinSkills();
        Map<String, Path> workspaceSkillsMap = scanWorkspaceSkills();

        for (Map.Entry<String, String> entry : builtinSkills.entrySet()) {
            String skillName = entry.getKey();
            String classpathDir = entry.getValue();

            try {
                // 计算内置技能哈希（取 SKILL.md）
                String builtinResourcePath = classpathDir + "/SKILL.md";
                String builtinHash = calculateClasspathHash(builtinResourcePath);

                Path workspaceSkillDir = workspaceSkillsMap.get(skillName);
                if (workspaceSkillDir == null) {
                    // 工作空间中不存在
                    differences.add(new SkillDifference(skillName, builtinHash, null,
                        SkillDifference.DifferenceType.NEW));
                    log.debug("技能 {} 在工作空间中不存在", skillName);
                } else {
                    // 工作空间中存在，对比哈希
                    Path workspaceSkillMd = workspaceSkillDir.resolve("SKILL.md");
                    if (Files.exists(workspaceSkillMd)) {
                        String workspaceHash = calculateHash(workspaceSkillMd);
                        if (!builtinHash.equals(workspaceHash)) {
                            differences.add(new SkillDifference(skillName, builtinHash, workspaceHash,
                                SkillDifference.DifferenceType.MODIFIED));
                            log.debug("技能 {} 内容不同: builtin={}, workspace={}",
                                skillName, builtinHash.substring(0, 8), workspaceHash.substring(0, 8));
                        }
                    } else {
                        // SKILL.md 不存在，视为新增
                        differences.add(new SkillDifference(skillName, builtinHash, null,
                            SkillDifference.DifferenceType.NEW));
                    }
                }
            } catch (IOException e) {
                log.warn("对比技能 {} 失败: {}", skillName, e.getMessage());
            }
        }

        log.info("发现 {} 个技能差异", differences.size());
        return differences;
    }

    /**
     * 复制内置技能到工作空间（覆盖）
     */
    public void copySkillToWorkspace(String skillName) throws IOException {
        Map<String, String> builtinSkills = scanBuiltinSkills();
        String classpathDir = builtinSkills.get(skillName);

        if (classpathDir == null) {
            throw new IllegalArgumentException("内置技能不存在: " + skillName);
        }

        Path targetDir = workspaceSkills.resolve(skillName);

        // 使用 BuiltinSkillsInstaller 的复制逻辑
        cli.BuiltinSkillsInstaller.InstallSummary summary =
            cli.BuiltinSkillsInstaller.installSkill(skillName, classpathDir, workspaceSkills);

        if (!summary.getFailed().isEmpty()) {
            throw new IOException("复制技能失败: " + summary.getFailed());
        }

        log.info("已复制技能 {} 到 {}", skillName, targetDir);
    }
}
