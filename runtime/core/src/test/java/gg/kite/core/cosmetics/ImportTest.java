package gg.kite.core.cosmetics;

import static org.junit.jupiter.api.Assertions.*;

import gg.kite.core.Json;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Iterator;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportTest {
    // ---- pictures ---------------------------------------------------------------------------------------------------

    /** A GIF of solid-colour frames, each shown {@code delayCs} hundredths of a second. */
    static void gif(Path file, int w, int h, int delayCs, int... colors) throws IOException {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("gif");
        ImageWriter writer = it.next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int c : colors) {
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                g.setColor(new Color(c));
                g.fillRect(0, 0, w, h);
                g.dispose();
                IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), null);
                String fmt = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);
                IIOMetadataNode gce = new IIOMetadataNode("GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", String.valueOf(delayCs));
                gce.setAttribute("transparentColorIndex", "0");
                root.appendChild(gce);
                meta.setFromTree(fmt, root);
                writer.writeToSequence(new IIOImage(img, null, meta), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    static void image(Path file, String format, int w, int h, int color) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(color));
        g.fillRect(0, 0, w, h);
        g.dispose();
        assertTrue(ImageIO.write(img, format, file.toFile()));
    }

    static BufferedImage texture(Cosmetic c) throws IOException { return ImageIO.read(new java.io.ByteArrayInputStream(c.readTexture())); }

    @Test void gifBecomesAnAnimatedCapeAndIsCached(@TempDir Path dir) throws IOException {
        gif(dir.resolve("party.gif"), 120, 160, 5, 0xFF0000, 0x00FF00, 0x0000FF);
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(dir);
        Cosmetic c = reg.get("local/party");
        assertNotNull(c, reg.problems().toString());
        assertEquals(Slot.CAPE, c.slot);
        assertEquals(3, c.frames);
        assertEquals(50, c.frameTimeMs);
        assertEquals(c.textureWidth, c.frameHeight() * 2, "frames have the cape's 2:1 layout");
        BufferedImage tex = texture(c);
        int fh = c.frameHeight(), k = c.textureWidth / 64;
        // The outside of the cape (1,1 .. 11,17) shows each frame's colour.
        assertEquals(0xFF0000, tex.getRGB(6 * k, 9 * k) & 0xFFFFFF);
        assertEquals(0x00FF00, tex.getRGB(6 * k, fh + 9 * k) & 0xFFFFFF);
        assertEquals(0x0000FF, tex.getRGB(6 * k, 2 * fh + 9 * k) & 0xFFFFFF);

        // Loading again reuses the converted file.
        Path cached;
        try (java.util.stream.Stream<Path> s = Files.list(dir.resolve(LocalCosmetics.CACHE))) {
            cached = s.filter(p -> p.toString().endsWith(".png")).findFirst().get();
        }
        Files.setLastModifiedTime(cached, FileTime.fromMillis(1_000_000_000L));
        reg.load(dir);
        assertEquals(1_000_000_000L, Files.getLastModifiedTime(cached).toMillis(), "not converted again");
        // A removed source takes its cache with it.
        Files.delete(dir.resolve("party.gif"));
        reg.load(dir);
        assertFalse(Files.exists(cached));
    }

    @Test void anyPictureIsLaidOutOnACape(@TempDir Path dir) throws IOException {
        image(dir.resolve("photo.jpg"), "jpg", 300, 200, 0x3366CC);
        image(dir.resolve("real-cape.jpg"), "jpg", 128, 64, 0xCC3333);
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(dir);
        Cosmetic photo = reg.get("local/photo");
        assertNotNull(photo, reg.problems().toString());
        assertEquals(1, photo.frames);
        assertEquals(photo.textureWidth, photo.frameHeight() * 2);
        int k = photo.textureWidth / 64;
        int outside = texture(photo).getRGB(6 * k, 9 * k), inside = texture(photo).getRGB(17 * k, 9 * k);
        assertTrue(Math.abs(((outside >> 8) & 255) - 0x66) < 12, Integer.toHexString(outside));
        assertTrue((inside & 255) < (outside & 255), "the inside is darker");
        // A 2:1 picture is already a cape texture: used as it is (only re-encoded as PNG).
        Cosmetic cape = reg.get("local/real-cape");
        assertEquals(128, cape.textureWidth);
        assertEquals(64, cape.textureHeight);
    }

    @Test void picturesCanForceTheCapeLayoutOff(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("banner");
        Files.createDirectories(f);
        image(f.resolve("art.png"), "png", 128, 64, 0x22AA22);
        Files.write(f.resolve("cosmetic.json"), "{\"slot\": \"cape\", \"texture\": \"art.png\", \"picture\": true}".getBytes(StandardCharsets.UTF_8));
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(dir);
        Cosmetic c = reg.get("local/banner");
        assertNotNull(c, reg.problems().toString());
        int k = c.textureWidth / 64;
        assertEquals(0x22AA22, texture(c).getRGB(6 * k, 9 * k) & 0xFFFFFF);
        assertNotEquals(0x22AA22, texture(c).getRGB(17 * k, 9 * k) & 0xFFFFFF, "laid out (darker inside), not used as is");
    }

    @Test void gifTexturesAnimateModels(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("blinky");
        Files.createDirectories(f);
        gif(f.resolve("texture.gif"), 32, 16, 20, 0xFF0000, 0x00FF00);
        Files.write(f.resolve("cosmetic.json"), ("{\"slot\": \"hat\", \"textureSize\": [32, 16], \"parts\": [{\"bone\": \"head\", "
                + "\"cubes\": [{\"from\": [-2, -12, -2], \"size\": [4, 4, 4], \"uv\": [0, 0]}]}]}").getBytes(StandardCharsets.UTF_8));
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(dir);
        Cosmetic c = reg.get("local/blinky");
        assertNotNull(c, reg.problems().toString());
        assertEquals(2, c.frames);
        assertEquals(200, c.frameTimeMs);
        assertEquals(16, c.frameHeight());
    }

    // ---- OBJ models -------------------------------------------------------------------------------------------------

    /** An axis-aligned box as OBJ faces (counter-clockwise from outside), corners in blocks. */
    static String box(String name, String mtl, float x0, float y0, float z0, float x1, float y1, float z1, int base) {
        StringBuilder sb = new StringBuilder("o " + name + "\nusemtl " + mtl + "\n");
        for (int i = 0; i < 8; i++) {
            sb.append(String.format(java.util.Locale.ROOT, "v %.4f %.4f %.4f%n", (i & 1) != 0 ? x1 : x0, (i & 2) != 0 ? y1 : y0, (i & 4) != 0 ? z1 : z0));
        }
        int[][] faces = { { 0, 2, 3, 1 }, { 4, 5, 7, 6 }, { 0, 1, 5, 4 }, { 2, 6, 7, 3 }, { 0, 4, 6, 2 }, { 1, 3, 7, 5 } };
        for (int[] fc : faces) sb.append(String.format(java.util.Locale.ROOT, "f %d %d %d %d%n", base + fc[0], base + fc[1], base + fc[2], base + fc[3]));
        return sb.toString();
    }

    private static final String MTL = "newmtl gold\nKd 1.0 0.6 0.0\n\nnewmtl glow_gem\nKd 0.2 0.4 1.0\n";

    static boolean hasPoint(Mesh.Layer l, float x, float y, float z) {
        float[] d = l.data();
        for (int q = 0; q < l.quads(); q++) {
            for (int k = 0; k < 4; k++) {
                int b = q * Mesh.STRIDE + 3 + k * 5;
                if (Math.abs(d[b] - x) < 1e-3 && Math.abs(d[b + 1] - y) < 1e-3 && Math.abs(d[b + 2] - z) < 1e-3) return true;
            }
        }
        return false;
    }

    @Test void objModelsFollowTheirNamedBonesWithMaterialColours(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("crown_hat");
        Files.createDirectories(f);
        // A crown on top of the head (the head spans 1.5..2.0 blocks up) and a gem on the left arm.
        String obj = "mtllib crown.mtl\n" + box("Crown", "gold", -0.25f, 2.0f, -0.25f, 0.25f, 2.2f, 0.25f, 1)
                + box("Gem.Arm.L", "glow_gem", 0.3f, 1.0f, -0.05f, 0.4f, 1.1f, 0.05f, 9)
                + box("ref_head", "gold", -0.25f, 1.5f, -0.25f, 0.25f, 2.0f, 0.25f, 17);
        Files.write(f.resolve("crown.obj"), obj.getBytes(StandardCharsets.UTF_8));
        Files.write(f.resolve("crown.mtl"), MTL.getBytes(StandardCharsets.UTF_8));
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(dir);
        Cosmetic c = reg.get("local/crown_hat");
        assertNotNull(c, reg.problems().toString());
        assertEquals(Slot.HAT, c.slot, "slot guessed from the folder name");
        assertEquals(12, c.model.polyCount(), "two boxes; the ref_ player reference is skipped");
        assertTrue(c.hasIconImage(), "imported models get a rendered thumbnail");
        assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(c.readIconImage())));

        Mesh mesh = c.model.mesh(new CosmeticModel.Motion());
        Mesh.Layer head = mesh.layer(CosmeticModel.Bone.HEAD, false);
        assertNotNull(head);
        // OBJ (0.25, 2.2, 0.25) blocks -> model (4, 24 - 35.2, -4) px; the head bone sits at the neck (0, 0, 0).
        assertTrue(hasPoint(head, 4, 24 - 35.2f, -4));
        Mesh.Layer arm = mesh.layer(CosmeticModel.Bone.LEFT_ARM, true);
        assertNotNull(arm, "named Arm.L, and its material glows");
        // OBJ (0.4, 1.1, 0.05) -> model (6.4, 6.4, -0.8), relative to the left arm's pivot (5, 2, 0).
        assertTrue(hasPoint(arm, 1.4f, 4.4f, -0.8f));
        // Faces point outwards: the crown's top face normal is up (-Y in model space).
        float[] d = head.data();
        boolean up = false;
        for (int q = 0; q < head.quads(); q++) if (d[q * Mesh.STRIDE + 1] < -0.99f) up = true;
        assertTrue(up);

        // Colour-only materials are packed into an atlas, Blender's linear colours converted to sRGB.
        BufferedImage atlas = texture(c);
        boolean gold = false;
        for (int y = 0; y < atlas.getHeight(); y++) for (int x = 0; x < atlas.getWidth(); x++) if ((atlas.getRGB(x, y) & 0xFFFFFF) == 0xFFCB00) gold = true;
        assertTrue(gold, "Kd 1.0 0.6 0.0 -> #FFCB00");
    }

    @Test void objGroupsGetPivotsAndAnimation(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("flappy");
        Files.createDirectories(f);
        Files.write(f.resolve("wings.obj"), box("Wing.R", "gold", -0.6f, 1.0f, -0.2f, -0.05f, 1.4f, -0.15f, 1).getBytes(StandardCharsets.UTF_8));
        Files.write(f.resolve("cosmetic.json"), ("{\"name\": \"Flappy\", \"slot\": \"wings\", \"model\": \"wings.obj\", \"groups\": {\"wing.r\": "
                + "{\"pivot\": [-0.05, 1.3, -0.15], \"animate\": {\"rot\": [0, 30, 0], \"speed\": 0.5}}}}").getBytes(StandardCharsets.UTF_8));
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(dir);
        Cosmetic c = reg.get("local/flappy");
        assertNotNull(c, reg.problems().toString());
        assertEquals("Flappy", c.name);
        Mesh a = c.model.mesh(new CosmeticModel.Motion(0, 0, false)), b = c.model.mesh(new CosmeticModel.Motion(3, 0, false));
        Mesh.Layer la = a.layer(CosmeticModel.Bone.BODY, false), lb = b.layer(CosmeticModel.Bone.BODY, false);
        assertNotNull(la);
        assertFalse(java.util.Arrays.equals(java.util.Arrays.copyOf(la.data(), la.quads() * Mesh.STRIDE),
                java.util.Arrays.copyOf(lb.data(), lb.quads() * Mesh.STRIDE)), "the wing moves");
        // At rest the hinge (pivot) is where the JSON put it: (-0.05, 1.3, -0.15) -> body space (-0.8, 3.2, 2.4).
        assertTrue(hasPoint(la, -0.8f, 24 - 1.4f * 16, 2.4f) || hasPoint(la, -0.8f, 24 - 1.0f * 16, 2.4f));
    }

    @Test void boneNames() {
        assertEquals(CosmeticModel.Bone.HEAD, ObjModel.boneNamed("Head"));
        assertEquals(CosmeticModel.Bone.LEFT_ARM, ObjModel.boneNamed("Arm.L"));
        assertEquals(CosmeticModel.Bone.RIGHT_ARM, ObjModel.boneNamed("right_arm_glove"));
        assertEquals(CosmeticModel.Bone.LEFT_LEG, ObjModel.boneNamed("LeftLeg"));
        assertEquals(CosmeticModel.Bone.RIGHT_LEG, ObjModel.boneNamed("boot leg_r"));
        assertEquals(CosmeticModel.Bone.BODY, ObjModel.boneNamed("Torso"));
        assertNull(ObjModel.boneNamed("Wing.L"));
        assertNull(ObjModel.boneNamed("Cube.001"));
    }

    @Test void badObjFilesSayWhy(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("broken");
        Files.createDirectories(f);
        Files.write(f.resolve("x.obj"), "v 0 0 0\nf 1 2 3\n".getBytes(StandardCharsets.UTF_8));
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(dir);
        assertNull(reg.get("local/broken"));
        assertTrue(reg.problems().get(0).startsWith("broken: line 2"), reg.problems().toString());
    }

    // ---- importer ---------------------------------------------------------------------------------------------------

    @Test void importerCopiesAModelWithItsMaterialsAndTextures(@TempDir Path tmp) throws IOException {
        Path src = tmp.resolve("blender export"), cosmetics = tmp.resolve("cosmetics");
        Files.createDirectories(src);
        String obj = "mtllib wings.mtl\n" + box("Wing", "feather", -0.5f, 1.0f, 0.12f, 0.5f, 1.5f, 0.18f, 1).replace("f ", "f ").replace("usemtl feather\n", "usemtl feather\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n");
        Files.write(src.resolve("Dragon_Wings.obj"), obj.getBytes(StandardCharsets.UTF_8));
        // Blender writes the absolute path from the artist's machine.
        Files.write(src.resolve("wings.mtl"), "newmtl feather\nKd 1 1 1\nmap_Kd C:\\Users\\artist\\Desktop\\feather.png\n".getBytes(StandardCharsets.UTF_8));
        image(src.resolve("feather.png"), "png", 16, 16, 0xEEDDCC);

        assertEquals(Importer.Kind.MODEL, Importer.kind(src.resolve("Dragon_Wings.obj")));
        assertEquals(Slot.WINGS, Importer.guessSlot(src.resolve("Dragon_Wings.obj")));
        String id = Importer.importFile(cosmetics, src.resolve("Dragon_Wings.obj"), "Dragon Wings", Slot.WINGS, Cosmetic.Rarity.EPIC, false, null);
        assertEquals("local/dragon-wings", id);
        Path folder = cosmetics.resolve("dragon-wings");
        for (String file : new String[] { "Dragon_Wings.obj", "wings.mtl", "feather.png", "cosmetic.json" }) assertTrue(Files.isRegularFile(folder.resolve(file)), file);

        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(cosmetics);
        Cosmetic c = reg.get(id);
        assertNotNull(c, reg.problems().toString());
        assertEquals("Dragon Wings", c.name);
        assertEquals(Cosmetic.Rarity.EPIC, c.rarity);
        assertEquals(16, c.textureWidth, "the single material image is used as it is");

        // Importing the same name again gets its own folder.
        assertEquals("local/dragon-wings-2", Importer.importFile(cosmetics, src.resolve("Dragon_Wings.obj"), "Dragon Wings", Slot.WINGS, null, false, null));
        try {
            Importer.importFile(cosmetics, src.resolve("Dragon_Wings.obj"), "x", Slot.CAPE, null, false, null);
            fail("a model can't be a cape");
        } catch (IOException expected) { }
    }

    @Test void importerTurnsPicturesIntoCapes(@TempDir Path tmp) throws IOException {
        Path cosmetics = tmp.resolve("cosmetics");
        gif(tmp.resolve("my dog.gif"), 50, 80, 10, 0x884422, 0x442288);
        image(tmp.resolve("layout.png"), "png", 64, 32, 0x111111);
        assertEquals("My dog", Importer.suggestName(tmp.resolve("my dog.gif")));
        assertFalse(Importer.looksLikeCapeTexture(tmp.resolve("my dog.gif")));
        assertTrue(Importer.looksLikeCapeTexture(tmp.resolve("layout.png")));
        String dog = Importer.importFile(cosmetics, tmp.resolve("my dog.gif"), "", Slot.HAT, Cosmetic.Rarity.LEGENDARY, false, null);
        String layout = Importer.importFile(cosmetics, tmp.resolve("layout.png"), "Plain", null, null, true, null);
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(cosmetics);
        Cosmetic c = reg.get(dog);
        assertNotNull(c, reg.problems().toString());
        assertEquals(Slot.CAPE, c.slot, "pictures are always capes");
        assertEquals("My dog", c.name);
        assertEquals(2, c.frames);
        Map<String, Object> json = Json.parseObject(new String(Files.readAllBytes(cosmetics.resolve("my-dog/cosmetic.json")), StandardCharsets.UTF_8));
        assertEquals(Boolean.TRUE, json.get("picture"));
        Cosmetic plain = reg.get(layout);
        assertEquals(64, plain.textureWidth, "a cape texture is used as it is");
    }

    @Test void creatorKitIsWrittenAndIgnoredByTheLoader(@TempDir Path dir) throws IOException {
        Path kit = Importer.writeCreatorKit(dir);
        for (String file : new String[] { "kite_cosmetic_template.bbmodel", "player_reference.obj", "player_reference.mtl", "HOW-TO.txt", "example-cosmetic.json" }) assertTrue(Files.isRegularFile(kit.resolve(file)), file);
        CosmeticRegistry reg = new CosmeticRegistry();
        reg.load(dir);
        assertTrue(reg.problems().isEmpty(), reg.problems().toString());
        assertEquals(0, reg.all().size() - builtins());
        // The reference is all ref_* objects: nothing of it would end up in a cosmetic.
        try {
            ObjModel.parse(Importer.referenceObj());
            fail("only reference objects");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("no faces"));
        }
        // Its boxes line up with the player: the head's top is 2 blocks up.
        assertTrue(Importer.referenceObj().contains("v 0.2500 2.0000 0.2500") || Importer.referenceObj().contains("v 0.2500 2.0000 -0.2500"));
    }

    private static int builtins() {
        CosmeticRegistry r = new CosmeticRegistry();
        r.load(null);
        return r.all().size();
    }
}
