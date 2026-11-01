package app.morphe.extension.youtube.download;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

import app.morphe.extension.shared.Utils;

/**
 * 69 mod: turns an animated GIF into a small H.264 MP4 (no FFmpeg needed, uses the phone's encoder).
 * Returns null when the GIF is not animated or the phone can't encode it; the GIF is then kept.
 */
@SuppressWarnings("deprecation")
public final class GifToMp4 {
    private GifToMp4() {}

    public static byte[] convert(byte[] gif) {
        File out = null;
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        try {
            Movie movie = Movie.decodeByteArray(gif, 0, gif.length);
            if (movie == null) return null;
            int duration = movie.duration();
            if (duration <= 0) return null; // a still image
            int w = movie.width(), h = movie.height();
            if (w <= 0 || h <= 0) return null;
            // Encoder friendly size: even numbers, at most 1280 on the long side.
            float scale = Math.min(1f, 1280f / Math.max(w, h));
            int ew = Math.max(2, Math.round(w * scale) & ~1);
            int eh = Math.max(2, Math.round(h * scale) & ~1);
            int fps = 25;
            int frameMs = 1000 / fps;

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ew, eh);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(300_000, ew * eh * 3));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            out = File.createTempFile("gif69", ".mp4", Utils.getContext().getCacheDir());
            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = -1;
            boolean muxing = false;

            Bitmap frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Bitmap scaled = null;
            Canvas canvas = new Canvas(frame);
            int[] argb = new int[ew * eh];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int t = 0;
            boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        if (t >= duration) {
                            codec.queueInputBuffer(in, 0, 0, t * 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            canvas.drawColor(Color.BLACK);
                            movie.setTime(t);
                            movie.draw(canvas, 0, 0);
                            Bitmap src = frame;
                            if (ew != w || eh != h) {
                                if (scaled != null) scaled.recycle();
                                scaled = Bitmap.createScaledBitmap(frame, ew, eh, true);
                                src = scaled;
                            }
                            src.getPixels(argb, 0, ew, 0, 0, ew, eh);
                            Image image = codec.getInputImage(in);
                            if (image == null) return null;
                            fillYuv(image, argb, ew, eh);
                            codec.queueInputBuffer(in, 0, ew * eh * 3 / 2, t * 1000L, 0);
                            t += frameMs;
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxing = true;
                } else if (outIndex >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(outIndex);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxing && buf != null) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        muxer.writeSampleData(track, buf, info);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            muxer.stop();
            byte[] data = readAll(out);
            return data.length > 0 ? data : null;
        } catch (Throwable ex) {
            return null;
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) { }
            try { if (muxer != null) muxer.release(); } catch (Throwable ignored) { }
            if (out != null) //noinspection ResultOfMethodCallIgnored
                out.delete();
        }
    }

    /** ARGB pixels -> the encoder's YUV 4:2:0 image (any plane layout). */
    private static void fillYuv(Image image, int[] argb, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yb = planes[0].getBuffer(), ub = planes[1].getBuffer(), vb = planes[2].getBuffer();
        int yRow = planes[0].getRowStride(), yPix = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride(), uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride(), vPix = planes[2].getPixelStride();
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int c = argb[j * w + i];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yb.put(j * yRow + i * yPix, (byte) y);
                if ((j & 1) == 0 && (i & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    ub.put((j / 2) * uRow + (i / 2) * uPix, (byte) u);
                    vb.put((j / 2) * vRow + (i / 2) * vPix, (byte) v);
                }
            }
        }
    }

    private static byte[] readAll(File f) throws Exception {
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
        }
        return data;
    }

    @SuppressWarnings("unused")
    private static void write(File f, byte[] d) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(d); }
    }
}
