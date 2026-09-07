package org.ssssssss.magicapi.git;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.ssssssss.magicapi.core.resource.FileResource;
import org.ssssssss.magicapi.core.resource.Resource;
import org.ssssssss.magicapi.core.service.WorkspaceResourceCoordinator;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/** Git 文件存储：磁盘操作和整笔服务操作进入同一可重入边界。 */
public class GitResource extends FileResource implements AutoCloseable {
    private final GitRepo gitRepo;

    public static GitResource of(org.ssssssss.magicapi.core.config.Resource config, MagicGitProperties properties)
            throws IOException, GitAPIException {
        File file = new File(config.getLocation());
        GitRepo repo = new GitRepo(file.getAbsolutePath(), properties);
        try {
            repo.setupRepo();
            return new GitResource(config.isReadonly(), file, file.getAbsolutePath(), repo);
        } catch (IOException | GitAPIException | RuntimeException failure) {
            repo.close();
            throw failure;
        }
    }

    public GitResource(boolean readonly, File file, String rootPath, GitRepo gitRepo) {
        super(file, readonly, rootPath);
        this.gitRepo = gitRepo;
    }

    public GitRepo getGitRepo() {
        return gitRepo;
    }

    @Override
    public WorkspaceResourceCoordinator getWorkspaceCoordinator() {
        return gitRepo.getCoordinator();
    }

    @Override
    public void reload() {
        gitRepo.pullLatest();
    }

    @Override
    public void reload(Runnable refreshLocal) {
        // worker 的本地整合阶段发布缓存，HTTP 线程不再额外刷新第二次。
        reload();
    }

    @Override
    public boolean delete() {
        return mutate(() -> {
            Path workspace = Paths.get(rootPath).toAbsolutePath().normalize();
            Path path = file.toPath().toAbsolutePath().normalize();
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return dir.equals(workspace.resolve(".git")) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path child, BasicFileAttributes attrs) throws IOException {
                    if (!child.equals(workspace.resolve(".git"))) {
                        Files.delete(child);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    // 全量上传可以清空资源，但不能删除仓库元数据和工作根目录。
                    if (!dir.equals(workspace)) {
                        Files.delete(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        });
    }

    @Override
    public boolean mkdir() {
        return mutate(() -> {
            if (file.isDirectory()) {
                return false;
            }
            Files.createDirectories(file.toPath());
            return true;
        });
    }

    @Override
    public byte[] read() {
        return getWorkspaceCoordinator().read(() -> {
            try {
                return Files.readAllBytes(file.toPath());
            } catch (IOException failure) {
                throw new GitSyncException(GitSyncError.LOCAL_IO_ERROR);
            }
        });
    }

    @Override
    public boolean write(byte[] bytes) {
        return mutate(() -> {
            Files.write(file.toPath(), bytes);
            return true;
        });
    }

    @Override
    public boolean write(String content) {
        return content != null && write(content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean renameTo(Resource resource) {
        if (!(resource instanceof GitResource) || ((GitResource) resource).gitRepo != gitRepo) {
            throw new GitSyncException(GitSyncError.LOCAL_IO_ERROR);
        }
        return mutate(() -> {
            File target = ((GitResource) resource).file;
            Files.move(file.toPath(), target.toPath());
            file = target;
            return true;
        });
    }

    private boolean mutate(FileAction action) {
        return getWorkspaceCoordinator().write(() -> {
            if (readonly()) {
                return false;
            }
            try {
                Path previous = file.toPath();
                boolean changed = action.run();
                if (changed) {
                    gitRepo.recordMutation(previous, file.toPath());
                }
                return changed;
            } catch (IOException failure) {
                getWorkspaceCoordinator().markInconsistent();
                throw new GitSyncException(GitSyncError.WORKSPACE_INCONSISTENT);
            }
        });
    }

    @Override
    public Resource getResource(String name) {
        return child(new File(file, name));
    }

    @Override
    public Resource getDirectory(String name) {
        return getResource(name);
    }

    private GitResource child(File child) {
        return new GitResource(readonly(), child, rootPath, gitRepo);
    }

    @Override
    public List<Resource> resources() {
        return getWorkspaceCoordinator().read(() -> {
            Path directory = file.toPath();
            if (Files.notExists(directory) || Files.isRegularFile(directory)) {
                return Collections.emptyList();
            }
            List<Resource> resources = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) {
                    if (!isGitMetadata(entry.toFile())) {
                        resources.add(child(entry.toFile()));
                    }
                }
                return resources;
            } catch (IOException | DirectoryIteratorException failure) {
                throw new GitSyncException(GitSyncError.LOCAL_IO_ERROR);
            }
        });
    }

    @Override
    public Resource parent() {
        return rootPath.equals(file.getAbsolutePath()) ? null : child(file.getParentFile());
    }

    @Override
    public List<Resource> dirs() {
        return scan(true, null);
    }

    @Override
    public List<Resource> files(String suffix) {
        return scan(false, suffix);
    }

    private List<Resource> scan(boolean directories, String suffix) {
        return getWorkspaceCoordinator().read(() -> {
            Path start = file.toPath();
            List<Resource> resources = new ArrayList<>();
            if (Files.notExists(start)) {
                return resources;
            }
            try {
                // File.listFiles 读取失败只返回 null，不能将其当作空目录发布。
                Files.walkFileTree(start, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                        new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        return isGitMetadata(directory.toFile()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                        if (!directories && !isGitMetadata(path.toFile())
                                && (path.equals(start) || path.getFileName().toString().endsWith(suffix))) {
                            resources.add(child(path.toFile()));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                        if (failure != null) {
                            throw failure;
                        }
                        if (directories && !directory.equals(start) && !isGitMetadata(directory.toFile())) {
                            resources.add(child(directory.toFile()));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
                return resources;
            } catch (IOException failure) {
                throw new GitSyncException(GitSyncError.LOCAL_IO_ERROR);
            }
        });
    }

    private boolean isGitMetadata(File candidate) {
        return candidate.toPath().toAbsolutePath().normalize().startsWith(Paths.get(rootPath, ".git").toAbsolutePath().normalize());
    }

    @Override
    public String toString() {
        return "Git 资源工作区";
    }

    @Override
    public void close() {
        gitRepo.close();
    }

    private interface FileAction {
        boolean run() throws IOException;
    }
}
