# Making and importing Kite cosmetics

This guide covers making a cosmetic (hat, wings, ears, face item, boots, backpack or cape), bringing it into the game
with the importer, placing it on the player, and publishing it to the store with a price.

**Who can import:** admins only. The admin list is `backend/src/admins.ts` (currently `nakii14`); more names can be
added with the backend setting `KITE_ADMINS=name1,name2`. Admins also have unlimited coins. You need to be signed in
through the Kite launcher with the backend running: that's how the game knows you're an admin, and publishing goes
through it.

---

## Quick start

1. In game, open the Wardrobe (Right Shift > Wardrobe).
2. Click **Import** (top right) > **Creator kit**. A folder opens with `kite_cosmetic_template.bbmodel` and this guide.
3. Open the template in [Blockbench](https://www.blockbench.net/), build your cosmetic around the player, paint it, and
   save.
4. Back in game, **Import** > **Choose a file...** and pick your `.bbmodel`.
5. In the editor, set the name, category, rarity and price, nudge the position until it sits right on the preview,
   then press **Publish**. It's in the store for everyone.

---

## What you can import

| File | Becomes | Made with |
|---|---|---|
| `.bbmodel` | A 3D cosmetic: hat, wings, ears, face, boots, back | Blockbench (recommended) |
| `.obj` (+ `.mtl`, textures) | A 3D cosmetic | Blender, Blockbench, most 3D tools |
| `.png`, `.jpg` | A cape (and elytra) | Any image editor, or just a photo |
| `.gif` | An animated cape | Any GIF |

---

## Blockbench (recommended)

Blockbench is free, made for Minecraft-style models, and lets you paint the texture right on the model. The importer
reads Blockbench projects directly, textures included, so there's no exporting.

### 1. Start from the template

Open `kite_cosmetic_template.bbmodel` from the creator kit (File > Open Model). It's a **Generic Model** with:

- **ref_player**: the player at real size (head, body, arms, legs, a red patch where the face is). It's locked and
  never imported, so leave it in the file as a guide.
- An empty **group per body part**: `head`, `body`, `right_arm`, `left_arm`, `right_leg`, `left_leg`. Each one already
  has its pivot where that part bends (the neck, the shoulders, the hips).

The player faces **north** (Blockbench's -Z, the side marked N on the grid). The player's right arm is on the +X side.

### 2. Build it

Add cubes (or meshes) **inside the group of the body part it should move with**:

- Hats, crowns, horns, glasses, ears go in `head`.
- Backpacks, capes-as-models, tails and wings go in `body`, or in their own group (see wings below).
- Boots go in `right_leg` and `left_leg`, gloves in the arms.

Anything you put outside those groups follows the category's usual part (hats the head, wings and backpacks the body,
boots the nearest leg), so a single loose hat works too.

Units are pixels, like a Minecraft skin: the head is 8x8x8 and starts 24 px above the ground. Keep it reasonably small
and low on cubes. A few hundred faces is plenty; the limit is 40,000.

Cube rotations, group rotations, inflate, per-face UVs and face rotation all import as you see them in Blockbench.
Mesh elements work too.

### 3. Wings and other moving parts

Put each wing in its own group (for example `right_wing` and `left_wing`) and move the group's **pivot** to where the
wing joins the back (Blockbench: select the group, then drag the pivot point or type its origin). In the importer
choose the **Flap** animation: every group whose name contains "wing" flaps around its pivot, mirrored on the two
sides, faster while flying.

### 4. Paint it

Create a texture (Textures panel > Create Texture, or import a PNG) and paint the cubes. One texture is easiest; several
work too (they're packed together). Semi-transparent pixels are cut out (shown or not). If you want glassy see-through
parts, set `"translucent": true` in the cosmetic.json afterwards (see Advanced).

### 5. Save

File > Save Model (`.bbmodel`). Textures you created in Blockbench are saved inside the file. If you used an image
file from your computer, make sure it's embedded (right-click the texture > Save inside the project / embed), or keep
the image next to the `.bbmodel` when you import.

---

## Pictures and GIFs: capes

Pick any picture and it becomes a cape, the elytra included.

- **A normal picture** (a photo, a logo, art): it's fitted onto the back of the cape (cropped to the cape's 10:16
  shape, centred), darker on the inside, edges in its average colour.
- **An animated GIF**: an animated cape with the GIF's own speed. Very long or large GIFs are sampled down (up to 256
  frames).
- **A real cape texture** (the 64x32 Minecraft cape layout, or any 2:1 multiple like 128x64 or 1024x512): used as it
  is. The importer notices the 2:1 shape and shows a switch, "It's a cape texture"; turn it off to fit it like a
  picture instead.

The cape layout, for painting one yourself (64x32; scale everything up for HD):

```
x:  0 1          11 12         22                46      64
y:0   +-- top ---+-- bottom ---+                         |
  1 |s|  OUTSIDE   |s|  INSIDE   |  elytra wing (22,0 .. 46,22)
    | |  (seen     | |  (against |
    | |   from     | |   the     |
    | |   behind)  | |   back)   |
 17 +-+------------+-+-----------+
```

---

## Blender (OBJ)

1. Import `player_reference.obj` from the creator kit (File > Import > Wavefront). 1 Blender unit = 1 block; the
   player stands on the origin facing -Y (the red patch is the face).
2. Model around it. Name objects after the part they move with: `head`, `body`, `right_arm`, `left_arm`, `right_leg`,
   `left_leg` (`Arm.L`, `leg_r` and the like work too). Other objects follow the category's usual part.
3. Colours: plain material colours work as they are. For painted detail, UV unwrap and give the material an Image
   Texture (PNG, JPG or animated GIF). A material named `glow`, or with Emission, is full-bright in the dark.
4. File > Export > Wavefront (.obj) with the defaults (Forward -Z, Up Y). Reference objects (`ref_*`) are ignored, so
   it doesn't matter if they're exported too.
5. Import the `.obj`. Its `.mtl` and textures are picked up from next to it.

Blender animations and armatures are not imported; use the animation presets instead.

---

## The editor

After you pick a file, the Wardrobe turns into the editor. The preview on the left wears the cosmetic as you change
things. Drag the preview to turn it, and drag up or down to look from above or below.

| Setting | What it does |
|---|---|
| **Name** | Shown in the shop, the launcher's store and the wardrobe. Also makes the store id (`custom-<name>`). |
| **Description** | One line under the name in the shop (optional). |
| **Category** | Which slot it goes in. A player wears one item per category. Pictures are always capes. |
| **Rarity** | Common, rare, epic or legendary: the colour of its card. |
| **Price** | Coins it costs in the store. 0 = free to claim. |
| **Follows** | Which body part the cosmetic moves with when it isn't in a named group. *Auto* picks the category's usual part. |
| **Position** | Right / left, up / down, forward / back in pixels (1 px = a skin pixel), from the wearer's point of view. |
| **Tilt, turn, roll** | Rotate the whole cosmetic (degrees), around its own centre. |
| **Size** | Scale it up or down, around its own centre. |
| **Reset position** | Puts position, rotation and size back. |
| **Animation** | *None*; *Flap* (wing groups flap, harder when flying); *Bob* (floats up and down); *Spin* (turns around, for halos); *Sway* (rocks side to side, more when running; tails). |

Then:

- **Save to my wardrobe**: keeps it only for you, in your local cosmetics folder. Good for trying things out.
- **Publish**: uploads it to the store with the price you set. Every Kite player gets it within a few minutes (the
  game checks the store regularly) and can buy it in game or in the launcher. Publishing again under the same name
  replaces it, and players get the new version.

To take a published item out of the store, open it in the Shop and click **Remove** (admins only, asks to confirm).

---

## Body part reference

Where each part bends, if you place pivots by hand:

| Part | Blockbench (px, Y up) | Blender (blocks, Y up) |
|---|---|---|
| head | 0, 24, 0 (the neck) | 0, 1.5, 0 |
| body | 0, 24, 0 (the neck) | 0, 1.5, 0 |
| right_arm | 5, 22, 0 | -0.31, 1.375, 0 |
| left_arm | -5, 22, 0 | 0.31, 1.375, 0 |
| right_leg | 1.9, 12, 0 | -0.12, 0.75, 0 |
| left_leg | -1.9, 12, 0 | 0.12, 0.75, 0 |

The player faces Blockbench north (-Z) and Blender -Y.

Hats and ears hide under a helmet, boots under boots, and wings under an elytra (players can turn that off).

---

## Advanced: cosmetic.json

Every imported cosmetic is a folder with a `cosmetic.json` next to its files; the editor writes it for you. For
anything the editor doesn't cover, use **Save to my wardrobe**, open the folder (Wardrobe > **Folder**), edit
cosmetic.json, and press **Reload**.

```json
{
  "name": "Dragon Wings",
  "slot": "wings",
  "rarity": "legendary",
  "model": "dragon_wings.bbmodel",
  "animation": "flap",
  "placement": { "offset": [0, 1, -0.5], "rotation": [0, 0, 0], "scale": 1.2 },
  "translucent": true
}
```

| Key | Meaning |
|---|---|
| `model` | The `.bbmodel` or `.obj` file. |
| `texture` | For OBJ: one image for every face. For capes: the picture. |
| `picture` | Capes: `true` fits the picture onto the cape even if it's 2:1. |
| `bone` | `head`, `body`, `right_arm`, `left_arm`, `right_leg`, `left_leg` for everything not in a named group. |
| `animation` | `none`, `flap`, `bob`, `spin`, `sway`. |
| `placement` | `offset` [right, up, forward] px, `rotation` [tilt, turn, roll] degrees, `scale`. |
| `translucent` | Blend semi-transparent pixels (glassy wings) instead of cutting them out. |
| `groups` | OBJ only: per object name, `pivot` (Blender coordinates), `rotation` and `animate` (custom waves: `rot` degrees per axis, `bob` px, `speed`, `spin`, `phase`, `flySpeed`, `flyAmp`, `walkAmp`). |
| `scale`, `offset`, `up` | OBJ only: unit scale, offset in blocks, `"z"` for a Z-up export. |
| `frames`, `frameTime` | Animated textures: frame count (stacked vertically) and ms per frame. GIFs set these themselves. |

To edit a published cosmetic, change your local copy and import it again with the same name, then publish.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| No **Import** button | You're not signed in as an admin, or the backend isn't running. Start the game from the launcher. |
| "No file dialog" | Linux needs `zenity` or `kdialog`. Or put the file in the cosmetics folder (Folder) and Reload. |
| "texture is missing" | The `.bbmodel` points at an image file that isn't embedded: embed it in Blockbench, or keep the image next to the model. |
| It's in the wrong place | Use the position sliders, or put the cubes in the right body-part group. Check the model faces north (Blockbench) / -Y (Blender). |
| It doesn't move with the head or arm | Its group must be named exactly `head`, `right_arm` and so on, or set **Follows**. |
| Wings don't flap | Name the groups with "wing" in them and move each group's pivot off the centre line, to where the wing joins the back. |
| It's huge or tiny (OBJ) | Blender units are blocks. Models taller than 6 units are taken to be in pixels. Use **Size** or `"scale"`. |
| See-through parts look black or missing | Set `"translucent": true`. |
| Others don't see it | Only **published** cosmetics are shared. Saved-to-wardrobe ones are only for you. |
| "That name is taken" | A built-in cosmetic has that id: rename it. |
