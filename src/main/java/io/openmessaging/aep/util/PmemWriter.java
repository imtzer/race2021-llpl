package io.openmessaging.aep.util;

import java.nio.ByteBuffer;

public interface PmemWriter {
    // 默认写入行为，自动分配大小合适的MemoryBlock
    long write(byte[] data);

}
