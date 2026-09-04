package org.ssssssss.magicapi.core.resource;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.ssssssss.magicapi.utils.IoUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

/** 将压缩包解到临时目录，避免把所有文件内容缓存到堆内存。 */
public class ZipResource implements Resource, AutoCloseable {
    private final File root;
    private final File file;
    private final Resource parent;
    public ZipResource(InputStream inputStream) throws IOException {
        this.root = Files.createTempDirectory("magic-api-upload-").toFile(); this.file = root; this.parent = null;
        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(inputStream)) {
            ArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String charset = entry instanceof ZipArchiveEntry && ((ZipArchiveEntry) entry).getGeneralPurposeBit().usesUTF8ForNames() ? StandardCharsets.UTF_8.name() : "GBK";
                String name = new String(((ZipArchiveEntry) entry).getRawName(), charset).replace('\\', '/');
                Path target = root.toPath().resolve(name).normalize();
                if (!target.startsWith(root.toPath())) throw new IOException("压缩包包含非法路径: " + name);
                if (entry.isDirectory() || name.endsWith("/")) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }
    private ZipResource(File root, File file, Resource parent) { this.root = root; this.file = file; this.parent = parent; }
    private FileResource delegate() { return new FileResource(file, true, root.getAbsolutePath()); }
    @Override public boolean readonly() { return true; }
    @Override public boolean exists() { return file.exists(); }
    @Override public boolean isDirectory() { return file.isDirectory(); }
    @Override public byte[] read() { return delegate().read(); }
    @Override public String name() { return file.equals(root) ? "" : file.getName(); }
    @Override public Resource getResource(String name) { return new ZipResource(root, new File(file, name), this); }
    @Override public Resource getDirectory(String name) { return getResource(name); }
    @Override public Resource parent() { return parent; }
    @Override public List<Resource> resources() {
        File[] children = file.listFiles();
        return children == null ? Collections.emptyList() : Arrays.stream(children).map(child -> new ZipResource(root, child, this)).collect(Collectors.toList());
    }
    @Override public List<Resource> dirs() {
        return resources().stream().filter(Resource::isDirectory).collect(Collectors.toList());
    }
    @Override public List<Resource> files(String suffix) {
        return resources().stream().filter(resource -> !resource.isDirectory() && resource.name().endsWith(suffix)).collect(Collectors.toList());
    }
    @Override public String getAbsolutePath() { return file.getAbsolutePath(); }
    @Override public String getFilePath() { return delegate().getFilePath(); }
    @Override public void close() { IoUtils.delete(root); }
}
