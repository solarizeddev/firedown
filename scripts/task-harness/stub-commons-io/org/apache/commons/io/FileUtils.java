package org.apache.commons.io;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
/** Fallback: commons-io 2.9+ semantics (delete throws, NoSuchFileException for a missing file). */
public class FileUtils {
    public static File delete(File f) throws IOException { Files.delete(f.toPath()); return f; }
    public static void forceMkdir(File d) throws IOException { if (!d.isDirectory() && !d.mkdirs()) throw new IOException("Cannot create " + d); }
}
