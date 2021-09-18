package io.openmessaging.ssd;

import io.openmessaging.constant.DataFileBasicInfo;
import io.openmessaging.constant.MntPath;
import io.openmessaging.constant.StatusCode;
import io.openmessaging.util.MapUtil;
import io.openmessaging.util.PartitionMaker;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SSDWriterReader2 implements DiskReader {
    // 保存文件输出流对象
    private static ConcurrentHashMap<String, Map<Integer, Map<String, BufferedOutputStream>>> topicQueueFileNameMap = new ConcurrentHashMap<>();

    private static Logger logger = LogManager.getLogger(SSDWriterReader2.class.getName());

    private static SSDWriterReader2 instance = new SSDWriterReader2();


    // 单例模式
    private SSDWriterReader2() {

    }

    @Override
    public ByteBuffer read(String path, long offset, int size) {
        ByteBuffer data = null;
        try {
            RandomAccessFile file = new RandomAccessFile(path, "r");
            long fl = file.length();
            // 超出文件范围
            if (offset >= fl) {
                return null;
            }
            // 是否offset+size超出文件范围
            size = offset + size > fl ? (int) (fl - offset) : size;
            byte[] b = new byte[size];
            file.seek(offset);
            file.read(b);
            data = ByteBuffer.allocate(size);
            data.put(b);
            data.rewind();
        } catch (Exception e) {
            logger.error("Read from disk fail, " + e.toString());
            return data;
        }
        return data;
    }

    /**
     * 将打开的OutputStream对象保存下来，减少因为频繁打开关闭文件流带来的开销*/
    public int append(String mntPath, String topic, int qID, String fileName, byte[] data) {
        try {
            File dir = new File(mntPath+topic+"/"+qID+"/");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            Map<Integer, Map<String, BufferedOutputStream>> queueFileNameMap = MapUtil.getOrPutDefault(topicQueueFileNameMap, topic, new HashMap<>());
            Map<String, BufferedOutputStream> fileNameMap = MapUtil.getOrPutDefault(queueFileNameMap, qID, new HashMap<>());

            BufferedOutputStream file = fileNameMap.get(fileName);
            if (file == null) {
                file = new BufferedOutputStream(new FileOutputStream(mntPath+topic+"/"+qID+"/"+fileName));
                fileNameMap.put(fileName, file);
            }
            file.write(data);
            file.flush();
        } catch (Exception e) {
            logger.error("Append to disk error,"+e.toString());
            return StatusCode.ERROR;
        }
        return StatusCode.SUCCESS;
    }

    /**
     * 直接从磁盘读，屏蔽.index文件细节
     */
    public Map<Long, byte[]> directRead(String topic, int queueId, long offset, int fetchNum) {
        Map<Long, byte[]> map = new HashMap<>();
        int partition = (int) (offset / DataFileBasicInfo.ITEM_NUM);
        long indexFileOffset = offset % DataFileBasicInfo.ITEM_NUM;
        String partitionPath = PartitionMaker.makePartitionPath(partition, DataFileBasicInfo.FILE_NAME_LENGTH, DataFileBasicInfo.ITEM_NUM);
        // 读索引文件
        ByteBuffer indexData = read(MntPath.SSD_PATH + topic + "/" + queueId + "/" + partitionPath + ".index", indexFileOffset * 10, fetchNum * 10);
        if (indexData == null) {
            return map;
        }
        long SSDDataStartOffset = -1L;
        int SSDReadBlockSize = 0;
        int[] size = new int[indexData.capacity() / 10];
        // 寻找.data文件的起止点
        for (int i = 0; i < size.length; i++) {
            if (i == 0) {
                SSDDataStartOffset = indexData.getLong();
                size[i] = indexData.getShort();
            } else {
                indexData.getLong();
                size[i] = indexData.getShort();
            }
            SSDReadBlockSize += size[i];
        }
        // 从ssd读数据
        ByteBuffer SSDData = read(MntPath.SSD_PATH + topic + "/" + queueId + "/" + partitionPath + ".data", SSDDataStartOffset, SSDReadBlockSize);
        for (int i = 0; i < size.length; i++) {
            byte[] bytes = new byte[size[i]];
            SSDData.get(bytes);
            map.put(offset + i, bytes);
        }
        return map;
    }

    public static SSDWriterReader2 getInstance() {
        return instance;
    }

}