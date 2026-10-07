// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.bazel.repository.cache;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.Collections2;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.FileSystemLock;
import com.google.devtools.build.lib.util.FileSystemLock.LockMode;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link LocalRepoContentsCache}. */
@RunWith(JUnit4.class)
public final class LocalRepoContentsCacheTest {
  @Test
  public void gc_releasesLockBetweenEntries() throws Exception {
    var root = TestUtils.createUniqueTmpDir(null).asFragment();
    var sharedLocksAcquired = new AtomicInteger();
    var fs =
        new JavaIoFileSystem(DigestHashFunction.SHA256) {
          @Override
          public Collection<Dirent> readdir(PathFragment path, boolean followSymlinks)
              throws IOException {
            var entries = super.readdir(path, followSymlinks);
            if (!path.equals(root)) {
              return entries;
            }
            return Collections2.transform(
                entries,
                entry -> {
                  if (entry.getType() == Dirent.Type.DIRECTORY
                      && !entry.getName().equals(LocalRepoContentsCache.TRASH_PATH)) {
                    try (var lock =
                        FileSystemLock.tryGet(
                            getPath(root).getRelative(LocalRepoContentsCache.LOCK_PATH),
                            LockMode.SHARED)) {
                      sharedLocksAcquired.incrementAndGet();
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  }
                  return entry;
                });
          }
        };
    var cachePath = fs.getPath(root);
    for (String hash : new String[] {"first", "second"}) {
      var entry = cachePath.getRelative(hash);
      entry.getRelative("repo").createDirectoryAndParents();
      var inputs = entry.getRelative("repo.recorded_inputs");
      FileSystemUtils.writeContentAsLatin1(inputs, hash);
      inputs.setLastModifiedTime(1);
    }
    var cache = new LocalRepoContentsCache();
    cache.setPath(cachePath);

    cache.createGcIdleTask(Duration.ofMillis(1), 0, Duration.ZERO).run();

    assertThat(sharedLocksAcquired.get()).isEqualTo(2);
    for (String hash : new String[] {"first", "second"}) {
      assertThat(cachePath.getRelative(hash).getDirectoryEntries()).isEmpty();
    }
    assertThat(cachePath.getRelative(LocalRepoContentsCache.TRASH_PATH).getDirectoryEntries())
        .isEmpty();
  }

  @Test
  public void gc_maxSize_evictsLeastRecentlyUsedEntriesFirst() throws Exception {
    var cachePath = createCachePath();
    long now = System.currentTimeMillis();
    var oldest = createEntry(cachePath, "hash1", "a", 100, now - 3000);
    var middle = createEntry(cachePath, "hash2", "b", 100, now - 2000);
    var newest = createEntry(cachePath, "hash1", "c", 100, now - 1000);
    var cache = new LocalRepoContentsCache();
    cache.setPath(cachePath);

    cache.createGcIdleTask(Duration.ZERO, 200, Duration.ZERO).run();

    assertThat(oldest.exists()).isFalse();
    assertThat(cachePath.getRelative("hash1/a.recorded_inputs").exists()).isFalse();
    assertThat(middle.exists()).isTrue();
    assertThat(newest.exists()).isTrue();
    assertThat(cachePath.getRelative(LocalRepoContentsCache.TRASH_PATH).getDirectoryEntries())
        .isEmpty();
  }

  @Test
  public void gc_maxSizeZero_doesNotEvictBySize() throws Exception {
    var cachePath = createCachePath();
    long now = System.currentTimeMillis();
    var first = createEntry(cachePath, "hash1", "a", 100, now - 2000);
    var second = createEntry(cachePath, "hash2", "b", 100, now - 1000);
    var cache = new LocalRepoContentsCache();
    cache.setPath(cachePath);

    cache.createGcIdleTask(Duration.ZERO, 0, Duration.ZERO).run();

    assertThat(first.exists()).isTrue();
    assertThat(second.exists()).isTrue();
  }

  @Test
  public void gc_maxSizeAndMaxAge_appliesBothCriteria() throws Exception {
    var cachePath = createCachePath();
    long now = System.currentTimeMillis();
    var expired = createEntry(cachePath, "hash1", "a", 1, now - Duration.ofDays(2).toMillis());
    var older = createEntry(cachePath, "hash2", "b", 100, now - 2000);
    var newer = createEntry(cachePath, "hash3", "c", 100, now - 1000);
    var cache = new LocalRepoContentsCache();
    cache.setPath(cachePath);

    cache.createGcIdleTask(Duration.ofDays(1), 150, Duration.ZERO).run();

    assertThat(expired.exists()).isFalse();
    assertThat(older.exists()).isFalse();
    assertThat(newer.exists()).isTrue();
  }

  @Test
  public void gc_maxSize_doesNotCountSymlinkTargets() throws Exception {
    var cachePath = createCachePath();
    long now = System.currentTimeMillis();
    var outside = cachePath.getParentDirectory().getRelative(cachePath.getBaseName() + "_large");
    FileSystemUtils.writeContent(outside, new byte[1000]);
    var first = createEntry(cachePath, "hash1", "a", 10, now - 2000);
    first.getRelative("link").createSymbolicLink(outside);
    var second = createEntry(cachePath, "hash2", "b", 10, now - 1000);
    var cache = new LocalRepoContentsCache();
    cache.setPath(cachePath);

    cache.createGcIdleTask(Duration.ZERO, 100, Duration.ZERO).run();

    assertThat(first.exists()).isTrue();
    assertThat(second.exists()).isTrue();
  }

  @Test
  public void gc_maxSize_keepsEntriesUsedDuringGc() throws Exception {
    var cachePath = createCachePath();
    long now = System.currentTimeMillis();
    var oldest = createEntry(cachePath, "hash1", "a", 100, now - 3000);
    var middle = createEntry(cachePath, "hash2", "b", 100, now - 2000);
    var newest = createEntry(cachePath, "hash3", "c", 100, now - 1000);
    // Simulate another server using the oldest entry while GC computes its size.
    var fs =
        new JavaIoFileSystem(DigestHashFunction.SHA256) {
          @Override
          public Collection<Dirent> readdir(PathFragment path, boolean followSymlinks)
              throws IOException {
            if (path.equals(oldest.asFragment())) {
              getPath(path.replaceName("a.recorded_inputs")).setLastModifiedTime(now);
            }
            return super.readdir(path, followSymlinks);
          }
        };
    var cache = new LocalRepoContentsCache();
    cache.setPath(fs.getPath(cachePath.asFragment()));

    cache.createGcIdleTask(Duration.ZERO, 200, Duration.ZERO).run();

    assertThat(oldest.exists()).isTrue();
    assertThat(middle.exists()).isFalse();
    assertThat(newest.exists()).isTrue();
  }

  private static Path createCachePath() throws IOException {
    var fs = new JavaIoFileSystem(DigestHashFunction.SHA256);
    return fs.getPath(TestUtils.createUniqueTmpDir(null).asFragment());
  }

  /**
   * Creates a cached repo with a {@code size}-byte file as its contents and a recorded inputs file
   * last modified at {@code mtime}, returning its contents directory.
   */
  private static Path createEntry(Path cachePath, String hash, String name, int size, long mtime)
      throws IOException {
    var contentsDir = cachePath.getRelative(hash).getRelative(name);
    contentsDir.createDirectoryAndParents();
    FileSystemUtils.writeContent(contentsDir.getRelative("file"), new byte[size]);
    var recordedInputs = cachePath.getRelative(hash).getRelative(name + ".recorded_inputs");
    FileSystemUtils.writeContentAsLatin1(recordedInputs, name);
    recordedInputs.setLastModifiedTime(mtime);
    return contentsDir;
  }
}
