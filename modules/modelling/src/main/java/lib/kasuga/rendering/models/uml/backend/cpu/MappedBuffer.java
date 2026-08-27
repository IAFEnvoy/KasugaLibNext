package lib.kasuga.rendering.models.uml.backend.cpu;

import lombok.Getter;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public abstract class MappedBuffer<T> implements AutoCloseable {

    protected static final MemoryUtil.MemoryAllocator ALLOCATOR = MemoryUtil.getAllocator(false);

    @Getter
    protected ByteBuffer buffer;

    @Getter
    protected Object[] data;

    @Getter
    protected Class<T> type;

    @Getter
    protected boolean isClosed = false;

    @Getter
    protected final ByteOrder order;

    @Getter
    protected final long address;

    public MappedBuffer(int dataSize, Class<T> type) {
        this.type = type;
        buffer = MemoryUtil.memAlloc(sizeOfType() * dataSize);
        address = MemoryUtil.memAddress(buffer);
        this.order = buffer.order();
        data = new Object[dataSize];
    }

    public MappedBuffer(T[] data, Class<T> type) {
        this.type = type;
        buffer = MemoryUtil.memAlloc(sizeOfType() * data.length);
        address = MemoryUtil.memAddress(buffer);
        this.order = buffer.order();
        this.data = data;
    }

    public T getDataFromBuffer(int index) {
        if (index < 0 || index >= data.length) {
            throw new IllegalArgumentException("Index is out of bounds.");
        }
        return getData(slice(index));
    }

    public ByteBuffer slice(int index) {
        return buffer.slice(index * sizeOfType(), sizeOfType());
    }

    public abstract T getData(ByteBuffer slice);

    public abstract void writeData(T value, int index);

    public abstract int sizeOfType();

    public void updateAll(T[] newData) {
        if (newData.length > data.length) {
            throw new IllegalArgumentException("New data size exceeds buffer capacity.");
        }
        this.data = newData;
        for (int i = 0; i < newData.length; i++) {
            writeData(newData[i], i);
        }
    }

    public void updateRange(T[] newData, int offset) {
        if (offset < 0 || offset >= data.length) {
            throw new IllegalArgumentException("Offset is out of bounds.");
        }
        if (newData.length + offset > data.length) {
            throw new IllegalArgumentException("New data size exceeds buffer capacity from the given offset.");
        }
        System.arraycopy(newData, 0, data, offset, newData.length);
        for (int i = 0; i < newData.length; i++) {
            writeData(newData[i], offset + i);
        }
    }

    public int bufferCapacity() {
        return buffer.capacity();
    }

    public int bufferPosition() {
        return buffer.position();
    }

    public int bufferLimit() {
        return buffer.limit();
    }

    public int bufferRemaining() {
        return buffer.remaining();
    }

    public int arrayCapacity() {
        return data.length;
    }

    public void checkOpen(String operation) {
        if (isClosed) {
            throw new IllegalStateException(operation);
        }
    }

    @Override
    public void close() throws Exception {
        MemoryUtil.memFree(buffer);
        data = null;
        isClosed = true;
    }

    public boolean isLittleEndian() {
        return order == ByteOrder.LITTLE_ENDIAN;
    }

     public boolean isBigEndian() {
        return order == ByteOrder.BIG_ENDIAN;
    }
}
