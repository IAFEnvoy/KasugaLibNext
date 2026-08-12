package lib.kasuga.rendering.models.uml.typo.miku_miku_dance;

import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterial;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.mesh.PmxMesh;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.vertex.PmxVertex;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PmxRealAssetSmokeTest {
    @Test
    void parsesEveryPmxInLocalMmdCompatibilityFixtures() throws Exception {
        Path fixtureDir = Path.of("modules/modelling/src/main/resources/assets/kasuga_lib/models/pmx");
        if (!Files.isDirectory(fixtureDir)) fixtureDir = Path.of("src/main/resources/assets/kasuga_lib/models/pmx");
        Assumptions.assumeTrue(Files.isDirectory(fixtureDir), "local MMD fixtures are optional");
        List<Path> packages;
        try (var stream = Files.list(fixtureDir)) {
            packages = stream.filter(path -> path.getFileName().toString().endsWith(".mmd.zip")).toList();
        }
        Assumptions.assumeFalse(packages.isEmpty(), "local MMD fixtures are optional");
        int parsed = 0;
        for (Path pack : packages) {
            try (ZipHelper zip = ZipHelper.fromFile(pack.toString())) {
                for (var resource : zip.searchNameForResource(
                        name -> name.toLowerCase(Locale.ROOT).endsWith(".pmx"))) {
                    ProbeLoader loader = new ProbeLoader();
                    loader.load(resource.name(), resource.buffer());
                    assertNotNull(loader.getHeader(), pack + "#" + resource.name());
                    assertNotNull(loader.getTail(), pack + "#" + resource.name());
                    parsed++;
                }
            }
        }
        assertTrue(parsed >= packages.size());
    }

    private static final class ProbeLoader extends PMXLoader<ByteBuffer, String, String, DummyContext> {
        ProbeLoader() { super("real-asset-probe"); }
        @Override public ByteBuffer getAsByteBuffer(ByteBuffer input) { return input.duplicate().order(ByteOrder.LITTLE_ENDIAN); }
        @Override public void beforeAllLoaders(ByteBuffer buffer, SerialContext<DummyContext> context) {}
        @Override public void beforeLoader(StreamLoader loader, ByteBuffer buffer, SerialContext<DummyContext> context) {}
        @Override public void build(Map<String, Model> map, String id, ByteBuffer buffer, SerialContext<DummyContext> context) {}
        @Override public void buildMaterial(MaterialSetBuilder builder, PmxMaterial material) {}
        @Override public String getTextureIdentifier(String texturePath) { return texturePath; }
        @Override public Vertex getVertex(PmxVertex first, Collection<PmxVertex> vertices) { return null; }
        @Override public Mesh getMesh(Vertex v1, Vertex v2, Vertex v3, PmxMesh mesh) { return null; }
        @Override public Bone getBone(List<PmxBone> bones, PmxBone bone) { return null; }
        @Override public ModelData getModelData(PmxHeader header) { return header; }
        @Override public Texture loadTexture(Object id) { return null; }
        @Override public boolean isValidInput(Object input) { return input instanceof ByteBuffer; }
    }
    private static final class DummyContext implements ContextData<DummyContext> {
        @Override public void build(SerialContext<DummyContext> context) {}
    }
}
