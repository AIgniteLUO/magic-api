package org.ssssssss.magicapi.core.resource;

import org.ssssssss.magicapi.utils.IoUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 文件存储实现
 *
 * @author mxd
 */
public class FileResource implements Resource {

	private final boolean readonly;
	protected File file;
	protected String rootPath;

	public FileResource(File file, boolean readonly, String rootPath) {
		this.file = file;
		this.readonly = readonly;
		this.rootPath = rootPath;
	}

	@Override
	public boolean readonly() {
		return this.readonly;
	}

	@Override
	public boolean exists() {
		return this.file.exists();
	}

	@Override
	public boolean delete() {
		return !readonly() && IoUtils.delete(this.file);
	}

	@Override
	public boolean isDirectory() {
		return this.file.isDirectory();
	}

	@Override
	public boolean mkdir() {
		return !readonly() && this.file.mkdirs();
	}

	@Override
	public byte[] read() {
		return IoUtils.bytes(this.file);
	}

	@Override
	public boolean renameTo(Resource resource) {
		if (!this.readonly()) {
			File target = ((FileResource) resource).file;
			if (this.file.renameTo(target)) {
				this.file = target;
				return true;
			}
		}
		return false;
	}

	@Override
	public Resource getResource(String name) {
		return new FileResource(new File(this.file, name), this.readonly, this.rootPath);
	}

	@Override
	public String name() {
		return this.file.getName();
	}

	@Override
	public List<Resource> resources() {
		File[] files = this.file.listFiles();
		return files == null ? Collections.emptyList() : Arrays.stream(files).map(it -> new FileResource(it, this.readonly, this.rootPath)).collect(Collectors.toList());
	}

	@Override
	public Resource parent() {
		return this.rootPath.equals(this.file.getAbsolutePath()) ? null : new FileResource(this.file.getParentFile(), this.readonly, this.rootPath);
	}

	@Override
	public List<Resource> dirs() {
		return IoUtils.dirs(this.file).stream().map(it -> new FileResource(it, this.readonly, this.rootPath)).collect(Collectors.toList());
	}

	@Override
	public boolean write(byte[] bytes) {
		return !readonly() && IoUtils.write(this.file, bytes);
	}

	@Override
	public boolean writeAtomic(byte[] bytes) {
		if (readonly()) return false;
		try {
			writeAtomicFile(bytes);
			return true;
		} catch (IOException failure) {
			throw new UncheckedIOException("资源原子写入失败", failure);
		}
	}

	/** Git 等文件存储复用写入过程，并保留各自的变更协调边界。 */
	protected void writeAtomicFile(byte[] bytes) throws IOException {
		Path target = file.toPath().toAbsolutePath().normalize();
		Path root = new File(rootPath).toPath().toAbsolutePath().normalize();
		Path parent = target.getParent();
		if (!target.startsWith(root) || parent == null || !parent.toRealPath().startsWith(root.toRealPath())) {
			throw new IOException("原子写入不能越出资源目录");
		}
		Path current = root;
		for (Path segment : root.relativize(target)) {
			current = current.resolve(segment);
			if (Files.isSymbolicLink(current)) throw new IOException("原子写入不能经过符号链接");
		}
		// 临时文件与目标同卷，使用非资源后缀，未完成文件不会进入运行映射。
		Path staged = Files.createTempFile(parent, ".magic-api-atomic-", ".tmp");
		try {
			try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) channel.write(buffer);
				channel.force(true);
			}
			replaceAtomically(staged, target);
		} finally {
			Files.deleteIfExists(staged);
		}
	}

	/** 不支持 ATOMIC_MOVE 的文件系统必须抛错，不尝试非原子的复制或覆盖。 */
	protected void replaceAtomically(Path staged, Path target) throws IOException {
		Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	@Override
	public boolean write(String content) {
		return !readonly() && IoUtils.write(this.file, content);
	}

	@Override
	public List<Resource> files(String suffix) {
		return IoUtils.files(this.file, suffix).stream().map(it -> new FileResource(it, this.readonly, this.rootPath)).collect(Collectors.toList());
	}

	@Override
	public String getAbsolutePath() {
		return this.file.getAbsolutePath();
	}

	@Override
	public String toString() {
		return String.format("file://%s", this.file.getAbsolutePath());
	}

	@Override
	public void processExport(ZipOutputStream zos, String path, Resource directory, List<Resource> resources, List<String> excludes) throws IOException {
		for (Resource resource : resources) {
			if (resource.parent().getAbsolutePath().equals(directory.getAbsolutePath()) && !excludes.contains(resource.name())) {
				if (resource.isDirectory()) {
					String newPath = path + resource.name() + "/";
					zos.putNextEntry(new ZipEntry(newPath));
					zos.closeEntry();
					processExport(zos, newPath, resource, resource.resources(), excludes);
				} else {
					zos.putNextEntry(new ZipEntry(path + resource.name()));
					zos.write(resource.read());
					zos.closeEntry();
				}
			}
		}
	}

	@Override
	public String getFilePath() {
		Resource parent = parent();
		while (parent.parent() != null) {
			parent = parent.parent();
		}
		String path = this.getAbsolutePath()
				.substring(parent.getAbsolutePath().length())
				.replace("\\", "/");
		if (isDirectory() && !path.endsWith("/")) {
			path += "/";
		}
		return path.startsWith("/") ? path.substring(1) : path;
	}
}
