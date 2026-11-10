package android.content.res;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** Desktop stand-in: the Android assets folder is packed into the jar as resources. */
public class AssetManager {
    public InputStream open(String path) throws IOException {
        InputStream in = AssetManager.class.getResourceAsStream("/" + path);
        if (in == null) throw new FileNotFoundException(path);
        return in;
    }
}
