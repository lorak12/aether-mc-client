package gg.aether.core;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SkinFacesTest {
    private static final String ID = "069a79f444e94726a5befca90e38aaf5";
    private final SkinFaces.Http real = SkinFaces.http;

    @AfterEach
    void reset() {
        SkinFaces.http = real;
        SkinFaces.clear();
    }

    private static String profile(String skinUrl) {
        String textures = "{\"profileId\":\"" + ID + "\",\"textures\":{" + (skinUrl == null ? "" : "\"SKIN\":{\"url\":\"" + skinUrl + "\"}") + "}}";
        String value = Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8));
        return "{\"id\":\"" + ID + "\",\"name\":\"Notch\",\"properties\":[{\"name\":\"textures\",\"value\":\"" + value + "\"}]}";
    }

    private static BufferedImage skin(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 16; y++) for (int x = 8; x < 16; x++) img.setRGB(x, y, 0xFF112233);
        return img;
    }

    @Test
    void readsTheSkinUrlFromTheProfile() {
        assertEquals("https://textures.minecraft.net/texture/abc", SkinFaces.skinUrl(profile("http://textures.minecraft.net/texture/abc")));
        assertNull(SkinFaces.skinUrl(profile(null)), "default skin");
        assertNull(SkinFaces.skinUrl("{\"id\":\"" + ID + "\",\"properties\":[]}"));
    }

    @Test
    void faceHasTheHatLayerOnTop() {
        BufferedImage img = skin(64, 64);
        img.setRGB(40, 8, 0xFFFF0000); // opaque hat pixel
        img.setRGB(41, 8, 0x80FFFFFF); // half see-through hat pixel
        int[] f = SkinFaces.face(img);
        assertEquals(64, f.length);
        assertEquals(0xFFFF0000, f[0]);
        assertEquals(0xFF112233, f[2], "no hat there: the face shows");
        assertEquals(0xFF, f[1] >>> 24);
        assertTrue((f[1] >> 16 & 0xFF) > 0x11 && (f[1] >> 16 & 0xFF) < 0xFF, "blended");
    }

    @Test
    void legacySkinsWithAnOpaqueHatShowTheFace() {
        BufferedImage img = skin(64, 32);
        for (int y = 8; y < 16; y++) for (int x = 40; x < 48; x++) img.setRGB(x, y, 0xFF000000);
        assertEquals(0xFF112233, SkinFaces.face(img)[0]);
    }

    @Test
    void looksFacesUpByUuid() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(skin(64, 64), "png", png);
        final List<String> asked = new ArrayList<String>();
        SkinFaces.http = url -> {
            asked.add(url);
            if (url.startsWith(SkinFaces.PROFILE_URL + ID)) return profile("http://textures.minecraft.net/texture/abc").getBytes(StandardCharsets.UTF_8);
            if (url.equals("https://textures.minecraft.net/texture/abc")) return png.toByteArray();
            return null; // unknown (offline) profile
        };
        assertEquals(0xFF112233, SkinFaces.lookup(ID)[0]);
        assertEquals(SkinFaces.PROFILE_URL + ID, asked.get(0));
        assertSame(SkinFaces.NONE, SkinFaces.lookup("00000000000000000000000000000000"));
        assertSame(SkinFaces.NONE, SkinFaces.face("not-a-uuid"));

        String dashed = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
        int[] f = null;
        for (int i = 0; i < 200 && f == null; i++) {
            f = SkinFaces.face(dashed);
            if (f == null) Thread.sleep(10);
        }
        assertNotNull(f, "the background lookup finished");
        assertEquals(0xFF112233, f[0]);
    }
}
