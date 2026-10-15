package world.phantasmal.web.core.rendering.conversion

import org.khronos.webgl.Float32Array
import org.khronos.webgl.Uint16Array
import org.khronos.webgl.set
import world.phantasmal.core.asArray
import world.phantasmal.core.jsArrayOf
import world.phantasmal.core.unsafe.UnsafeMap
import world.phantasmal.psolib.fileFormats.ninja.XvrTexture
import world.phantasmal.web.externals.three.*
import world.phantasmal.webui.obj

class MeshBuilder(
    private val textures: List<XvrTexture?> = emptyList(),
    private val textureCache: UnsafeMap<Int, Texture?> = UnsafeMap(),
    private val anisotropy: Int = 1,
    private val textureIndexOverrides: Map<Int, Int> = emptyMap(),
) {
    private val positions = mutableListOf<Vector3>()
    private val normals = mutableListOf<Vector3>()
    private val uvs = mutableListOf<Vector2>()

    /**
     * ARGB vertex colors, only used when at least one vertex has a color.
     */
    private val colors = mutableListOf<Int>()
    private var hasColors = false
    private val boneIndices = mutableListOf<Short>()
    private val boneWeights = mutableListOf<Float>()
    private val bones = mutableListOf<Bone>()

    /**
     * One group per material.
     */
    private val groups = mutableListOf<Group>()
    private var indexCount = 0

    private var defaultMaterial: Material? = null

    /**
     * @param alpha Whether the group is alpha blended, otherwise it's rendered opaque.
     * @param alphaTest Texels with a lower alpha are discarded. Only used for opaque groups.
     * @param srcAlpha Ninja source blend factor, see [ninjaBlendFactor].
     * @param dstAlpha Ninja destination blend factor, see [ninjaBlendFactor].
     * @param doubleSide Whether back faces are rendered.
     * @param diffuse Material diffuse color as ARGB, multiplied with the texture. Its alpha is
     * only used by [alpha] groups. Untextured groups with a diffuse color are drawn in that color
     * instead of with the default material.
     */
    fun getGroupIndex(
        textureIndex: Int?,
        alpha: Boolean,
        srcAlpha: Int? = NJ_BLEND_SRC_ALPHA,
        dstAlpha: Int? = NJ_BLEND_INV_SRC_ALPHA,
        environmentMapping: Boolean = false,
        alphaTest: Double = 0.0,
        doubleSide: Boolean = true,
        diffuse: Int? = null,
    ): Int {
        val textureIndex = textureIndex?.let { textureIndexOverrides[it] ?: it }
        val srcAlpha = srcAlpha ?: NJ_BLEND_SRC_ALPHA
        val dstAlpha = dstAlpha ?: NJ_BLEND_INV_SRC_ALPHA
        val groupIndex = groups.indexOfFirst {
            it.textureIndex == textureIndex &&
                    it.alpha == alpha &&
                    it.srcAlpha == srcAlpha &&
                    it.dstAlpha == dstAlpha &&
                    it.environmentMapping == environmentMapping &&
                    it.alphaTest == alphaTest &&
                    it.doubleSide == doubleSide &&
                    it.diffuse == diffuse
        }

        return if (groupIndex != -1) {
            groupIndex
        } else {
            groups.add(
                Group(
                    textureIndex,
                    alpha,
                    srcAlpha,
                    dstAlpha,
                    environmentMapping,
                    alphaTest,
                    doubleSide,
                    diffuse,
                )
            )
            groups.lastIndex
        }
    }

    val vertexCount: Int
        get() = positions.size

    fun getPosition(index: Int): Vector3 =
        positions[index]

    fun getNormal(index: Int): Vector3 =
        normals[index]

    fun vertex(
        position: Vector3,
        normal: Vector3,
        uv: Vector2? = null,
        boneIndices: IntArray? = null,
        boneWeights: FloatArray? = null,
        color: Int? = null,
    ) {
        positions.add(position)
        normals.add(normal)
        uv?.let { uvs.add(uv) }
        colors.add(color ?: WHITE)
        hasColors = hasColors || color != null

        if (boneIndices != null && boneWeights != null) {
            require(boneIndices.size == 4)
            require(boneWeights.size == 4)

            for (index in boneIndices) {
                this.boneIndices.add(index.toShort())
            }

            for (weight in boneWeights) {
                this.boneWeights.add(weight)
            }
        }
    }

    fun index(groupIdx: Int, index: Int) {
        groups[groupIdx].indices.push(index.toShort())
        indexCount++
    }

    fun bone(bone: Bone) {
        bones.add(bone)
    }

    fun defaultMaterial(material: Material) {
        defaultMaterial = material
    }

    fun buildMesh(boundingVolumes: Boolean = false): Mesh =
        build(skinning = false, boundingVolumes) { geom, materials, _ ->
            Mesh(geom, materials)
        }

    /**
     * Creates an [InstancedMesh] with 0 instances.
     */
    fun buildInstancedMesh(maxInstances: Int, boundingVolumes: Boolean = false): InstancedMesh =
        build(skinning = false, boundingVolumes) { geom, materials, _ ->
            InstancedMesh(geom, materials, maxInstances).apply {
                // Start with 0 instances.
                count = 0
            }
        }

    /**
     * Creates a [SkinnedMesh] with bones and a skeleton for animation.
     */
    fun buildSkinnedMesh(boundingVolumes: Boolean = false): SkinnedMesh =
        build(skinning = true, boundingVolumes) { geom, materials, bones ->
            SkinnedMesh(geom, materials).apply {
                add(bones[0])
                bind(Skeleton(bones))
            }
        }

    private fun <M : Mesh> build(
        skinning: Boolean,
        boundingVolumes: Boolean,
        createMesh: (BufferGeometry, Array<Material>, Array<Bone>) -> M,
    ): M {
        check(positions.size == normals.size)
        check(uvs.isEmpty() || positions.size == uvs.size)

        val positions = Float32Array(3 * positions.size)
        val normals = Float32Array(3 * normals.size)
        val uvs = if (uvs.isEmpty()) null else Float32Array(2 * uvs.size)

        for (i in this.positions.indices) {
            val pos = this.positions[i]
            positions[3 * i] = pos.x.toFloat()
            positions[3 * i + 1] = pos.y.toFloat()
            positions[3 * i + 2] = pos.z.toFloat()

            val normal = this.normals[i]
            normals[3 * i] = normal.x.toFloat()
            normals[3 * i + 1] = normal.y.toFloat()
            normals[3 * i + 2] = normal.z.toFloat()

            uvs?.let {
                val uv = this.uvs[i]
                uvs[2 * i] = uv.x.toFloat()
                uvs[2 * i + 1] = uv.y.toFloat()
            }
        }

        val geom = BufferGeometry()
        geom.setAttribute("position", Float32BufferAttribute(positions, 3))
        geom.setAttribute("normal", Float32BufferAttribute(normals, 3))
        uvs?.let { geom.setAttribute("uv", Float32BufferAttribute(uvs, 2)) }

        if (hasColors) {
            val colors = Float32Array(4 * this.colors.size)

            for ((i, argb) in this.colors.withIndex()) {
                colors[4 * i] = ((argb ushr 16) and 0xFF) / 255f
                colors[4 * i + 1] = ((argb ushr 8) and 0xFF) / 255f
                colors[4 * i + 2] = (argb and 0xFF) / 255f
                colors[4 * i + 3] = ((argb ushr 24) and 0xFF) / 255f
            }

            geom.setAttribute("color", Float32BufferAttribute(colors, 4))
        }

        if (skinning) {
            check(this.positions.size == boneIndices.size / 4)
            check(this.positions.size == boneWeights.size / 4)

            boneIndices.maxOrNull()?.let {
                check(it < bones.size)
            }

            geom.setAttribute(
                "skinIndex",
                Uint16BufferAttribute(Uint16Array(boneIndices.toTypedArray()), 4)
            )
            geom.setAttribute(
                "skinWeight",
                Float32BufferAttribute(Float32Array(boneWeights.toTypedArray()), 4)
            )
        }

        val indices = Uint16Array(indexCount)

        var offset = 0

        val materials = mutableListOf<Material>()
        // Area geometry carries baked lighting in its vertex colors; all-white colors bake none.
        val bakedLighting = hasColors && colors.any { it != WHITE }

        val defaultMaterial = defaultMaterial ?: MeshLambertMaterial(obj {
            this.skinning = skinning
            side = DoubleSide
        })

        for (group in groups) {
            indices.set(group.indices.asArray(), offset)
            geom.addGroup(offset, group.indices.length, materials.size)

            var tex: Texture? = null

            if (group.textureIndex != null) {
                tex = textureCache.get(group.textureIndex)

                if (tex == null) {
                    tex = textures.getOrNull(group.textureIndex)?.let { xvm ->
                        xvrTextureToThree(xvm, anisotropy = anisotropy)
                    }
                    textureCache.set(group.textureIndex, tex)
                }
            }

            val mat = if (tex == null && group.textureIndex == null && group.diffuse != null) {
                MeshLambertMaterial(obj {
                    this.skinning = skinning
                    color = diffuseColor(group.diffuse)
                    applyGroup(group)
                })
            } else if (tex == null) {
                defaultMaterial
            } else if (group.environmentMapping) {
                MeshMatcapMaterial(obj {
                    this.skinning = skinning
                    matcap = tex
                    color = diffuseColor(group.diffuse ?: WHITE)
                    applyGroup(group)
                })
            } else if (bakedLighting) {
                MeshBasicMaterial(obj {
                    this.skinning = skinning
                    map = tex
                    color = diffuseColor(group.diffuse ?: WHITE)
                    applyGroup(group)
                })
            } else {
                // Without baked vertex lighting (items, characters) the game lights the model;
                // unlit, concave and convex surfaces become indistinguishable.
                MeshLambertMaterial(obj {
                    this.skinning = skinning
                    map = tex
                    color = diffuseColor(group.diffuse ?: WHITE)
                    applyGroup(group)
                })
            }

            materials.add(mat)
            offset += group.indices.length
        }

        geom.setIndex(Uint16BufferAttribute(indices, 1))

        if (boundingVolumes) {
            geom.computeBoundingBox()
            geom.computeBoundingSphere()
        }

        return createMesh(geom, materials.toTypedArray(), bones.toTypedArray())
    }

    private fun MaterialParameters.applyGroup(group: Group) {
        side = if (group.doubleSide) DoubleSide else FrontSide
        vertexColors = hasColors

        val diffuseAlpha = (((group.diffuse ?: WHITE) ushr 24) and 0xFF) / 255.0

        if (group.alpha && diffuseAlpha < 1.0) {
            opacity = diffuseAlpha
        }

        if (group.srcAlpha != NJ_BLEND_SRC_ALPHA || group.dstAlpha != NJ_BLEND_INV_SRC_ALPHA) {
            transparent = true
            depthWrite = false
            alphaTest = 0.01
            blending = CustomBlending
            blendSrc = ninjaBlendFactor(group.srcAlpha, source = true)
            blendDst = ninjaBlendFactor(group.dstAlpha, source = false)
        } else if (group.alpha) {
            transparent = true
            alphaTest = 0.01
        } else if (group.alphaTest > 0.0) {
            alphaTest = group.alphaTest
        }
    }

    private class Group(
        val textureIndex: Int?,
        val alpha: Boolean,
        val srcAlpha: Int,
        val dstAlpha: Int,
        val environmentMapping: Boolean,
        val alphaTest: Double,
        val doubleSide: Boolean,
        val diffuse: Int?,
    ) {
        val indices = jsArrayOf<Short>()
    }

    private fun diffuseColor(argb: Int): Color =
        Color(
            ((argb ushr 16) and 0xFF) / 255.0,
            ((argb ushr 8) and 0xFF) / 255.0,
            (argb and 0xFF) / 255.0,
        )

    companion object {
        private const val WHITE = -1 // 0xFFFFFFFF

        const val NJ_BLEND_SRC_ALPHA = 4
        const val NJ_BLEND_INV_SRC_ALPHA = 5
    }
}

/**
 * Converts a Ninja blend mode value to a three.js blend factor. "Other color" is the destination
 * color for source factors and the source color for destination factors.
 */
internal fun ninjaBlendFactor(value: Int, source: Boolean): BlendingFactor =
    when (value) {
        0 -> ZeroFactor
        1 -> OneFactor
        2 -> if (source) DstColorFactor else SrcColorFactor
        3 -> if (source) OneMinusDstColorFactor else OneMinusSrcColorFactor
        4 -> SrcAlphaFactor
        5 -> OneMinusSrcAlphaFactor
        6 -> DstAlphaFactor
        7 -> OneMinusDstAlphaFactor
        else -> if (source) SrcAlphaFactor else OneMinusSrcAlphaFactor
    }
