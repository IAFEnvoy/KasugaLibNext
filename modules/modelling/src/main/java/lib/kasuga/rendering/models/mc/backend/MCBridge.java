package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.platform.NativeImage;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.data_type.MCRenderableContext;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.SpriteHolder;
import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCMeshData;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.ColorizedMeshData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.SDEFBoneBindingData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.material.SpriteSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.util.ModelProfiler;
import lib.kasuga.structure.Pair;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.HashMap;

public class MCBridge implements Bridge<KsgVertexBuffer> {

    private static final Vector4f DEFAULT_MESH_COLOR = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
    private static final Vector4f HIDDEN_MESH_COLOR = new Vector4f();

    @Override
    public HashMap<Vertex, Vertex> transformVertices(Model model, SkeletonInstance skeleton, Vertex[] vertices) {
        if (KsgVertexBuffer.isGpuSkinningEnabled() || KsgVertexBuffer.isIrisGpuSkinningEnabled()) {
            return new HashMap<>();
        }
        if (skeleton.isBindPose()) {
            return new HashMap<>();
        }
        return skeleton.getVertexTransforms(model, this);
    }

    @Override
    public Mesh[] transformMeshes(Model model, SkeletonInstance skeleton, Mesh[] meshes) {
        return meshes;
    }

    @Override
    public KsgVertexBuffer getBackendRenderable(ModelInstance instance, HashMap vertexMap, Mesh[] meshes) {
        @SuppressWarnings("unchecked")
        HashMap<Vertex, Vertex> vertexHashMap = (HashMap<Vertex, Vertex>) vertexMap;
        Model model = instance.getModel();
        long buildStart = ModelProfiler.start();
        KsgVertexBuffer.Builder builder = new KsgVertexBuffer.Builder(
                model,
                RenderState.UML_VERTEX_FORMAT,
                Constants.MC_BACKEND.executor
        );

        long packStart = ModelProfiler.start();
        for (Mesh mesh : meshes) {
            Vector4f meshColor = DEFAULT_MESH_COLOR;
            if (mesh.getData() instanceof ColorizedMeshData colorized) {
                meshColor = colorized.getColor();
            }
            Direction direction = null;
            boolean visible = true;
            boolean shade = true;
            boolean ambientOcclusion = true;
            if (mesh.getData() instanceof MCMeshData mcMeshData) {
                direction = mcMeshData.getDirection();
                visible = mcMeshData.isVisible();
                shade = mcMeshData.isShade();
                ambientOcclusion = mcMeshData.isAmbientOcclusion();
            }
            if (!visible) {
                meshColor = HIDDEN_MESH_COLOR;
            }

            for (Vertex vertex : mesh.getVertices()) {
                Vertex transformed = vertexHashMap.getOrDefault(vertex, vertex);
                Vector3f normal = transformed.getNormal(mesh);
                if (normal.length() == 0.0f && direction != null) {
                    net.minecraft.core.Direction d = toMCDirection(direction);
                    normal = new Vector3f(d.step());
                }
                Vector3f pos = transformed.getPosition();

                builder.addVertex(pos.x(), pos.y(), pos.z())
                        .setNormal(normal.x(), normal.y(), normal.z())
                        .setColor(255, 255, 255,255);

                int i = 0;
                for (Material material : mesh.getMaterials()) {
                    Vector2f uv = transformed.getUV(mesh, material);
                    if (uv == null) continue;
                    float u0, v0, u1, v1, u2, v2, u3, v3;
                    SpriteSet spriteSet = instance.getMaterialFrame(material);
                    Sprite umlSprite = spriteSet.getSprite(0);
                    boolean flipU = umlSprite.flipU;
                    boolean flipV = umlSprite.flipV;
                    if (umlSprite.getTexture() != null &&
                            umlSprite.getTexture().getData() instanceof SpriteHolder textureData) {
                        TextureAtlasSprite sprite = textureData.getSprite();
                        u0 = flipU ? sprite.getU1() : sprite.getU0();
                        v0 = flipV ? sprite.getV1() : sprite.getV0();
                        u1 = flipU ? sprite.getU0() : sprite.getU1();
                        v1 = flipV ? sprite.getV0() : sprite.getV1();
                        if (textureData.shouldDividedByTextureSize()) {
                            NativeImage image = sprite.contents().getOriginalImage();
                            uv = new Vector2f(uv).mul(1f / (float) image.getWidth(), 1f / (float) image.getHeight());
                        }
                    } else {
                        u0 = 0f; v0 = 0f; u1 = 1f; v1 = 1f;
                    }
                    float rectU = u1 - u0;
                    float rectV = v1 - v0;
                    float maxV = Math.max(v0, v1);
                    float maxU = Math.max(u0, u1);
                    float minV = Math.min(v0, v1);
                    float minU = Math.min(u0, u1);
                    u0 = Math.clamp(u0 + rectU * umlSprite.getUv0().x(), minU, maxU);
                    v0 = Math.clamp(v0 + rectV * umlSprite.getUv0().y(), minV, maxV);
                    u1 = Math.clamp(u0 + rectU * umlSprite.getUv1().x(), minU, maxU);
                    v1 = Math.clamp(v0 + rectV * umlSprite.getUv1().y(), minV, maxV);
                    u2 = Math.clamp(u0 + rectU * umlSprite.getUv2().x(), minU, maxU);
                    v2 = Math.clamp(v0 + rectV * umlSprite.getUv2().y(), minV, maxV);
                    u3 = Math.clamp(u0 + rectU * umlSprite.getUv3().x(), minU, maxU);
                    v3 = Math.clamp(v0 + rectV * umlSprite.getUv3().y(), minV, maxV);
                    Vector2f uvPos = getUVPosition(uv, u0, v0, u1, v1, u2, v2, u3, v3);

                    builder.setUv(i, uvPos);
                    i++;
                }
                int type = bindingType(vertex.getBinding().getFunc());
                if (type != 0) {
                    System.out.println("Vertex uses bone binding type " + type);
                }
                builder.setBoneBindingType(type);
                Pair<Bone, Float>[] weights = vertex.getBinding().getWeights();
                for (int k = 0; k < weights.length; k++) {
                    if (k > 3) break;
                    Pair<Bone, Float> pair = weights[k];
                    builder.setBoneAndWeight(k, pair.getFirst().getIndex(), pair.getSecond());
                }
                BoneBindingData bbd = vertex.getBinding().getData();
                if (type == 1 && bbd instanceof SDEFBoneBindingData sdefData) {
                    if (sdefData.getSDEFData() != null) {
                        builder.setSdefData(sdefData.getSDEFData());
                    }
                }
                builder.pack(vertex, mesh, meshColor);
            }
            builder.endMesh(mesh);
        }
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("mcbridge.packMeshes", packStart,
                    "meshes=" + meshes.length + ", vertices=" + (meshes.length * 4));
        }
        long finalizeStart = ModelProfiler.start();
        KsgVertexBuffer buffer = builder.build(model);
        if (KsgVertexBuffer.isGpuSkinningEnabled() || KsgVertexBuffer.isIrisGpuSkinningEnabled()) {
            buffer.updateForVersion(instance, this);
        }
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("mcbridge.finalizeVertexBuffer", finalizeStart,
                    "meshes=" + meshes.length + ", vertices=" + (meshes.length * 4));
        }
        if (ModelProfiler.enabled()) {
            ModelProfiler.record("mcbridge.buildVertexBuffer", buildStart,
                    "meshes=" + meshes.length + ", vertices=" + (meshes.length * 4));
        }
        return buffer;
    }

    public static Vector2f getUVPosition(Vector2f uv, float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3) {
        float x = uv.x();
        float y = uv.y();
        float invX = 1f - x;
        float invY = 1f - y;
        float w0 = invX * invY;
        float w1 = x * invY;
        float w2 = x * y;
        float w3 = invX * y;
        return new Vector2f(
                u0 * w0 + u1 * w1 + u2 * w2 + u3 * w3,
                v0 * w0 + v1 * w1 + v2 * w2 + v3 * w3
        );
    }

    @Override
    public BoneBindingFunc getBoneBindingFunc(
            Model model,
            SkeletonInstance skeleton,
            Vertex vertex
    ) {
        return vertex.getBinding().getFunc();
    }

    @Override
    public MCRenderableContext getBackendContext(ModelInstance modelInstance) {
        return  new MCRenderableContext(this, modelInstance);
    }

    public static final net.minecraft.core.Direction toMCDirection(Direction direction) {
        return switch (direction) {
            case DOWN -> net.minecraft.core.Direction.DOWN;
            case UP -> net.minecraft.core.Direction.UP;
            case NORTH -> net.minecraft.core.Direction.NORTH;
            case SOUTH -> net.minecraft.core.Direction.SOUTH;
            case WEST -> net.minecraft.core.Direction.WEST;
            case EAST -> net.minecraft.core.Direction.EAST;
        };
    }

    public static final Direction fromMCDirection(net.minecraft.core.Direction direction) {
        return switch (direction) {
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
        };
    }

    public static int bindingType(BoneBindingFunc func) {
        if (func == BoneBindingFunc.SDEF) return 1;
        if (func == BoneBindingFunc.QDEF) return 2;
        return 0;  // BDEF
    }
}
