# Cosmetics

Capes, wings, hats, bandanas, ears, face items, boots and backpacks. Players pick them in the **Wardrobe** of the
in-game menu (Right Shift, or the Wardrobe button on the main menu) and buy store items in its **Shop** tab (the hub's
Shop tile and the main menu's Store button open it). Client users also get the **Kite logo badge** in front of their name on
nametags and in the tab list.

## Wardrobe, shop and coins

The Wardrobe tab lists, per category, what you own (**Yours**) and below it what you can buy (**Get in the shop**,
greyed out with a lock and the price). Click one of yours to wear it (again, or right-click, to take it off); click a
shop item to open a card with its description, price and your balance, and buy it there. The Shop tab lists
everything for sale. Purchases use the account's coins (`POST /v1/store/buy`), the same as the launcher's store, and a
bought item is equipped right away. Drag the preview to turn the player and up/down to tilt the view.

**Admins** (`backend/src/admins.ts`, currently `nakii14`; add more with `KITE_ADMINS=name1,name2`) have unlimited
coins, and they alone get **Import**, **Folder** and **Reload**. Names are verified by Mojang; offline (dev) sign-ins can
claim any name, which is why `KITE_ALLOW_OFFLINE` is refused in production.

## Importing and publishing

**The full how-to is [docs/COSMETICS_GUIDE.md](../docs/COSMETICS_GUIDE.md)** (also written into the creator kit).

Import takes a Blockbench project (`.bbmodel`, textures embedded), an OBJ (Blender), a picture or a GIF (capes) and
opens the editor: name, description, category, rarity, price, the body part it follows, position / rotation / size
sliders (live on the preview) and an animation preset (flap, bob, spin, sway). **Save to my wardrobe** keeps it local
(only you see it); **Publish** sends it to the backend (`POST /v1/admin/cosmetics`), which adds it to the store with
its price. Every client downloads published cosmetics (`GET /v1/cosmetics/:id/bundle`) into
`<cosmetics>/.remote/` and treats them like the built-in set: shared with other players, bought with coins, shown in
the launcher's store with the thumbnail the importer rendered. Admins remove one from its shop card.

Code: `BbModel` (Blockbench), `ObjModel` (OBJ), `Images` (pictures, GIFs), `LocalCosmetics` (folders and the
conversion cache), `Draft` + `ui/CosmeticEditor` (the editor), `backend/src/custom.ts` (publishing).

## Add a cape by hand

Put a PNG, JPG or GIF in the cosmetics folder, for example `my_cape.png`, then press Reload. It appears as **My cape**.
Pictures that aren't in the cape layout are laid out on a cape automatically (see Import above).

- The layout is vanilla's 64x32 cape (the outside is at 1,1 to 11,17; the elytra is at 22,0), at **any resolution**
  that keeps the 2:1 ratio: 128x64, 512x256, 1024x512...
- **Animated:** stack frames vertically, for example 16 frames of 256x128 in one 256x2048 PNG. The frame count is
  worked out from the 2:1 layout. Each frame shows for 100 ms; for another speed use a folder with a cosmetic.json
  (below) and `"frameTime"`.
- Wearing an elytra hides the cape, and the elytra keeps its own texture (a cosmetic cape is not painted onto it).
- **Wavy capes** (on by default, toggle under the preview on the Cosmetics page): every cape, Mojang capes included,
  is drawn as cloth instead of a stiff board. Standing still it sways slightly; moving, the hem trails and flutters
  more the faster you go, and jumping, falling and turning lift and swing it, smoothed so sprinting, jumping and
  flying stay calm. The texture layout is the same, so any
  cape works. Turning it off gives vanilla's cape back.

## Add a model (wings, hats...)

Make a folder with a `cosmetic.json` and a `texture.png`:

```json
{
  "name": "Party Hat",
  "slot": "hat",
  "rarity": "rare",
  "textureSize": [32, 16],
  "parts": [
    {
      "bone": "head",
      "pivot": [0, -8.5, 0],
      "rotation": [0, 0, -10],
      "cubes": [{ "from": [-2, -6, -2], "size": [4, 6, 4], "uv": [0, 0] }]
    }
  ]
}
```

| Key | Meaning |
|---|---|
| `slot` | `cape`, `wings`, `hat`, `ears`, `face`, `boots` or `back`. You wear one cosmetic per slot. |
| `rarity` | `common`, `rare`, `epic` or `legendary` (the card colour in the menu). |
| `texture` | Picture in the folder (PNG, JPG or GIF; a GIF sets `frames` and `frameTime`), default `texture.png` or the first picture. |
| `picture` | Capes: `true` lays the picture out on the cape even when it is 2:1. |
| `model` | An OBJ file used instead of `parts` (see Blender above). |
| `textureSize` | UV space of one frame. The PNG can be any multiple of it (high resolution). |
| `frames`, `frameTime` | Vertical animation strip: frame count and milliseconds per frame. |
| `translucent` | `true` blends semi-transparent pixels (glassy wings). The default is cutout: pixels are either shown or not. |
| `iconImage` | Optional thumbnail PNG for the menu (otherwise the texture's `icon` region `[u, v, w, h]` is shown). |
| `parts` | The model, see below. |

Parts use the same conventions as vanilla models and Blockbench's box UV mode. Units are pixels (1/16 block) and
Y points down.

- `bone`: the player part the model follows: `head`, `body`, `right_arm`, `left_arm`, `right_leg` or `left_leg`.
  Child parts inherit it.
  - Head origin: the neck; the head spans x -4..4, y -8..0, z -4..4.
  - Body origin: the neck; the body spans y 0..12. The back is +z.
  - Leg origin: the hip; the leg spans y 0..12.
- `pivot`: offset from the bone (or parent part). `rotation`: degrees, applied Z, then Y, then X, around the pivot.
  `scale`: optional.
- `cubes`: `from` (corner relative to the pivot), `size`, `uv` (top-left of the unfolded box), plus optional
  `inflate` (grow on every side without changing the UVs), `mirror` (mirror the texture) and `glow` (full-bright).
  A cube with a size of 0 on one axis is a flat panel. Paint a shape with transparency to get feathers, membranes and so on.
- `"mirror": true` on a part adds a copy reflected to the other side (left wing from the right one, left leg from the
  right). Mirrored copies of `right_leg` / `right_arm` parts attach to the left limb.
- `animate` waves the part with a sine: `rot` (degrees per axis) and `bob` (pixels) amplitudes, `speed` (radians per
  tick), `phase`, `spin` (degrees per tick, continuous), `flySpeed` / `flyAmp` (multipliers while gliding or flying) and
  `walkAmp` (extra amplitude while running).

Hats and ears hide under a helmet, boots under boots and wings under an elytra, unless the player turns off
**Hide under armor**. A folder that fails to load is listed at the top of the Cosmetics page.

## Regenerate the built-in set

```sh
cd runtime && ./gradlew.bat :core:compileJava && cd ..
java -cp runtime/core/build/classes/java/main cosmetics/StarterCosmetics.java runtime/core/src/main/resources/kite/cosmetics --preview <some dir>
```

`--preview` also renders every cosmetic on a plain player (front and back) into `<some dir>`, so you can check a
change without starting the game. New built-in ids must also be added to `backend/src/catalog.ts` so the backend
accepts them in loadouts.
