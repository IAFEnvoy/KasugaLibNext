package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.header;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.BasicLoaders;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk.Chunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxGlobalInfo;
import lib.kasuga.structure.Pair;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class HeaderInfoChunk extends Chunk {
    public HeaderInfoChunk() {
        super(List.of(
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.BYTE
        ), Map.of());
    }

    @Override
    public Object process(List<Pair<StreamLoader, Object>> loadedData, ByteBuffer buffer, SerialContext context) {
        Charset encoding;
        if ((byte) loadedData.getFirst().getSecond() == 0) {
            encoding = StandardCharsets.UTF_16LE;
        } else {
            encoding = StandardCharsets.UTF_8;
        }
        PmxGlobalInfo globalInfo = new PmxGlobalInfo(
                encoding,
                (byte) loadedData.get(1).getSecond(),
                (byte) loadedData.get(2).getSecond(),
                (byte) loadedData.get(3).getSecond(),
                (byte) loadedData.get(4).getSecond(),
                (byte) loadedData.get(5).getSecond(),
                (byte) loadedData.get(6).getSecond(),
                (byte) loadedData.get(7).getSecond()
        );
        return globalInfo;
    }
}
