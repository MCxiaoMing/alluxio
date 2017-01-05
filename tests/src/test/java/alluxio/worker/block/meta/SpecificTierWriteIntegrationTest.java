/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.worker.block.meta;

import alluxio.AlluxioURI;
import alluxio.Constants;
import alluxio.LocalAlluxioClusterResource;
import alluxio.PropertyKey;
import alluxio.PropertyKeyFormat;
import alluxio.client.WriteType;
import alluxio.client.file.FileOutStream;
import alluxio.client.file.FileSystem;
import alluxio.client.file.URIStatus;
import alluxio.client.file.options.CreateFileOptions;
import alluxio.client.file.options.DeleteOptions;
import alluxio.client.file.policy.LocalFirstPolicy;
import alluxio.heartbeat.HeartbeatContext;
import alluxio.heartbeat.HeartbeatScheduler;
import alluxio.heartbeat.ManuallyScheduleHeartbeat;
import alluxio.util.CommonUtils;
import alluxio.util.io.BufferUtils;

import com.google.common.io.Files;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class SpecificTierWriteIntegrationTest {
  private static final int CAPACITY_BYTES = Constants.KB;
  private static final int FILE_SIZE = CAPACITY_BYTES;
  private static final String BLOCK_SIZE_BYTES = "1KB";

  @Rule
  public LocalAlluxioClusterResource mLocalAlluxioClusterResource =
      new LocalAlluxioClusterResource.Builder()
          .setProperty(PropertyKey.USER_BLOCK_SIZE_BYTES_DEFAULT, BLOCK_SIZE_BYTES)
          .setProperty(PropertyKey.USER_FILE_BUFFER_BYTES, BLOCK_SIZE_BYTES)
          .setProperty(PropertyKey.WORKER_MEMORY_SIZE, CAPACITY_BYTES)
          .setProperty(PropertyKey.WORKER_TIERED_STORE_LEVELS, "3")
          .setProperty(PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_ALIAS_FORMAT.format(1), "SSD")
          .setProperty(PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_ALIAS_FORMAT.format(2), "HDD")
          .setProperty(PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_DIRS_PATH_FORMAT.format(0),
              Files.createTempDir().getAbsolutePath())
          .setProperty(PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_DIRS_PATH_FORMAT.format(1),
              Files.createTempDir().getAbsolutePath())
          .setProperty(PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_DIRS_QUOTA_FORMAT.format(1),
              String.valueOf(CAPACITY_BYTES))
          .setProperty(PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_DIRS_PATH_FORMAT.format(2),
              Files.createTempDir().getAbsolutePath())
          .setProperty(PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_DIRS_QUOTA_FORMAT.format(2),
              String.valueOf(CAPACITY_BYTES)).build();

  @ClassRule
  public static ManuallyScheduleHeartbeat sManuallySchedule =
      new ManuallyScheduleHeartbeat(HeartbeatContext.WORKER_BLOCK_SYNC);

  private FileSystem mFileSystem = null;

  @Before
  public final void before() throws Exception {
    mFileSystem = mLocalAlluxioClusterResource.get().getClient();
  }

  private void writeFileAndCheckUsage(int writeTier, long memBytes, long ssdBytes, long hddBytes)
      throws Exception {
    FileOutStream os = mFileSystem.createFile(
        new AlluxioURI("/tier-" + writeTier + "_" + CommonUtils.randomAlphaNumString(5)),
        CreateFileOptions.defaults().setWriteTier(writeTier).setWriteType(WriteType.MUST_CACHE)
            .setLocationPolicy(new LocalFirstPolicy()));
    os.write(BufferUtils.getIncreasingByteArray(FILE_SIZE));
    os.close();

    HeartbeatScheduler.execute(HeartbeatContext.WORKER_BLOCK_SYNC);

    long totalBytes = memBytes + ssdBytes + hddBytes;
    Assert.assertEquals("Total bytes used", totalBytes,
        mLocalAlluxioClusterResource.get().getMaster().getInternalMaster().getBlockMaster()
            .getUsedBytes());

    Map<String, Long> bytesOnTiers =
        mLocalAlluxioClusterResource.get().getMaster().getInternalMaster().getBlockMaster()
            .getUsedBytesOnTiers();
    Assert.assertEquals("MEM tier usage", memBytes, bytesOnTiers.get("MEM").longValue());
    Assert.assertEquals("SSD tier usage", ssdBytes, bytesOnTiers.get("SSD").longValue());
    Assert.assertEquals("HDD tier usage", hddBytes, bytesOnTiers.get("HDD").longValue());

  }

  private void deleteAllFiles() throws Exception {
    List<URIStatus> files = mFileSystem.listStatus(new AlluxioURI("/"));
    for (URIStatus file : files) {
      mFileSystem
          .delete(new AlluxioURI(file.getPath()), DeleteOptions.defaults().setRecursive(true));
    }
    // Trigger a worker heartbeat to delete the blocks.
    HeartbeatScheduler.execute(HeartbeatContext.WORKER_BLOCK_SYNC);
  }

  @Test
  public void topTierWrite() throws Exception {
    writeFileAndCheckUsage(0, FILE_SIZE, 0, 0);
    deleteAllFiles();
    writeFileAndCheckUsage(-3, FILE_SIZE, 0, 0);
    deleteAllFiles();
    writeFileAndCheckUsage(-4, FILE_SIZE, 0, 0);
  }

  @Test
  public void midTierWrite() throws Exception {
    writeFileAndCheckUsage(1, 0, FILE_SIZE, 0);
    deleteAllFiles();
    writeFileAndCheckUsage(-2, 0, FILE_SIZE, 0);
  }

  @Test
  public void bottomTierWrite() throws Exception {
    writeFileAndCheckUsage(2, 0, 0, FILE_SIZE);
    deleteAllFiles();
    writeFileAndCheckUsage(3, 0, 0, FILE_SIZE);
    deleteAllFiles();
    writeFileAndCheckUsage(-1, 0, 0, FILE_SIZE);
  }

  @Test
  public void allTierWrite() throws Exception {
    writeFileAndCheckUsage(0, FILE_SIZE, 0, 0);
    writeFileAndCheckUsage(1, FILE_SIZE, FILE_SIZE, 0);
    writeFileAndCheckUsage(2, FILE_SIZE, FILE_SIZE, FILE_SIZE);
    deleteAllFiles();
    writeFileAndCheckUsage(-1, 0, 0, FILE_SIZE);
    writeFileAndCheckUsage(-2, 0, FILE_SIZE, FILE_SIZE);
    writeFileAndCheckUsage(-3, FILE_SIZE, FILE_SIZE, FILE_SIZE);
  }

  @Test
  public void topTierWriteWithEviction() throws Exception {
    writeFileAndCheckUsage(0, FILE_SIZE, 0, 0);
    writeFileAndCheckUsage(0, FILE_SIZE, FILE_SIZE, 0);
    writeFileAndCheckUsage(0, FILE_SIZE, FILE_SIZE, FILE_SIZE);
    writeFileAndCheckUsage(0, FILE_SIZE, FILE_SIZE, FILE_SIZE);
  }

  @Test
  public void midTierWriteWithEviction() throws Exception {
    writeFileAndCheckUsage(1, 0, FILE_SIZE, 0);
    writeFileAndCheckUsage(1, 0, FILE_SIZE, FILE_SIZE);
    writeFileAndCheckUsage(1, 0, FILE_SIZE, FILE_SIZE);
  }

  @Test
  public void bottomTierWriteWithEviction() throws Exception {
    writeFileAndCheckUsage(2, 0, 0, FILE_SIZE);
    writeFileAndCheckUsage(2, 0, 0, FILE_SIZE);
    writeFileAndCheckUsage(2, 0, 0, FILE_SIZE);
  }

  @Test
  public void allTierWriteWithEviction() throws Exception {
    writeFileAndCheckUsage(0, FILE_SIZE, 0, 0);
    writeFileAndCheckUsage(1, FILE_SIZE, FILE_SIZE, 0);
    writeFileAndCheckUsage(2, FILE_SIZE, FILE_SIZE, FILE_SIZE);
    writeFileAndCheckUsage(-1, FILE_SIZE, FILE_SIZE, FILE_SIZE);
    writeFileAndCheckUsage(-2, FILE_SIZE, FILE_SIZE, FILE_SIZE);
    writeFileAndCheckUsage(-3, FILE_SIZE, FILE_SIZE, FILE_SIZE);
  }
}
