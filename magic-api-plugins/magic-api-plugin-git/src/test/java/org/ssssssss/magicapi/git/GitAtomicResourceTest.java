package org.ssssssss.magicapi.git;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.ssssssss.magicapi.core.resource.Resource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class GitAtomicResourceTest {
    @TempDir Path directory;

    @Test
    void atomicWriteParticipatesInTheExistingCheckpointAndSync() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            long generation = workspace.repo.snapshot(true).getLocalGeneration();
            Resource resource = workspace.resource.getResource("完整 原子内容.txt");
            byte[] bytes = "\ufeffVue 原文\r\n\r\n".getBytes(StandardCharsets.UTF_8);
            assertTrue(resource.writeAtomic(bytes));
            assertTrue(workspace.repo.snapshot(true).getLocalGeneration() > generation);
            assertArrayEquals(bytes, Files.readAllBytes(workspace.localPath.resolve(resource.name())));
            workspace.service.refresh();
            ObjectId tree = workspace.bare.getRepository().resolve("refs/heads/main^{tree}");
            try (TreeWalk walk = TreeWalk.forPath(workspace.bare.getRepository(), resource.name(), tree)) {
                assertNotNull(walk);
                assertArrayEquals(bytes, workspace.bare.getRepository().open(walk.getObjectId(0)).getBytes());
            }
            assertFalse(workspace.repo.getCoordinator().isInconsistent());
        }
    }
}
