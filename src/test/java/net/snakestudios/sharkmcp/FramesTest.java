package net.snakestudios.sharkmcp;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FramesTest {
	@Test
	void smallFrameIsNotUpscaled() throws Exception {
		byte[] jpeg = Frames.toJpeg(640, 360, (x, y) -> 0xFF112233);
		BufferedImage image = Frames.read(jpeg);
		assertEquals(640, image.getWidth());
		assertEquals(360, image.getHeight());
		assertTrue(jpeg[0] == (byte) 0xFF && jpeg[1] == (byte) 0xD8);
	}

	@Test
	void exact720pStays720p() throws Exception {
		BufferedImage image = Frames.read(Frames.toJpeg(1280, 720, (x, y) -> x == 0 && y == 0 ? 0xFFFF0000 : 0xFF000000));
		assertEquals(1280, image.getWidth());
		assertEquals(720, image.getHeight());
	}

	@Test
	void fourKIsQuarteredNotCropped() throws Exception {
		BufferedImage image = Frames.read(Frames.toJpeg(3840, 2160, (x, y) -> color(x, y)));
		assertEquals(1280, image.getWidth());
		assertEquals(720, image.getHeight());
		assertClose(0xFFFF0000, image.getRGB(20, 20));
		assertClose(0xFF00FF00, image.getRGB(1259, 699));
	}

	@Test
	void tenEightyPIsScaledThenCenterCropped() throws Exception {
		BufferedImage image = Frames.read(Frames.toJpeg(1920, 1080, (x, y) -> {
			if (x < 320 || x >= 1600) return 0xFFFF00FF;
			return 0xFF00FFFF;
		}));
		assertEquals(1280, image.getWidth());
		assertEquals(720, image.getHeight());
		assertClose(0xFF00FFFF, image.getRGB(0, 360));
		assertClose(0xFF00FFFF, image.getRGB(1279, 360));
	}

	@Test
	void portraitIsCroppedTo720Tall() throws Exception {
		BufferedImage image = Frames.read(Frames.toJpeg(1000, 2000, (x, y) -> y < 640 ? 0xFFFF0000 : 0xFF0000FF));
		assertEquals(1000, image.getWidth());
		assertEquals(720, image.getHeight());
		assertClose(0xFF0000FF, image.getRGB(500, 0));
	}

	@Test
	void emptyFrameIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> Frames.toJpeg(0, 10, (x, y) -> 0));
		assertThrows(IllegalArgumentException.class, () -> Frames.toJpeg(10, 0, (x, y) -> 0));
	}

	private static int color(int x, int y) {
		if (x < 120 && y < 120) return 0xFFFF0000;
		if (x >= 3720 && y >= 2040) return 0xFF00FF00;
		return 0xFF000000;
	}

	private static void assertClose(int expected, int actual) {
		for (int shift = 16; shift >= 0; shift -= 8) {
			int delta = Math.abs(((expected >> shift) & 0xFF) - ((actual >> shift) & 0xFF));
			assertTrue(delta < 30, Integer.toHexString(expected) + " vs " + Integer.toHexString(actual));
		}
	}
}
