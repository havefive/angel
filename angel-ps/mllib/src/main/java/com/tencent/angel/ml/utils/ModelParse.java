/*
 * Tencent is pleased to support the open source community by making Angel available.
 *
 * Copyright (C) 2017 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package com.tencent.angel.ml.utils;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.util.Time;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Parsing the model ,will asynchronously parse all partitions of matrices from HDFS ,the format
 * refers to MatrixCommitter
 */
public class ModelParse {
  private static final Log LOG = LogFactory.getLog(ModelParse.class);
  private ExecutorService convertTaskPool;
  private static FileSystem infs;
  private static FileSystem outfs;
  private static FileStatus[] fileStatus = null;
  private AtomicBoolean isConverting = new AtomicBoolean(false);
  private String inputStr;
  private String modelName;
  private String outputStr;
  private Path outputPath;
  private int convertThreadCount;


  public ModelParse(String inputStr, String outputStr, String modelName, int convertThreadCount) {
    this.inputStr = inputStr;
    this.outputStr = outputStr;
    this.modelName = modelName;
    this.convertThreadCount = convertThreadCount;
  }

  /**
   * The task convert matrix partition on according file.
   */
  public class ConvertTask implements Runnable {

    private FileStatus status;
    private AtomicBoolean isSuccess = new AtomicBoolean(false);
    private AtomicBoolean finishFlag = new AtomicBoolean(false);
    private String errorLog;
    private FSDataOutputStream out;

    /**
     * Create a new Convert task.
     * 
     * @param status
     * @param out
     */
    public ConvertTask(FileStatus status, FSDataOutputStream out) {
      this.status = status;
      this.out = out;
    }

    @Override
    public void run() {
      long startTime = Time.monotonicNow();
      LOG.info(String.format("open file " + status.getPath()));
      try {
        FSDataInputStream fin;
        fin = infs.open(status.getPath());
        int matrixId = fin.readInt();
        out.writeBytes("matrixId:" + matrixId + "\n");
        int partSize = fin.readInt();
        out.writeBytes("partSize:" + partSize + "\n");

        // read partition header
        int startRow = fin.readInt();
        int startCol = fin.readInt();
        int endRow = fin.readInt();
        int endCol = fin.readInt();
        String rowType = fin.readUTF();
        String patInfo = "rowType " + rowType + ", partition range is [" + startRow + ", "
            + startCol + "] to (" + endRow + ", " + endCol + ")";
        out.writeBytes(patInfo + "\n");
        LOG.info(String.format(patInfo));

        int rowNum = fin.readInt();
        LOG.info(String.format("rowNum=" + rowNum));
        out.writeBytes("rowNum:" + rowNum + "\n");
        int rowIndex;
        int rowLen;
        int clock;

        // deal with row according the rowType
        switch (rowType) {
          case "T_DOUBLE_SPARSE": {
            int key;
            double value;
            for (int j = 0; j < rowNum; j++) {
              clock = fin.readInt();
              rowIndex = fin.readInt();
              rowLen = fin.readInt();
              out.writeBytes("rowId:" + rowIndex + " clock:" + clock + " size:" + rowLen + "\n");
              for (int k = 0; k < rowLen; k++) {
                key = fin.readInt();
                value = fin.readDouble();
                out.writeBytes(key + ":" + value + " ");
                if (k != 0 && k % 10 == 0 && k != rowLen - 1)
                  out.writeBytes("\n");
              }
              out.writeBytes("\n");
            }
            break;
          }

          case "T_DOUBLE_DENSE": {
            byte[] data = new byte[8 * (endCol - startCol)];
            rowLen = endCol - startCol;
            double value;
            for (int j = 0; j < rowNum; j++) {
              rowIndex = fin.readInt();
              clock = fin.readInt();
              out.writeBytes("rowId:" + rowIndex + " clock:" + clock + " len:" + rowLen + "\n");
              fin.readFully(data, 0, data.length);
              DoubleBuffer dBuffer = ByteBuffer.wrap(data, 0, data.length).asDoubleBuffer();
              for (int k = 0; k < rowLen; k++) {
                value = dBuffer.get();
                out.writeBytes((startCol+k)+":"+value + " ");
                if (k != 0 && k % 10 == 0 && k != rowLen - 1)
                  out.writeBytes("\n");
                if (k != 0 && k % 10 == 0 && k != rowLen - 1)
                  out.writeBytes("\n");
              }
              out.writeBytes("\n");
            }
            break;
          }

          case "T_FLOAT_DENSE": {
            byte[] data = new byte[4 * (endCol - startCol)];
            rowLen = endCol - startCol;
            float value;
            for (int j = 0; j < rowNum; j++) {
              rowIndex = fin.readInt();
              clock = fin.readInt();
              out.writeBytes("rowId:" + rowIndex + " clock:" + clock + " len:" + rowLen + "\n");
              fin.readFully(data, 0, data.length);
              FloatBuffer fBuffer = ByteBuffer.wrap(data, 0, data.length).asFloatBuffer();
              for (int k = 0; k < rowLen; k++) {
                value = fBuffer.get();
                out.writeBytes((startCol+k)+":"+value + " ");
                if (k != 0 && k % 10 == 0 && k != rowLen - 1)
                  out.writeBytes("\n");
                if (k != 0 && k % 10 == 0 && k != rowLen - 1)
                  out.writeBytes("\n");
              }
              out.writeBytes("\n");
            }
            break;
          }

          case "T_INT_SPARSE": {
            int key;
            int value;
            for (int j = 0; j < rowNum; j++) {
              rowIndex = fin.readInt();
              clock = fin.readInt();
              rowLen = fin.readInt();
              out.writeBytes("rowId:" + rowIndex + " clock:" + clock + " size:" + rowLen + "\n");
              for (int k = 0; k < rowLen; k++) {
                key = fin.readInt();
                value = fin.readInt();
                out.writeBytes(key + ":" + value + " ");
                if (k != 0 && k % 10 == 0 && k != rowLen - 1)
                  out.writeBytes("\n");
              }
              out.writeBytes("\n");
            }
            break;
          }

          case "T_INT_DENSE": {
            byte[] data = new byte[4 * (endCol - startCol)];
            rowLen = endCol - startCol;
            int value;
            for (int j = 0; j < rowNum; j++) {
              rowIndex = fin.readInt();
              clock = fin.readInt();
              out.writeBytes("rowId:" + rowIndex + " clock:" + clock + " len:" + rowLen + "\n");
              fin.readFully(data, 0, data.length);
              IntBuffer iBuffer = ByteBuffer.wrap(data, 0, data.length).asIntBuffer();
              for (int k = 0; k < rowLen; k++) {
                value = iBuffer.get();
                out.writeBytes((startCol+k)+":"+value + " ");
                if (k!=0 &&k % 10 == 0 && k != rowLen - 1)
                  out.writeBytes("\n");
                if (k!=0 &&k % 10 == 0 && k != rowLen - 1)
                  out.writeBytes("\n");
              }
              out.writeBytes("\n");
            }
            break;
          }

          case "T_INT_ARBITRARY": {
            byte[] data = new byte[4 * (endCol - startCol)];
            String denseOrSparse;
            int nnz;
            int key;
            int value;
            for (int j = 0; j < rowNum; j++) {
              rowIndex = fin.readInt();
              clock = fin.readInt();
              denseOrSparse = fin.readUTF();
              if (denseOrSparse.equals("T_INT_DENSE")) {
                rowLen = endCol - startCol;
                out.writeBytes("rowId:" + rowIndex + " clock:" + clock + " len:" + rowLen + " type:"
                    + denseOrSparse + "\n");
                fin.readFully(data, 0, data.length);
                IntBuffer iBuffer = ByteBuffer.wrap(data, 0, data.length).asIntBuffer();
                for (int k = 0; k < rowLen; k++) {
                  value = iBuffer.get();
                  out.writeBytes((startCol+k)+":"+value + " ");
                  if (k!=0 &&k % 10 == 0 && k != rowLen - 1)
                    out.writeBytes("\n");
                  if (k!=0 &&k % 10 == 0 && k != rowLen - 1)
                    out.writeBytes("\n");
                }
                out.writeBytes("\n");
              } else if (denseOrSparse.equals("T_INT_SPARSE")) {
                nnz = fin.readInt();
                rowLen = fin.readInt();
                out.writeBytes("rowId:" + rowIndex + " clock:" + clock + " size:" + rowLen
                    + " type:" + denseOrSparse + " nnz:" + nnz + "\n");
                for (int k = 0; k < rowLen; k++) {
                  key = fin.readInt();
                  value = fin.readInt();
                  out.writeBytes(key + ":" + value + " ");
                  if (k!=0 &&k % 10 == 0 && k != rowLen - 1)
                    out.writeBytes("\n");
                }
                out.writeBytes("\n");
              } else {
                LOG.error(String.format(denseOrSparse + " type error,need T_INT_ARBITRARY"));
              }
            }
            break;
          }
        }

        fin.close();
        out.close();
        isSuccess.set(true);
        finishFlag.set(true);
      } catch (IOException e) {
        errorLog = "convert partFile " + status.toString() + " error";
        LOG.error(errorLog, e);
        isSuccess.set(false);
      } finally {
        LOG.info(String.format("convert partFile " + status.toString() + " cost time: "
            + (Time.monotonicNow() - startTime) + "ms!"));
        finishFlag.set(true);
      }
    }

    /**
     * Is success.
     *
     * @return true if success, else false
     */
    public boolean isSuccess() {
      return isSuccess.get();
    }

    /**
     * Gets error log
     *
     * @return the error log if exists
     */
    public String getErrorLog() {
      return errorLog;
    }
  }

  /**
   * init the output path and convert task
   * 
   * @throws IOException
   */
  public void convertInit() throws IOException {
    fileStatus = null;
    LOG.info(String.format("read model from " + inputStr));
    if (inputStr == null) {
      throw new IOException("inputStr is null");
    }
    Path inputPath = new Path(inputStr);
    Configuration conf = new Configuration();
    infs = inputPath.getFileSystem(conf);
    fileStatus = infs.listStatus(inputPath);

    if (outputStr == null) {
      throw new IOException("outputStr is null");
    }

    outputPath = new Path(outputStr);
    LOG.info(String.format("outputPath:" + outputPath.toString()));
    outfs=outputPath.getFileSystem(conf);
    outfs.mkdirs(outputPath);
  }

  /**
   * parsing the model
   * 
   * @throws IOException
   * @throws InterruptedException
   */
  public void convertModel() throws IOException, InterruptedException {
    if (isConverting.get() == true) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(String.format("model is converting......"));
      }
      return;
    }
    LOG.info(String.format("to start convert tasks!"));
    isConverting.set(true);
    long startTime = Time.monotonicNow();

    convertInit();
    ThreadFactory convertThreadFacotry =
        new ThreadFactoryBuilder().setNameFormat("ConvertTask").build();
    convertTaskPool = Executors.newFixedThreadPool(convertThreadCount, convertThreadFacotry);
    List<ConvertTask> allConvertTasks = new ArrayList<>();
    Configuration conf;
    FileSystem outfs;
    for (int i = 0; i < fileStatus.length; i++) {
      FileStatus status = fileStatus[i];
      conf = new Configuration();
      outfs = outputPath.getFileSystem(conf);
      FSDataOutputStream out = outfs.create(new Path(outputPath, String.valueOf(i)));
      ConvertTask ConvertTask = new ConvertTask(status, out);
      allConvertTasks.add(ConvertTask);
      convertTaskPool.execute(ConvertTask);
    }
    boolean convertSuccess = true;
    String errorLog = null;
    for (ConvertTask task : allConvertTasks) {
      while (task.finishFlag.get() != true) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        continue;
      }
      if (task.isSuccess() == false) {
        convertSuccess = false;
        errorLog = task.getErrorLog();
      }
    }
    LOG.info(String.format("model convert cost time: " + (Time.monotonicNow() - startTime) + "ms"));
    convertTaskPool.shutdownNow();
    if (!convertSuccess) {
      LOG.error(String.format("convert failed for " + errorLog));
    }
  }

}
