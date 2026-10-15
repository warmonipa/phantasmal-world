# Rendering fidelity

Scope and decisions for how closely the 3D renderer reproduces PSO's look. Phantasmal World is a
quest editor and model viewer, not a game client: anything that helps assets look correct is in
scope, anything that only reproduces runtime presentation and gets in the way of editing is not.

Area geometry, whose vertex colors carry baked lighting, is unlit (`MeshBasicMaterial`: texture ×
vertex color × material diffuse). Models without baked lighting (items, characters, enemies,
objects) are lit (`MeshLambertMaterial`), as the game lights them.
Area geometry and entities are XJ or NJ models converted by
`web/.../core/rendering/conversion/NinjaGeometryConversion.kt` and `MeshBuilder.kt`; textures by
`XvrTextureConversion.kt`; parsers live in `psolib/.../fileFormats/ninja`.

Reference point: the DashGL Ninja plugin viewer (https://dashgl.gitlab.io/psov2/, three.js r95,
Dreamcast data) gets its shaded look from vertex colors and GPU mipmaps. Both are covered below,
as is back-face culling for NJ models.

## Implemented

| Feature | Notes |
| --- | --- |
| XJ vertex colors | Vertex types 4–7 carry a D3DCOLOR (ARGB). Every vertex of every bundled area has one; they hold the baked lighting. Rendered as an RGBA `color` attribute, alpha included. |
| NJ vertex colors | `NJD_CV_D8` vertex chunks (35, 42, 49) and strip chunks with colors (70–72). Bundled data: only 4 object models have vertex colors (e.g. Dolphin), no NPC or player model does. |
| NJ material diffuse | Diffuse RGB tints the texture; diffuse alpha sets opacity for strips with `useAlpha`. Untextured strips with a diffuse color render as a Lambert material in that color instead of the default entity material. Player models use colored diffuse heavily (e.g. hair, where the game substitutes the player's chosen color at runtime, so the viewer shows the file default); `ffb2b2b2` (the Ninja default) is common. |
| Mipmaps | XVR format flag bit 0 marks a full chain down to 1 × 1 (8/16-byte minimum DXT block). Stored chains are uploaded; DXT textures without one (about half) are decoded to RGBA and mipmapped on the GPU. |
| Anisotropic filtering | Viewer uses half the GPU maximum; quest editor loaders use `DEFAULT_TEXTURE_ANISOTROPY` (8, clamped by three.js). |
| Ninja blending | Source/destination values 0–7 map to three.js factors via `ninjaBlendFactor`; 4/5 is normal alpha blending, anything else uses `CustomBlending` without depth writes. A strip with no blend state yet defaults to normal blending; previously it counted as additive, which made the leading strips of many XJ models (e.g. Forest tree trunks) nearly invisible and over-brightened the ground. |
| XJ opaque/transparent strips | The opaque strip table renders without blending using a 0.5 alpha test (cut-outs); only the transparent table is blended and sorted. |
| NJ back-face culling | NJ strips without the double-side flag render `FrontSide`. |
| DXT2 | Decoded like DXT3. Despite the name, PSO's DXT2 data is straight alpha: in 206 of 338 DXT2 textures, partially transparent texels have color channels above their alpha, which premultiplied data can't have. |
| XJ environment mapping | XJ meshes with a texture but no UVs use `MeshMatcapMaterial`; enabled for multi-part XJ models in the viewer (e.g. items). |
| Lighting of models without baked lighting | A model whose vertex colors are missing or all white (items: every vertex `ffffffff`; NPC and player NJ models: none) uses `MeshLambertMaterial`. The shared light rig is the usual model viewer setup: ambient (0.45) + key directional light (0.6) from the upper left + fill directional light (0.2) from the lower right; the Viewer rotates it with the camera. Unlit, the two sides of Akiko's Frying Pan (0x001600, which has both an inner and an outer surface) could not be told apart. |
| Normal repair for lit models | Low-poly models share one vertex, and so one normal, between surfaces facing different ways. `MeshBuilder` gives a triangle corner whose normal points more than 60° away from its triangle the triangle's own normal (on a copy of the vertex); other normals stay as authored. Without it, Gouraud lighting turned such triangles nearly black, which read as holes (e.g. 14 triangles at the rim and handle joint of Akiko's Frying Pan). Models with baked lighting are unlit and untouched. |
| Antialiasing and pixel ratio | MSAA and `devicePixelRatio` rendering. |
| Skinning and NJM motions | Skinned meshes with motion tracks converted to three.js keyframe tracks; spline motions use smooth interpolation. |

### Verification

- Unit tests: `XjTests`, `NjModelTests` (psolib); `MeshBuilderTests`, `XvrTextureConversionTests`
  (web). They cover vertex color and strip table parsing, NJ colors, diffuse and texture flags, the
  RGBA color attribute, blend factor mapping, culling and diffuse materials, DXT decoding and
  mipmap chains.
- Visual: areas (Forest, Mine) and 12 viewer models compared pixel by pixel against the previous
  commit with the browser cache disabled. Areas gain baked shading and lose minification noise.
  Dolphin, 21st Century and FOmar change as described above. Models whose diffuse is effectively
  white are unchanged. For Barba Ray, the live three.js scene was checked to confirm the expected
  diffuse colors and culling.

## Not needed

| Item | Reason |
| --- | --- |
| Back-face culling for XJ (area) geometry | Editing often looks at areas from below or behind walls, where double-sided rendering helps. XJ winding is also derived from a normal-based heuristic, so culling would risk holes. |
| Area object animation and UV animation | Large effort (needs a per-frame update path for area meshes) and a static area is a better reference for placing entities. Motions are parsed (`AreaObject.Animated`) but rendered statically. |
| NJ texture clamp/flip flags | Parsed into `NjTriangleStrip` but not applied; all textures use mirrored repeat. Clamp is set on nearly every NPC strip and flip on few; honouring them would split materials per wrap mode for little visible gain. Revisit if texture seams show up. |
| Other XVR formats (A8R8G8B8, A4R4G4B4, P8, …) | Bundled area, NPC and object textures only use formats 2, 3, 6 and 7. |
| NJ ambient, specular and `ignoreLight` | Only meaningful with a lighting model; textured surfaces are unlit. |
| NJ double UVs and bump maps | Skipped by the parser; no visible use in an editor. |
| Calibrated game lighting | Area lighting is baked; models use an ambient + key + fill rig for shape, not the game's light setup. |
| Brightness calibration | Without in-game reference captures there is nothing to calibrate against. Revisit with screenshots. |

## Open

| Item | Notes |
| --- | --- |
| NJ environment mapping | `NjTriangleStrip.environmentMapping` is parsed but not passed to `MeshBuilder`, so environment-mapped NJ parts (e.g. metallic weapon or enemy parts) render with their texture as regular UV-mapped surfaces. Small change; not yet decided. |

## three.js version

The renderer runs on three.js r186 (`0.186.1`, upgraded from r128). The upgrade keeps r128's
output; screenshots of areas, models and quest editor overlays before and after differ only in
isolated texels. What keeps it that way:

| Item | Notes |
| --- | --- |
| Color | `ColorManagement.enabled = false` (in `main()`, before any `Color` exists) and `outputColorSpace = LinearSRGBColorSpace`; textures keep `NoColorSpace`. Colors, textures and vertex colors pass through unconverted, as in r128. Moving to an sRGB workflow is a separate decision. |
| Lights | r155+ uses physically based light units; every light intensity is multiplied by `LEGACY_LIGHT_SCALE` (π). |
| Lambert shading | Per fragment since r144 (smoother); accepted. |
| Blending | Every transparent double-sided material (blended model strips, the area section fallback material, quest editor overlays) sets `forceSinglePass`, because r149+ draws such materials in two passes. |
| R5G6B5 textures | Decoded to RGBA8: `UnsignedShort565Type` was removed in r137. |
| Instanced meshes | r151+ culls and raycasts against cached instance bounds; `InstancedMesh.instancesChanged()` drops them whenever instances change, otherwise entities vanish or can't be clicked. `Box3.setFromObject` also prefers those all-instance bounds, so the selection and hover boxes are sized from the entity geometry alone. |
| Camera controls | r186 `OrbitControls` scales zoom by the wheel delta (twice as fast on high-DPI screens), ignores the wheel during a drag and captures the pointer while dragging; `OrbitalCameraInputManager` restores one zoom step per event, zooming while rotating and r128's uncaptured drags (other canvas listeners, e.g. symbol chat trigger tooltips, stop receiving moves during a camera drag). |
| WebGL 2 required | r163 removed WebGL 1 support: on a browser or GPU without WebGL 2 the 3D tools can't create a renderer. Every current browser supports WebGL 2 (Safari since 15). Accepted. |
| Anisotropic filtering | r186 applies it only with trilinear (mipmapped) minification; the only affected textures are DXT textures whose stored mipmap chain is incomplete, which fall back to `LinearFilter`. Accepted. |
| Opaque alpha | r186 writes alpha 1 for opaque (non-transparent, normal-blended) materials. r128 wrote the texel/vertex alpha, so on the transparent canvas the cut-out fringes of alpha-tested foliage let the page background show through slightly, and entity icons kept partly transparent fringes. Accepted: r186 is correct and the difference is limited to isolated fringe texels. |
| Canvas textures | Since r135 a texture's size can't change after its first upload; the symbol chat billboard creates a new texture when its canvas is resized. |
| Module resolution | `require("three")` resolves to `three.module.js` (webpack alias): the deprecated CommonJS entry calls `process.emitWarning`. Addon `@JsModule` paths end in `.js`. |

Known console warning: `THREE.Clock` is deprecated (r183) and still used by the viewer's animation
playback; replace it with `THREE.Timer` when touching that code.
