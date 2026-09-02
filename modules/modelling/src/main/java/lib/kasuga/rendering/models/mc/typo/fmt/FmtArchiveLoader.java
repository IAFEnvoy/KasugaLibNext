package lib.kasuga.rendering.models.mc.typo.fmt;

import com.google.gson.*;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTexture;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCTextureData;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipResource;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.ModelLoader;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Loader for FMT archive files: MTB (Model.txt) and FMTB (model.jtmt). */
public final class FmtArchiveLoader implements ModelLoader<ZipHelper, ResourceLocation, Integer> {
    private static final float NORMAL_OFFSET = 0.0001f;
    private final String name;
    private final MaterialSetBuilder<Integer> materials;
    private final HashMap<SourceType, HashMap<String, SourceManager<?>>> sidedSources = new HashMap<>();
    private ZipHelper archive;
    private ResourceLocation identifier;

    public FmtArchiveLoader(String name) { this.name = name; this.materials = new MaterialSetBuilder<>(this); }

    @Override public Map<ResourceLocation, Model> load(ResourceLocation id, ZipHelper input) {
        archive = input; identifier = id;
        List<BoxDef> boxes = id.getPath().endsWith(".mtb") ? parseMtb(input) : parseJtmt(input);
        Material material = buildMaterial();
        List<Vertex> vertices = new ArrayList<>(); List<Mesh> meshes = new ArrayList<>();
        Bone root = new Bone("root", new Transform(), null);
        for (int boxIndex = 0; boxIndex < boxes.size(); boxIndex++) {
            appendBox(boxes.get(boxIndex), boxIndex, material, root, vertices, meshes);
        }
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new lib.kasuga.rendering.models.uml.structure.skeleton.Anchor[0], null, new Transform());
        Model model = new Model(vertices.toArray(Vertex[]::new), meshes.toArray(Mesh[]::new), new Bone[]{root}, skeleton,
                materials.endMaterialSet(), MeshMode.QUADS, null, null);
        return Map.of(id, model);
    }

    private Material buildMaterial() {
        ZipResource texture = firstTexture();
        int width = 16, height = 16;
        Object source = MissingTextureAtlasSprite.getLocation();
        ResourceLocation location = MissingTextureAtlasSprite.getLocation();
        if (texture != null) {
            try {
                var image = ImageIO.read(new ByteArrayInputStream(bytes(texture.buffer())));
                if (image != null) {
                    width = image.getWidth(); height = image.getHeight();
                    location = ResourceLocation.fromNamespaceAndPath(identifier.getNamespace(), "fmt/" + Integer.toHexString(identifier.hashCode()));
                    source = Pair.of(location, image); Constants.TEXTURE_BASIC.load(source);
                }
            } catch (Exception ignored) { }
        }
        final ResourceLocation textureLocation = location;
        MCTexture textureData = new MCTexture("main", () -> new net.minecraft.client.resources.model.Material(RenderState.KSG_LAYER_0, textureLocation), width, height,
                new MCTextureData(source, Constants.TEXTURE_BASIC));
        materials.beginMaterial().registerTexture(0, textureData).useTexture(0)
                .addSpriteBuildingFunc((builder, sprites, material) -> sprites.textureId(0).endSprite())
                .endMaterial(0);
        return materials.getNamedMaterial(0);
    }

    private ZipResource firstTexture() {
        for (ZipResource resource : archive.searchNameForResource(n -> n.equals("model.png") || n.equals("texture.png") || n.startsWith("texture-"))) return resource;
        return null;
    }

    private static byte[] bytes(java.nio.ByteBuffer buffer) { var copy = buffer.asReadOnlyBuffer(); byte[] data = new byte[copy.remaining()]; copy.get(data); return data; }

    private List<BoxDef> parseMtb(ZipHelper input) {
        ZipResource resource = input.getResource("Model.txt"); if (resource == null) return List.of();
        String text = new String(bytes(resource.buffer()), StandardCharsets.UTF_8);
        // Processors use SerialContext.peek(); populate through a tiny context bridge is not possible,
        // so parse the stable MTB columns directly here.
        List<BoxDef> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] p = line.trim().split("\\|", -1);
            if (p.length < 20 || !p[0].equals("Element")) continue;
            // MTB exports use both "Box" and "Shapebox" (case varies by
            // Traincraft/Modeler version).  Treat the element type
            // case-insensitively so valid geometry is not silently skipped.
            if (!p[5].equalsIgnoreCase("Box") && !p[5].equalsIgnoreCase("ShapeBox")) continue;
            try {
                Vector3f position = new Vector3f(f(p[6]), f(p[7]), f(p[8]));
                Vector3f size = new Vector3f(f(p[9]), f(p[10]), f(p[11]));
                Vector3f offset = new Vector3f(f(p[15]), f(p[16]), f(p[17]));
                Vector2i uv = new Vector2i(i(p[18]), i(p[19]));
                Vector3f[] corners = null;
                if (p[5].equalsIgnoreCase("ShapeBox") && p.length >= 44) {
                    corners = new Vector3f[8];
                    for (int n = 0; n < 8; n++) {
                        corners[n] = new Vector3f(f(p[20 + n]), f(p[28 + n]), f(p[36 + n]));
                    }
                }
                result.add(new BoxDef(position, size, offset, uv, corners));
            }
            catch (RuntimeException ignored) { }
        }
        return result;
    }

    private List<BoxDef> parseJtmt(ZipHelper input) {
        ZipResource resource = input.getResource("model.jtmt"); if (resource == null) return List.of();
        JsonObject root = JsonParser.parseString(new String(bytes(resource.buffer()), StandardCharsets.UTF_8)).getAsJsonObject();
        List<BoxDef> result = new ArrayList<>(); JsonObject groups = root.has("groups") ? root.getAsJsonObject("groups") : new JsonObject();
        for (JsonElement group : groups.entrySet().stream().map(Map.Entry::getValue).toList()) {
            JsonArray polys = group.getAsJsonObject().getAsJsonArray("polygons"); if (polys == null) continue;
            for (JsonElement element : polys) { JsonObject p = element.getAsJsonObject(); if (!"box".equalsIgnoreCase(p.has("type") ? p.get("type").getAsString() : "")) continue;
                result.add(new BoxDef(new Vector3f(num(p,"pos_x"),num(p,"pos_y"),num(p,"pos_z")), new Vector3f(num(p,"width",1),num(p,"height",1),num(p,"depth",1)), new Vector3f(num(p,"off_x"),num(p,"off_y"),num(p,"off_z")), new Vector2i((int)num(p,"texture_x",0),(int)num(p,"texture_y",0)), null)); }
        }
        return result;
    }

    private static float f(String s) { return Float.parseFloat(s.trim().replace(',', '.')); }
    private static int i(String s) { return Integer.parseInt(s.trim()); }
    private static float num(JsonObject o, String key) { return num(o,key,0); }
    private static float num(JsonObject o, String key, double def) { return o.has(key) ? o.get(key).getAsFloat() : (float)def; }

    private void appendBox(BoxDef box, int boxIndex, Material material, Bone root, List<Vertex> vertices, List<Mesh> meshes) {
        // MTB uses a screen/model coordinate system where positive Y points
        // down. Minecraft's world coordinates point up, so mirror the Y
        // interval while keeping the X/Z extents unchanged.
        Vector3f sourceMin = new Vector3f(box.position).add(box.offset);
        // Some MTB ShapeBox records intentionally use a zero dimension (the
        // actual primitive is described by additional columns).  Emitting a
        // zero-volume cuboid creates degenerate triangles and severe depth
        // artifacts, so keep a one-pixel thickness for this fallback box
        // representation.
        Vector3f extent = new Vector3f(Math.max(1f, Math.abs(box.size.x)),
                Math.max(1f, Math.abs(box.size.y)), Math.max(1f, Math.abs(box.size.z)));
        Vector3f min = new Vector3f(sourceMin.x, -(sourceMin.y + extent.y), sourceMin.z).mul(1f / 16f);
        Vector3f max = new Vector3f(sourceMin.x + extent.x, -sourceMin.y, sourceMin.z + extent.z).mul(1f / 16f);
        // ShapeBox corner ordering differs between MTB exporters. Until that
        // ordering is explicitly decoded, use the stable bounding cuboid
        // rather than connecting corners heuristically (which creates
        // self-intersecting triangles and floating shards).
        Vector3f[][] faces = {
                {new Vector3f(min.x,min.y,max.z),new Vector3f(max.x,min.y,max.z),new Vector3f(max.x,min.y,min.z),new Vector3f(min.x,min.y,min.z)},
                {new Vector3f(min.x,max.y,min.z),new Vector3f(max.x,max.y,min.z),new Vector3f(max.x,max.y,max.z),new Vector3f(min.x,max.y,max.z)},
                {new Vector3f(max.x,min.y,min.z),new Vector3f(min.x,min.y,min.z),new Vector3f(min.x,max.y,min.z),new Vector3f(max.x,max.y,min.z)},
                {new Vector3f(min.x,min.y,max.z),new Vector3f(max.x,min.y,max.z),new Vector3f(max.x,max.y,max.z),new Vector3f(min.x,max.y,max.z)},
                {new Vector3f(min.x,min.y,min.z),new Vector3f(min.x,min.y,max.z),new Vector3f(min.x,max.y,max.z),new Vector3f(min.x,max.y,min.z)},
                {new Vector3f(max.x,min.y,max.z),new Vector3f(max.x,min.y,min.z),new Vector3f(max.x,max.y,min.z),new Vector3f(max.x,max.y,max.z)}};
        for (int faceIndex = 0; faceIndex < faces.length; faceIndex++) {
            Vector3f[] face = faces[faceIndex];
            Vector3f normal = new Vector3f(face[1]).sub(face[0]).cross(new Vector3f(face[2]).sub(face[0])).normalize();
            Mesh mesh = new Mesh(new Vertex[4], normal, new Transform(), new Material[]{material}, null);
            Vector2f[] faceUvs = atlasUvs(box, faceIndex, (int) material.getTextures()[0].getWidth(), (int) material.getTextures()[0].getHeight());
            for (int n = 0; n < 4; n++) {
                // Push each polygon very slightly outwards.  MTB contains
                // many coplanar/adjacent faces; this epsilon prevents depth
                // precision oscillation (z-fighting) without visible gaps.
                // Equal offsets would still leave two same-facing coplanar
                // polygons at exactly the same depth. Add a stable,
                // sub-pixel layer per source box so overlapping MTB faces
                // receive distinct depth values while remaining invisible.
                float faceOffset = NORMAL_OFFSET * (1f + (boxIndex & 7) + faceIndex * 0.125f);
                Vector3f vertexPosition = new Vector3f(face[n]).fma(faceOffset, normal);
                Vertex v = new Vertex(vertexPosition, null);
                v.addUV(mesh, material, faceUvs[n]);
                v.setBinding(new BoneBinding(new Pair[]{Pair.of(root, 1f)}, BoneBindingFunc.BDEF, null));
                mesh.getVertices()[n] = v;
                vertices.add(v);
            }
            meshes.add(mesh);
        }
    }

    /** MTB stores the origin of the standard six-face cuboid UV net. */
    private static Vector2f[] atlasUvs(BoxDef box, int face, int textureWidth, int textureHeight) {
        float w = Math.abs(box.size.x), h = Math.abs(box.size.y), d = Math.abs(box.size.z);
        float u = box.uv.x, v = box.uv.y;
        float x0, y0, x1, y1;
        switch (face) {
            case 0 -> { x0 = u + d + w; y0 = v; x1 = x0 + w; y1 = y0 + d; } // bottom
            case 1 -> { x0 = u + d; y0 = v; x1 = x0 + w; y1 = y0 + d; } // top
            case 2 -> { x0 = u + d; y0 = v + d; x1 = x0 + w; y1 = y0 + h; } // front
            case 3 -> { x0 = u + d + w + d; y0 = v + d; x1 = x0 + w; y1 = y0 + h; } // back
            case 4 -> { x0 = u; y0 = v + d; x1 = x0 + d; y1 = y0 + h; } // left
            default -> { x0 = u + d + w; y0 = v + d; x1 = x0 + d; y1 = y0 + h; } // right
        }
        float sx = 1f / Math.max(1, textureWidth), sy = 1f / Math.max(1, textureHeight);
        return new Vector2f[]{new Vector2f(x0 * sx, y0 * sy), new Vector2f(x1 * sx, y0 * sy),
                new Vector2f(x1 * sx, y1 * sy), new Vector2f(x0 * sx, y1 * sy)};
    }

    private record BoxDef(Vector3f position, Vector3f size, Vector3f offset, Vector2i uv, Vector3f[] corners) {}
    @Override public MaterialSetBuilder<Integer> materialSetBuilder(){return materials;} @Override public String getName(){return name;}
    @Override public boolean isValidInput(Object input){return input instanceof ZipHelper;} @Override public HashMap<SourceType,HashMap<String,SourceManager<?>>> getSidedSources(){return sidedSources;}
    @Override public Texture loadTexture(Object id){return materials.getTexture((Integer)id);}
}
