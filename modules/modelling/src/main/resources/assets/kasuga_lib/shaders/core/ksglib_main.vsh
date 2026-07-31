#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec3 Normal;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec4 Tangent;
in int BoneBindingType;
in ivec4 BoneIndices;
in vec4 BoneWeights;
in vec3 sdefR0;
in vec3 sdefR1;
in vec3 sdefC;
in vec2 TextureUV;
in vec4 TextureBounds;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform samplerBuffer ksg_BoneTransforms;
uniform mat4 ksg_ModelPoseMat;
uniform mat3 ksg_ModelNormalMat;
uniform float ksg_BrightnessScale;
uniform ivec2 ksg_PackedLight;
uniform ivec2 ksg_PackedOverlay;
uniform int ksg_GpuSkinningEnabled;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec2 textureUV;
flat out vec4 textureBounds;
out vec3 viewPos;
out vec3 viewNormal;
out mat3 TBN;
out vec3 viewLight0_Direction;
out vec3 viewLight1_Direction;

#moj_import <kasuga_lib:ksg_skinning.transform.glsl>

void main() {
    vec3 skinnedPosition = Position;
    vec3 skinnedNormal = Normal;
    vec4 skinnedTangent = Tangent;
    if (ksg_GpuSkinningEnabled > 0) {
        ksg_applyGpuSkinning(skinnedPosition, skinnedNormal, skinnedTangent);
    }

    vec4 posWorld = (ksg_ModelPoseMat * vec4(skinnedPosition, 1.0));
    vertexColor = vec4(Color.rgb * ksg_BrightnessScale, Color.a);
    lightMapColor = texelFetch(Sampler2, ksg_PackedLight / 16, 0);
    overlayColor = texelFetch(Sampler1, ksg_PackedOverlay, 0);

    texCoord0 = UV0;
    textureUV = TextureUV;
    textureBounds = TextureBounds;
    vec4 viewPos4 = ModelViewMat * posWorld;
    viewPos = viewPos4.xyz;
    vertexDistance = fog_distance(viewPos, FogShape);
    mat3 normalMatrix = mat3(ModelViewMat) * ksg_ModelNormalMat;
    viewNormal = normalize(normalMatrix * skinnedNormal);

    vec3 T = normalize(normalMatrix * skinnedTangent.xyz);
    vec3 B = cross(viewNormal, T) * skinnedTangent.w;
    TBN = mat3(T, B, viewNormal);

    mat3 viewRot = mat3(ModelViewMat);
    viewLight0_Direction = normalize(viewRot * Light0_Direction);
    viewLight1_Direction = normalize(viewRot * Light1_Direction);

    gl_Position = ProjMat * viewPos4;
}
