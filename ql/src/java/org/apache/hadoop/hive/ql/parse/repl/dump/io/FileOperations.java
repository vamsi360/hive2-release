/*
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package org.apache.hadoop.hive.ql.parse.repl.dump.io;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.ReplChangeManager;
import org.apache.hadoop.hive.ql.exec.CopyTask;
import org.apache.hadoop.hive.ql.exec.ReplCopyTask;
import org.apache.hadoop.hive.ql.parse.EximUtil;
import org.apache.hadoop.hive.ql.parse.LoadSemanticAnalyzer;
import org.apache.hadoop.hive.ql.parse.ReplicationSpec;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.List;

public class FileOperations {
  private static Logger logger = LoggerFactory.getLogger(FileOperations.class);
  private final Path dataFileListPath;
  private final Path exportRootDataDir;
  private final HiveConf hiveConf;
  private final FileSystem dataFileSystem, exportFileSystem;
  private final boolean forExportCommand;

  public FileOperations(Path dataFileListPath, Path exportRootDataDir, HiveConf hiveConf,
      boolean forExportCommand)
      throws IOException {
    this.dataFileListPath = dataFileListPath;
    this.exportRootDataDir = exportRootDataDir;
    this.hiveConf = hiveConf;
    dataFileSystem = dataFileListPath.getFileSystem(hiveConf);
    exportFileSystem = exportRootDataDir.getFileSystem(hiveConf);
    this.forExportCommand = forExportCommand;
  }

  public void export(ReplicationSpec forReplicationSpec) throws IOException, SemanticException {
    if (forReplicationSpec.isLazy()) {
      exportFilesAsList();
    } else {
      copyFiles();
    }
  }

  /**
   * This writes the actual data in the exportRootDataDir from the source.
   */
  private void copyFiles() throws IOException {
    FileStatus[] fileStatuses =
        LoadSemanticAnalyzer.matchFilesOrDir(dataFileSystem, dataFileListPath);
    if (forExportCommand) {
      CopyTask.doCopy(exportFileSystem, exportRootDataDir, dataFileSystem, fileStatuses,
          new SessionState.LogHelper(logger), hiveConf);
    } else {
      List<Path> paths =
          Lists.transform(Arrays.asList(fileStatuses), new Function<FileStatus, Path>() {
            @Override
            public Path apply(FileStatus fileStatus) {
              return fileStatus.getPath();
            }
          });
      ReplCopyTask.doCopy(exportRootDataDir, exportFileSystem, paths, dataFileSystem, hiveConf);
    }
  }

  /**
   * This needs the root data directory to which the data needs to be exported to.
   * The data export here is a list of files either in table/partition that are written to the _files
   * in the exportRootDataDir provided.
   */
  private void exportFilesAsList() throws SemanticException, IOException {
    try (BufferedWriter writer = writer()) {
      FileStatus[] fileStatuses =
          LoadSemanticAnalyzer.matchFilesOrDir(dataFileSystem, dataFileListPath);
      for (FileStatus fileStatus : fileStatuses) {
        writer.write(encodedUri(fileStatus));
        writer.newLine();
      }
    }
  }

  private BufferedWriter writer() throws IOException {
    Path exportToFile = new Path(exportRootDataDir, EximUtil.FILES_NAME);
    if (exportFileSystem.exists(exportToFile)) {
      throw new IllegalArgumentException(
          exportToFile.toString() + " already exists and cant export data from path(dir) "
              + dataFileListPath);
    }
    logger.debug("exporting data files in dir : " + dataFileListPath + " to " + exportToFile);
    return new BufferedWriter(
        new OutputStreamWriter(exportFileSystem.create(exportToFile))
    );
  }

  private String encodedUri(FileStatus fileStatus) throws IOException {
    Path currentDataFilePath = fileStatus.getPath();
    String checkSum = ReplChangeManager.checksumFor(currentDataFilePath, dataFileSystem);
    return ReplChangeManager.encodeFileUri(currentDataFilePath.toUri().toString(), checkSum);
  }
}
