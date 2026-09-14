package org.ssssssss.magicapi.core.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

@Timeout(60)
class AtomicFileResourceTest {
    @TempDir Path directory;

    @Test
    void readersSeeOldFileUntilCompleteReplacementAndBytesRoundTrip() throws Exception {
        Path target = directory.resolve("中文 源码.head");
        byte[] old = "old\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] next = new byte[1024 * 1024 + 3];
        next[0] = (byte) 0xef; next[1] = (byte) 0xbb; next[2] = (byte) 0xbf;
        Files.write(target, old);
        CountDownLatch staged = new CountDownLatch(1);
        CountDownLatch replace = new CountDownLatch(1);
        FileResource resource = new FileResource(target.toFile(), false, directory.toString()) {
            @Override protected void replaceAtomically(Path pending, Path destination) throws IOException {
                assertArrayEquals(next, Files.readAllBytes(pending));
                staged.countDown();
                try {
                    if (!replace.await(5, TimeUnit.SECONDS)) throw new IOException("未解除测试闸门");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                super.replaceAtomically(pending, destination);
            }
        };
        ExecutorService writer = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = writer.submit(() -> resource.writeAtomic(next));
            assertTrue(staged.await(5, TimeUnit.SECONDS));
            assertArrayEquals(old, Files.readAllBytes(target));
            replace.countDown();
            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertArrayEquals(next, Files.readAllBytes(target));
        } finally {
            replace.countDown();
            writer.shutdownNow();
            assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertOnlyTargetRemains();
        FileResource plain = new FileResource(target.toFile(), false, directory.toString());
        assertTrue(plain.writeAtomic(new byte[0]));
        assertEquals(0, Files.size(target));
    }

    @Test
    void unsupportedAtomicMoveDoesNotFallBackOrDamageOldContent() throws Exception {
        Path target = directory.resolve("project.head");
        byte[] old = "original".getBytes(StandardCharsets.UTF_8);
        Files.write(target, old);
        FileResource resource = new FileResource(target.toFile(), false, directory.toString()) {
            @Override protected void replaceAtomically(Path pending, Path destination) throws IOException {
                throw new AtomicMoveNotSupportedException(pending.toString(), destination.toString(), "测试不支持");
            }
        };
        UncheckedIOException error = assertThrows(UncheckedIOException.class,
                () -> resource.writeAtomic("new".getBytes(StandardCharsets.UTF_8)));
        assertTrue(error.getCause() instanceof AtomicMoveNotSupportedException);
        assertArrayEquals(old, Files.readAllBytes(target));
        assertOnlyTargetRemains();
    }

    @Test
    void readonlyAndUnsupportedResourceCannotPretendAtomicSuccess() throws Exception {
        Path target = directory.resolve("project.head");
        byte[] old = new byte[] {1, 2, 3};
        Files.write(target, old);
        assertFalse(new FileResource(target.toFile(), true, directory.toString()).writeAtomic(new byte[] {4}));
        assertArrayEquals(old, Files.readAllBytes(target));
        Resource unsupported = mock(Resource.class, CALLS_REAL_METHODS);
        assertThrows(UnsupportedOperationException.class, () -> unsupported.writeAtomic(old));
    }

    @Test
    void symbolicTargetIsRejectedWithoutChangingItsReferent() throws Exception {
        Path actual = directory.resolve("actual.head");
        Files.write(actual, new byte[] {7, 8});
        Path link = directory.resolve("link.head");
        Files.createSymbolicLink(link, actual.getFileName());
        FileResource resource = new FileResource(link.toFile(), false, directory.toString());
        assertThrows(UncheckedIOException.class, () -> resource.writeAtomic(new byte[] {9}));
        assertArrayEquals(new byte[] {7, 8}, Files.readAllBytes(actual));
        assertTrue(Files.isSymbolicLink(link));
    }

    private void assertOnlyTargetRemains() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(1, files.count(), "成功或失败均不得留下会被导出的临时文件");
        }
    }
}
