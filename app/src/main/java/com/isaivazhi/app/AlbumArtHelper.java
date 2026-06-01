package com.isaivazhi.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Album-art extraction and on-disk caching.
 * Pure helper — no Capacitor types here. The plugin façade owns PluginCall handling.
 */
public final class AlbumArtHelper {
    private static final String ART_CACHE_KEY_PREFIX = "art_v2::";

    private AlbumArtHelper() {}

    public static File getArtCacheDir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "albumart");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static String artCacheKey(String filePath) {
        return Integer.toHexString((ART_CACHE_KEY_PREFIX + filePath).hashCode());
    }

    public static File cachedArtFile(Context ctx, String filePath) {
        return new File(getArtCacheDir(ctx), artCacheKey(filePath) + ".jpg");
    }

    /** Returns absolute path of cached art jpg, or null on failure / no embedded picture. */
    public static String extractAndCacheArt(Context ctx, String filePath) {
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(filePath);
            byte[] artBytes = mmr.getEmbeddedPicture();
            mmr.release();

            if (artBytes == null || artBytes.length == 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(artBytes, 0, artBytes.length, opts);

            int size = Math.max(opts.outWidth, opts.outHeight);
            int sampleSize = 1;
            while (size / sampleSize > 1536) sampleSize *= 2;

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sampleSize;
            Bitmap bmp = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.length, opts);
            if (bmp == null) return null;

            Bitmap thumb = Bitmap.createScaledBitmap(bmp, 960, 960, true);
            if (thumb != bmp) bmp.recycle();

            File outFile = cachedArtFile(ctx, filePath);
            FileOutputStream fos = new FileOutputStream(outFile);
            thumb.compress(Bitmap.CompressFormat.JPEG, 92, fos);
            fos.close();
            thumb.recycle();

            return outFile.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }
}
