package gg.kite.core.cosmetics;

import static org.junit.jupiter.api.Assertions.*;

import gg.kite.core.Json;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Blockbench import, placement, animation presets, the importer's draft and published cosmetics. */
class CreatorTest {
    // ---- Blockbench -------------------------------------------------------------------------------------------------

    static String texture(int w, int h, int argb) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(Images.png(img));
    }

    static Map<String, Object> cube(String name, String uuid, double[] from, double[] to, double[] origin, double[] rotation) {
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("name", name);
        e.put("type", "cube");
        e.put("uuid", uuid);
        e.put("from", nums(from));
        e.put("to", nums(to));
        e.put("origin", nums(origin));
        if (rotation != null) e.put("rotation", nums(rotation));
        Map<String, Object> faces = new LinkedHashMap<String, Object>();
        for (String side : new String[] { "north", "east", "south", "west", "up", "down" }) {
            Map<String, Object> f = new LinkedHashMap<String, Object>();
            f.put("uv", nums(new double[] { 0, 0, 8, 8 }));
            f.put("texture", 0L);
            faces.put(side, f);
        }
        e.put("faces", faces);
        return e;
    }

    static Map<String, Object> group(String name, double[] origin, Object... children) {
        Map<String, Object> g = new LinkedHashMap<String, Object>();
        g.put("name", name);
        g.put("origin", nums(origin));
        g.put("uuid", name + "-uuid");
        g.put("children", new ArrayList<Object>(Arrays.asList(children)));
        return g;
    }

    static List<Object> nums(double[] v) {
        List<Object> out = new ArrayList<Object>();
        for (double d : v) out.add(d);
        return out;
    }

    /** A crown on the head group, two wings in a "wings" group, the template's ref_ player, and a 16x16 texture. */
    static String project() throws IOException {
        List<Object> elements = new ArrayList<Object>();
        elements.add(cube("crown", "c1", new double[] { -4, 32, -4 }, new double[] { 4, 35, 4 }, new double[] { 0, 0, 0 }, null));
        elements.add(cube("wing_r", "w1", new double[] { 1, 14, 2 }, new double[] { 9, 22, 3 }, new double[] { 1, 20, 2 }, new double[] { 0, 0, 0 }));
        elements.add(cube("wing_l", "w2", new double[] { -9, 14, 2 }, new double[] { -1, 22, 3 }, new double[] { -1, 20, 2 }, null));
        elements.add(cube("ref_head", "r1", new double[] { -4, 24, -4 }, new double[] { 4, 32, 4 }, new double[] { 0, 0, 0 }, null));
        Map<String, Object> rightWing = group("right_wing", new double[] { 1, 20, 2 }, "w1");
        Map<String, Object> leftWing = group("left_wing", new double[] { -1, 20, 2 }, "w2");
        List<Object> outliner = new ArrayList<Object>();
        outliner.add(group("head", new double[] { 0, 24, 0 }, "c1"));
        outliner.add(rightWing);
        outliner.add(leftWing);
        outliner.add(group("ref_player", new double[] { 0, 0, 0 }, "r1"));
        Map<String, Object> tex = new LinkedHashMap<String, Object>();
        tex.put("name", "t.png");
        tex.put("uv_width", 16L);
        tex.put("uv_height", 16L);
        tex.put("source", texture(16, 16, 0xFF20C0A0));
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("meta", Json.parseObject("{\"format_version\": \"4.5\", \"model_format\": \"free\"}"));
        root.put("resolution", Json.parseObject("{\"width\": 16, \"height\": 16}"));
        root.put("elements", elements);
        root.put("outliner", outliner);
        root.put("textures", java.util.Collections.singletonList(tex));
        return Json.write(root);
    }

    static boolean hasPoint(Mesh.Layer l, float x, float y, float z) { return ImportTest.hasPoint(l, x, y, z); }

    @Test void blockbenchCubesFollowTheirGroupsWithTheEmbeddedTexture() throws IOException {
        BbModel.Result r = BbModel.parse(project(), null, Slot.HAT, null, "none");
        assertEquals(18, r.model.polyCount(), "crown + two wings; ref_ cubes are skipped");
        assertEquals(16, r.texture.getWidth());
        assertEquals(0xFF20C0A0, r.texture.getRGB(3, 3));
        Mesh mesh = r.model.mesh(new CosmeticModel.Motion());
        Mesh.Layer head = mesh.layer(CosmeticModel.Bone.HEAD, false);
        assertNotNull(head, "the head group follows the head");
        // Blockbench (4, 35, 4) -> model (-4, 24 - 35, 4), the head pivot is the neck.
        assertTrue(hasPoint(head, -4, -11, 4));
        // Faces point out: the crown's top (Blockbench +Y) is model -Y.
        float[] d = head.data();
        boolean up = false;
        for (int q = 0; q < head.quads(); q++) if (d[q * Mesh.STRIDE + 1] < -0.99f) up = true;
        assertTrue(up);
        // The wings are loose groups on the body (the hat slot's default would be the head: groups not named after a
        // part follow the slot's part).
        assertNull(mesh.layer(CosmeticModel.Bone.BODY, false));
        // UVs are 0..1 of the 16 px texture: the 0..8 face covers half.
        boolean half = false;
        for (int q = 0; q < head.quads(); q++) for (int k = 0; k < 4; k++) if (Math.abs(d[q * Mesh.STRIDE + 3 + k * 5 + 3] - 0.5f) < 1e-4) half = true;
        assertTrue(half);
    }

    @Test void flapAnimatesTheWingGroupsInOppositeDirections() throws IOException {
        BbModel.Result r = BbModel.parse(project(), null, Slot.WINGS, null, "flap");
        Mesh a = r.model.mesh(new CosmeticModel.Motion(0, 0, false)), b = r.model.mesh(new CosmeticModel.Motion(5, 0, false));
        Mesh.Layer la = a.layer(CosmeticModel.Bone.BODY, false), lb = b.layer(CosmeticModel.Bone.BODY, false);
        assertNotNull(la);
        assertFalse(Arrays.equals(Arrays.copyOf(la.data(), la.quads() * Mesh.STRIDE), Arrays.copyOf(lb.data(), lb.quads() * Mesh.STRIDE)), "the wings move");
        // The crown is still on the head and doesn't flap.
        Mesh.Layer ha = a.layer(CosmeticModel.Bone.HEAD, false), hb = b.layer(CosmeticModel.Bone.HEAD, false);
        assertArrayEquals(Arrays.copyOf(ha.data(), ha.quads() * Mesh.STRIDE), Arrays.copyOf(hb.data(), hb.quads() * Mesh.STRIDE));
    }

    @Test void rotatedElementsTurnAroundTheirOrigin() throws IOException {
        List<Object> elements = new ArrayList<Object>();
        // A 2x2x2 cube at (10..12, 0..2, 0..2), turned 90 degrees about Y around (10, 0, 0): it lands on z -2..0... x 10..12.
        elements.add(cube("c", "u", new double[] { 10, 0, 0 }, new double[] { 12, 2, 2 }, new double[] { 10, 0, 0 }, new double[] { 0, 90, 0 }));
        Map<String, Object> tex = new LinkedHashMap<String, Object>();
        tex.put("source", texture(8, 8, 0xFFFFFFFF));
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("elements", elements);
        root.put("textures", java.util.Collections.singletonList(tex));
        BbModel.Result r = BbModel.parse(Json.write(root), null, Slot.BACK, CosmeticModel.Bone.BODY, "none");
        Mesh.Layer l = r.model.mesh(new CosmeticModel.Motion()).layer(CosmeticModel.Bone.BODY, false);
        // Rotating (12, 0, 0) by +90 about Y around (10, 0, 0) gives (10, 0, -2) -> model (-10, 24, -2).
        assertTrue(hasPoint(l, -10, 24, -2), "corner after rotation");
    }

    @Test void theTemplateIsOnlyTheReferencePlayer() {
        try {
            BbModel.parse(Importer.blockbenchTemplate(), null, Slot.HAT, null, "none");
            fail("the template has nothing to export yet");
        } catch (IllegalArgumentException | IOException e) {
            assertTrue(e.getMessage().contains("no cubes"), e.getMessage());
        }
        Map<String, Object> t = Json.parseObject(Importer.blockbenchTemplate());
        List<?> outliner = (List<?>) t.get("outliner");
        List<String> names = new ArrayList<String>();
        for (Object o : outliner) names.add(String.valueOf(((Map<?, ?>) o).get("name")));
        assertTrue(names.containsAll(Arrays.asList("head", "body", "right_arm", "left_arm", "right_leg", "left_leg")), names.toString());
        // The right arm group pivots where the player's right shoulder is: +X in Blockbench.
        Map<?, ?> arm = (Map<?, ?>) outliner.get(names.indexOf("right_arm"));
        assertEquals(5.0, ((Number) ((List<?>) arm.get("origin")).get(0)).doubleValue(), 1e-6);
        assertEquals(22.0, ((Number) ((List<?>) arm.get("origin")).get(1)).doubleValue(), 1e-6);
    }

    @Test void bbmodelFilesLoadFromTheFolder(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("angel_wings.bbmodel"), project().getBytes(StandardCharsets.UTF_8));
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(dir);
        Cosmetic c = reg.get("local/angel_wings");
        assertNotNull(c, reg.problems().toString());
        assertEquals(Slot.WINGS, c.slot, "slot from the name");
        assertTrue(c.hasIconImage());
        assertFalse(c.builtin);
    }

    // ---- placement --------------------------------------------------------------------------------------------------

    @Test void placementMovesTurnsAndScalesAroundTheCentre() throws IOException {
        BbModel.Result r = BbModel.parse(project(), null, Slot.HAT, null, "none");
        CosmeticModel m = r.model;
        Mesh.Layer before = m.mesh(new CosmeticModel.Motion()).layer(CosmeticModel.Bone.HEAD, false);
        assertTrue(hasPoint(before, -4, -11, 4));
        // 2 px up: model Y goes down by 2.
        m.setPlacement(new CosmeticModel.Placement(new float[] { 0, 2, 0 }, new float[3], 1));
        assertTrue(hasPoint(m.mesh(new CosmeticModel.Motion()).layer(CosmeticModel.Bone.HEAD, false), -4, -13, 4));
        // Double size around the centre of what's on the head (crown and wings: y -11..10, so -0.5): the corner moves
        // twice as far from it.
        m.setPlacement(new CosmeticModel.Placement(new float[3], new float[3], 2));
        assertTrue(hasPoint(m.mesh(new CosmeticModel.Motion()).layer(CosmeticModel.Bone.HEAD, false), -8, -21.5f, 8));
        // Placement round-trips through cosmetic.json.
        CosmeticModel.Placement p = new CosmeticModel.Placement(new float[] { 1, 2, 3 }, new float[] { 10, 20, 30 }, 1.5f);
        CosmeticModel.Placement back = CosmeticModel.Placement.parse(Json.parseObject(Json.write(p.toJson())));
        assertArrayEquals(p.offset, back.offset, 1e-4f);
        assertArrayEquals(p.rotation, back.rotation, 1e-4f);
        assertEquals(1.5f, back.scale, 1e-4f);
    }

    // ---- draft ------------------------------------------------------------------------------------------------------

    @Test void draftsPreviewSaveAndBundle(@TempDir Path tmp) throws IOException {
        Path cosmetics = tmp.resolve("cosmetics"), src = tmp.resolve("Halo Hat.bbmodel");
        Files.write(src, project().getBytes(StandardCharsets.UTF_8));
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(cosmetics);
        Draft d = Draft.start(cosmetics, src);
        assertEquals("Halo Hat", d.name);
        assertEquals(Slot.HAT, d.slot);
        Cosmetic c = d.rebuild(reg);
        assertNotNull(c, d.problem());
        assertSame(c, reg.get(c.id), "the preview finds the draft");
        assertFalse(reg.all().contains(c), "but it isn't listed");

        // Sliders move the loaded model without a rebuild.
        d.setPlacement(new float[] { 0, 3, 0 }, new float[3], 1);
        assertEquals(3, c.model.placement().offset[1], 1e-6);

        d.price = 250;
        d.rarity = Cosmetic.Rarity.LEGENDARY;
        d.animation = "spin";
        Map<String, Object> b = d.bundle();
        assertEquals("custom-halo-hat", b.get("id"));
        assertEquals(250L, b.get("price"));
        Map<?, ?> files = (Map<?, ?>) b.get("files");
        assertTrue(files.containsKey("Halo_Hat.bbmodel") && files.containsKey("cosmetic.json") && files.containsKey("icon.png"), files.keySet().toString());
        assertFalse(files.keySet().toString().contains(".cache"));
        Map<String, Object> json = Json.parseObject(new String(Base64.getDecoder().decode((String) files.get("cosmetic.json")), StandardCharsets.UTF_8));
        assertEquals("spin", json.get("animation"));
        assertNotNull(json.get("placement"));
        assertNotNull(ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode((String) files.get("icon.png")))));

        String id = d.saveLocal(reg);
        reg.load(cosmetics);
        Cosmetic saved = reg.get(id);
        assertNotNull(saved, reg.problems().toString());
        assertEquals(Cosmetic.Rarity.LEGENDARY, saved.rarity);
        assertEquals(3, saved.model.placement().offset[1], 1e-6, "placement saved");
        assertFalse(Files.exists(cosmetics.resolve(Draft.FOLDER)));
    }

    @Test void pictureDraftsBecomeCapes(@TempDir Path tmp) throws IOException {
        Path cosmetics = tmp.resolve("cosmetics"), src = tmp.resolve("sunset.png");
        ImportTest.image(src, "png", 300, 200, 0xFF8800);
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(cosmetics);
        Draft d = Draft.start(cosmetics, src);
        assertEquals(Slot.CAPE, d.slot);
        assertEquals(Arrays.asList(Slot.CAPE), d.slots());
        assertNotNull(d.rebuild(reg), d.problem());
        Map<?, ?> files = (Map<?, ?>) d.bundle().get("files");
        assertTrue(files.containsKey("picture.png") && files.containsKey("icon.png"));
        d.discard(reg);
        assertNull(reg.get("local/" + Draft.FOLDER));
    }

    // ---- published cosmetics ----------------------------------------------------------------------------------------

    @Test void publishedCosmeticsAreDownloadedAndSharedLikeBuiltIns(@TempDir Path tmp) throws Exception {
        try (final CosmeticsTest.FakeBackend backend = new CosmeticsTest.FakeBackend()) {
            Map<String, String> files = new LinkedHashMap<String, String>();
            files.put("cosmetic.json", Base64.getEncoder().encodeToString("{\"slot\": \"hat\", \"model\": \"m.bbmodel\"}".getBytes(StandardCharsets.UTF_8)));
            files.put("m.bbmodel", Base64.getEncoder().encodeToString(project().getBytes(StandardCharsets.UTF_8)));
            files.put("../evil.txt", Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)));
            backend.published.put("custom-crown", files);

            final Cosmetics cos = new Cosmetics();
            cos.load(tmp);
            cos.setBackend(backend.url());
            final gg.kite.core.ClientHost host = CosmeticsTest.signedIn(backend);
            CosmeticsTest.await(cos, host, () -> cos.registry().get("custom-crown") != null);
            Cosmetic c = cos.registry().get("custom-crown");
            assertNotNull(c, cos.registry().problems().toString());
            assertTrue(c.builtin, "shared: others see it, it must be bought");
            assertEquals("Crown of Tests", c.name, "the store's name wins");
            assertFalse(cos.owns(c));
            assertFalse(Files.exists(tmp.resolve(".remote").resolve("evil.txt")));
            Shop.Offer o = cos.shop().offer("custom-crown");
            assertTrue(o.custom);
            assertEquals("nakii14", o.author);

            // Withdrawn from the store: gone after the next refresh.
            backend.published.clear();
            cos.shop().refresh();
            CosmeticsTest.await(cos, host, () -> cos.registry().get("custom-crown") == null);
            assertNull(cos.registry().get("custom-crown"));
        }
    }

    @Test void adminsPublishThroughTheBackend(@TempDir Path tmp) throws Exception {
        try (final CosmeticsTest.FakeBackend backend = new CosmeticsTest.FakeBackend()) {
            backend.unlimited = true;
            final Cosmetics cos = new Cosmetics();
            cos.load(tmp);
            cos.setBackend(backend.url());
            final gg.kite.core.ClientHost host = CosmeticsTest.signedIn(backend);
            CosmeticsTest.await(cos, host, () -> cos.synced() != null);
            assertTrue(cos.shop().admin());
            Map<String, Object> bundle = new LinkedHashMap<String, Object>();
            bundle.put("id", "custom-x");
            cos.shop().publish(bundle);
            final Shop.Event[] got = new Shop.Event[1];
            CosmeticsTest.await(cos, host, () -> (got[0] = cos.shop().poll()) != null);
            assertTrue(got[0].ok, got[0].message);
            assertEquals("custom-x", got[0].publishedId);
            assertTrue(backend.lastPublish.contains("custom-x"));
        }
    }
}
