package lite.abs;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cover loader: server-side resized JPEGs, small in-memory LRU cache, safe for recycled list rows. */
final class Images {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount() / 1024; }
    };

    private Images() {}

    static String coverPath(String itemId, int width) {
        return "/api/items/" + itemId + "/cover?width=" + width + "&format=jpeg";
    }

    /** Loads the cover for an item into the view; the view's tag guards against stale results in recycled rows. */
    static void load(final String itemId, final int width, final ImageView view) {
        final String key = itemId + "@" + width;
        view.setTag(key);
        Bitmap hit = CACHE.get(key);
        if (hit != null) { view.setImageBitmap(hit); return; }
        view.setImageBitmap(null);
        POOL.execute(new Runnable() {
            @Override public void run() {
                try {
                    byte[] data = Api.bytes("GET", coverPath(itemId, width), null);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    final Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    if (bmp == null) return;
                    CACHE.put(key, bmp);
                    UI.post(new Runnable() {
                        @Override public void run() { if (key.equals(view.getTag())) view.setImageBitmap(bmp); }
                    });
                } catch (Exception ignored) { /* no cover: leave the placeholder background */ }
            }
        });
    }
}
