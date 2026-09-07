package org.ssssssss.magicapi.core.service;

import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.Collection;
import java.util.Collections;

/** 整笔资源变更、本地整合和缓存发布共用的可重入边界；锁内不得等待网络或后台任务。 */
public final class WorkspaceResourceCoordinator {
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
	private volatile boolean inconsistent;
	private long mutationVersion;
	private Runnable inconsistencyListener;
	private Runnable localRefresh;
	private Supplier<Collection<String>> resourcePaths = Collections::emptyList;

	public <T> T read(Supplier<T> action) {
		lock.readLock().lock();
		try {
			return action.get();
		} finally {
			lock.readLock().unlock();
		}
	}

	public <T> T write(Supplier<T> action) {
		lock.writeLock().lock();
		long before = mutationVersion;
		try {
			assertConsistent();
			T result = action.get();
			if (Boolean.FALSE.equals(result) && before != mutationVersion) {
				markInconsistent();
				assertConsistent();
			}
			return result;
		} catch (RuntimeException | Error failure) {
			// 校验失败尚未修改磁盘，不冻结；部分落盘后的失败必须显性阻断后续写入。
			if (before != mutationVersion) {
				markInconsistent();
			}
			throw failure;
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void recordMutation() {
		requireWriteLock();
		mutationVersion++;
	}

	public void markInconsistent() {
		requireWriteLock();
		if (!inconsistent) {
			inconsistent = true;
			if (inconsistencyListener != null) {
				inconsistencyListener.run();
			}
		}
	}

	public boolean isInconsistent() {
		return inconsistent;
	}

	public void assertConsistent() {
		if (inconsistent) {
			throw new InvalidArgumentException(JsonCodeConstants.WORKSPACE_INCONSISTENT);
		}
	}

	public void assertCanWait() {
		if (lock.isWriteLockedByCurrentThread() || lock.getReadHoldCount() > 0) {
			throw new IllegalStateException("不能持资源锁等待同步任务");
		}
	}

	public void onInconsistent(Runnable listener) {
		this.inconsistencyListener = listener;
	}

	public void setLocalRefresh(Runnable localRefresh) {
		this.localRefresh = localRefresh;
	}

	public void setResourcePaths(Supplier<Collection<String>> resourcePaths) {
		this.resourcePaths = resourcePaths;
	}

	/** 供存储验证已保存资源确实包含在本地 checkpoint 中，路径相对工作区。 */
	public Collection<String> getResourcePaths() {
		requireWriteLock();
		return resourcePaths.get();
	}

	public void refreshLocal() {
		requireWriteLock();
		assertConsistent();
		if (localRefresh == null) {
			throw new IllegalStateException("资源缓存刷新尚未就绪");
		}
		localRefresh.run();
	}

	/** 管理员完成磁盘修复后显式核验；普通重试不能进入这个恢复入口。 */
	public void recover(Runnable verify, Runnable complete) {
		lock.writeLock().lock();
		try {
			verify.run();
			inconsistent = false;
			try {
				refreshLocal();
				complete.run();
			} catch (RuntimeException failure) {
				markInconsistent();
				throw failure;
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void requireWriteLock() {
		if (!lock.isWriteLockedByCurrentThread()) {
			throw new IllegalStateException("此操作必须持有资源协调写锁");
		}
	}
}
