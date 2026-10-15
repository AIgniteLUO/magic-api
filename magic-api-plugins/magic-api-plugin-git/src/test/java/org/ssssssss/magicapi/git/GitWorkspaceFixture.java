package org.ssssssss.magicapi.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.ssssssss.magicapi.core.event.EventAction;
import org.ssssssss.magicapi.core.event.FileEvent;
import org.ssssssss.magicapi.core.event.GroupEvent;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.service.impl.RequestMagicDynamicRegistry;
import org.ssssssss.magicapi.core.web.RequestHandler;
import org.ssssssss.magicapi.utils.Mapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.ssssssss.magicapi.core.event.MagicEvent;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.servlet.MagicRequestContextHolder;
import org.ssssssss.magicapi.utils.WebUtils;
import org.ssssssss.magicapi.core.resource.FileResource;
import org.ssssssss.magicapi.core.resource.Resource;
import org.ssssssss.magicapi.core.service.impl.ApiInfoMagicResourceStorage;
import org.ssssssss.magicapi.core.service.impl.DefaultMagicResourceService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

final class GitWorkspaceFixture implements AutoCloseable {
    final MagicRequestContextHolder previousContext;
    final Path localPath;
    final Path otherPath;
    final Git bare;
    final Git other;
    final MagicGitProperties properties;
    final ControlledRepo repo;
    final GitResource resource;
    final DefaultMagicResourceService service;
    final Mapping routes;
    final RequestMagicDynamicRegistry registry;
    final DefaultMagicResourceService otherService;
    final ExecutorService clients = Executors.newCachedThreadPool();
    final AtomicInteger clears = new AtomicInteger();
    final List<MagicEvent> events = new CopyOnWriteArrayList<>();
    volatile EventAction failPublication;

    GitWorkspaceFixture(Path directory) throws Exception {
        previousContext = WebUtils.magicRequestContextHolder;
        WebUtils.magicRequestContextHolder = mock(MagicRequestContextHolder.class);
        localPath = directory.resolve("local");
        otherPath = directory.resolve("other");
        Path remotePath = directory.resolve("remote.git");
        bare = Git.init().setBare(true).setDirectory(remotePath.toFile()).call();
        other = Git.init().setInitialBranch("main").setDirectory(otherPath.toFile()).call();
        otherService = service(new FileResource(otherPath.toFile(), false, otherPath.toString()), new AtomicInteger());
        Group group = new Group("g", "Orders");
        group.setType("api");
        group.setParentId("0");
        group.setPath("orders");
        assertTrue(otherService.saveGroup(group));
        assertTrue(otherService.saveFile(api("a", "First", "first", "return 1;")));
        assertTrue(otherService.saveFile(api("b", "Second", "second", "return 2;")));
        other.getRepository().getConfig().setString("remote", "origin", "url", remotePath.toUri().toString());
        other.getRepository().getConfig().setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
        other.getRepository().getConfig().save();
        commitOther();
        try (Git clone = Git.cloneRepository().setURI(remotePath.toUri().toString()).setBranch("main")
                .setDirectory(localPath.toFile()).call()) {
            clone.getRepository().getConfig().setString("user", null, "name", "Test");
            clone.getRepository().getConfig().setString("user", null, "email", "test@example.invalid");
            clone.getRepository().getConfig().save();
        }
        properties = new MagicGitProperties();
        properties.setBranch("main");
        properties.setUrl(remotePath.toUri().toString());
        repo = new ControlledRepo(localPath.toString(), properties);
        repo.setupRepo();
        resource = new GitResource(false, localPath.toFile(), localPath.toString(), repo);
        routes = Mapping.create(new RequestMappingHandlerMapping());
        ApiInfoMagicResourceStorage storage = new ApiInfoMagicResourceStorage("");
        registry = new RequestMagicDynamicRegistry(storage, routes, false, "");
        registry.setHandler(new RequestHandler(new MagicConfiguration(), registry));
        service = new DefaultMagicResourceService(resource, Collections.singletonList(storage), event -> {
            if (event instanceof MagicEvent) {
                events.add((MagicEvent) event);
            }
            if (event instanceof MagicEvent && ((MagicEvent) event).getAction() == failPublication) {
                throw new IllegalStateException("模拟运行缓存发布失败");
            }
            if (event instanceof MagicEvent && ((MagicEvent) event).getAction() == EventAction.CLEAR) {
                registry.clear((MagicEvent) event);
                clears.incrementAndGet();
            } else if (event instanceof FileEvent) {
                registry.onFileEvent((FileEvent) event);
            } else if (event instanceof GroupEvent) {
                registry.onGroupEvent((GroupEvent) event);
            }
        });
        service.refreshLocal();
        clears.set(0);
        events.clear();
    }

    static DefaultMagicResourceService service(Resource resource, AtomicInteger clears) {
        DefaultMagicResourceService service = new DefaultMagicResourceService(resource,
                Collections.singletonList(new ApiInfoMagicResourceStorage("")), event -> {
            if (event instanceof MagicEvent && ((MagicEvent) event).getAction() == EventAction.CLEAR) {
                clears.incrementAndGet();
            }
        });
        service.refreshLocal();
        return service;
    }

    static ApiInfo api(String id, String name, String path, String script) {
        ApiInfo api = new ApiInfo();
        api.setId(id);
        api.setName(name);
        api.setGroupId("g");
        api.setPath(path);
        api.setScript(script);
        return api;
    }

    void save(DefaultMagicResourceService target, String id, String script) {
        ApiInfo updated = ((ApiInfo) target.file(id)).copy();
        updated.setScript(script);
        assertTrue(target.saveFile(updated));
    }

    void commitOther() throws Exception {
        other.add().addFilepattern(".").call();
        other.add().setUpdate(true).addFilepattern(".").call();
        other.commit().setMessage("其他工作区保存").setAuthor("Test", "test@example.invalid")
                .setCommitter("Test", "test@example.invalid").call();
        other.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main")).call();
    }

    ApiInfo remoteFile(String name) throws Exception {
        ObjectId tree = bare.getRepository().resolve("refs/heads/main^{tree}");
        try (TreeWalk walk = TreeWalk.forPath(bare.getRepository(), "api/Orders/" + name + ".ms", tree)) {
            assertNotNull(walk);
            return new ApiInfoMagicResourceStorage("").read(bare.getRepository().open(walk.getObjectId(0)).getBytes());
        }
    }

    /** 本地工作区当前 HEAD，用于记录详情与差异用例。 */
    String headCommit() throws Exception {
        try (Git git = Git.open(localPath.toFile())) {
            ObjectId head = git.getRepository().resolve(org.eclipse.jgit.lib.Constants.HEAD);
            assertNotNull(head, "本地工作区没有提交");
            return head.name();
        }
    }

    Future<?> reloadAsync() {
        return clients.submit(service::refresh);
    }

    @Override
    public void close() throws Exception {
        repo.gates.forEach(Gate::release);
        clients.shutdownNow();
        repo.close();
        clients.awaitTermination(5, TimeUnit.SECONDS);
        other.close();
        bare.close();
        WebUtils.magicRequestContextHolder = previousContext;
    }

    static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "同步没有进入预期的网络阶段");
        }

        void pause() throws TransportException {
            entered.countDown();
            try {
                if (!released.await(20, TimeUnit.SECONDS)) {
                    throw new TransportException("测试网络闸门超时");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new TransportException("测试网络等待被中断");
            }
        }

        void release() { released.countDown(); }
    }

    static class ControlledRepo extends GitRepo {
        final List<Gate> gates = new CopyOnWriteArrayList<>();
        final AtomicReference<Gate> nextFetch = new AtomicReference<>();
        final AtomicReference<Gate> nextPush = new AtomicReference<>();
        final AtomicInteger fetchCalls = new AtomicInteger();
        final AtomicInteger pushCalls = new AtomicInteger();
        volatile boolean failApply;
        volatile boolean emptyPushResult;
        volatile boolean incompletePushResult;
        volatile String transportFailure;

        ControlledRepo(String root, MagicGitProperties properties) { super(root, properties); }

        Gate blockFetch() { return block(nextFetch); }
        Gate blockPush() { return block(nextPush); }

        private Gate block(AtomicReference<Gate> phase) {
            Gate gate = new Gate();
            assertTrue(phase.compareAndSet(null, gate));
            gates.add(gate);
            return gate;
        }

        @Override
        protected FetchResult fetch() throws GitAPIException {
            fetchCalls.incrementAndGet();
            Gate gate = nextFetch.getAndSet(null);
            if (gate != null) { gate.pause(); }
            if (transportFailure != null) { throw new TransportException(transportFailure); }
            return super.fetch();
        }

        @Override
        protected Iterable<PushResult> push(ObjectId commit) throws GitAPIException {
            pushCalls.incrementAndGet();
            Gate gate = nextPush.getAndSet(null);
            if (gate != null) { gate.pause(); }
            if (emptyPushResult) { return Collections.emptyList(); }
            Iterable<PushResult> pushed = super.push(commit);
            if (!incompletePushResult) { return pushed; }
            List<PushResult> results = new ArrayList<>();
            pushed.forEach(results::add);
            results.add(new PushResult());
            return results;
        }

        @Override
        protected void applyTree(DirCacheCheckout checkout) throws IOException {
            super.applyTree(checkout);
            if (failApply) { throw new IOException("应用已经修改工作树，模拟磁盘失败"); }
        }
    }
}
