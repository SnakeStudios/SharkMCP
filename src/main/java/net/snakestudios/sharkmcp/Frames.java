package net.snakestudios.sharkmcp;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** Downscale a framebuffer to at most 1280x720 and encode it as JPEG. */
public final class Frames {
	public static final int MAX_WIDTH = 1280;
	public static final int MAX_HEIGHT = 720;

	private Frames() {}

	/** Integer downscale, then a center crop when the aspect is not an integer fit. */
	public static byte[] toJpeg(int width, int height, Argb pixel) throws Exception {
		if (width < 1 || height < 1) throw new IllegalArgumentException("empty image");
		int factor = Math.max(1, Math.min(width / MAX_WIDTH, height / MAX_HEIGHT));
		int w = width / factor;
		int h = height / factor;
		int tw = Math.min(w, MAX_WIDTH);
		int th = Math.min(h, MAX_HEIGHT);
		int x0 = (w - tw) / 2;
		int y0 = (h - th) / 2;
		BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < th; y++) {
			for (int x = 0; x < tw; x++) {
				out.setRGB(x, y, pixel.at(x0 * factor + x * factor, y0 * factor + y * factor));
			}
		}
		return jpeg(out);
	}

	public static byte[] jpeg(BufferedImage image) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
		ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(0.6f);
		writer.setOutput(new MemoryCacheImageOutputStream(bytes));
		writer.write(null, new IIOImage(image, null, null), param);
		writer.dispose();
		return bytes.toByteArray();
	}

	public static BufferedImage read(byte[] jpeg) throws Exception {
		return ImageIO.read(new ByteArrayInputStream(jpeg));
	}

	@FunctionalInterface
	public interface Argb {
		int at(int x, int y);
	}
}
