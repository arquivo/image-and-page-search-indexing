package pt.arquivo.imagesearch.indexing.utils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.net.URL;
import java.net.URLConnection;

public class AlternativeFileUtils {

   /**
    * <p>
    * Method created to replace the {@link org.apache.commons.io.FileUtils}
    * implementation, because the
    * original sometimes failed to copy a file in its entirety (output file size
    * was smaller than input).
    * </p>
    * <p>
    * This code was adapted from the original implementation and the suggestion at
    * https://stackoverflow.com/questions/20143054/copying-xml-file-from-url-returns-incomplete-file
    * .
    * </p>
    * Copies bytes from an {@link InputStream} <code>source</code> to a file
    * <code>destination</code>. The directories up to <code>destination</code>
    * will be created if they don't already exist. <code>destination</code>
    * will be overwritten if it already exists.
    * The {@code source} stream is closed.
    * See {@link #copyToFile(InputStream, File)} for a method that does not close
    * the input stream.
    *
    * @param source      the <code>InputStream</code> to copy bytes from, must not
    *                    be {@code null}, will be closed
    * @param destination the non-directory <code>File</code> to write bytes to
    *                    (possibly overwriting), must not be {@code null}
    * @throws IOException if <code>destination</code> is a directory
    * @throws IOException if <code>destination</code> cannot be written
    * @throws IOException if <code>destination</code> needs creating but can't be
    * @throws IOException if an IO error occurs during copying
    */
   public static void copyURLToFile(final URL source, final File destination,
         final int connectionTimeout, final int readTimeout) throws IOException {
      final URLConnection connection = source.openConnection();
      connection.setConnectTimeout(connectionTimeout);
      connection.setReadTimeout(readTimeout);
      try (final InputStream stream = connection.getInputStream()) {
         copyInputStreamToFile(stream, destination);
      }
   }

   private static void copyInputStreamToFile(InputStream in, File f)
         throws IOException {
      try (InputStream input = in;
            FileOutputStream out = new FileOutputStream(f)) {
         byte[] buffer = new byte[65536];
         int count;
         while ((count = input.read(buffer)) > 0) {
            out.write(buffer, 0, count);
         }
         out.flush();
      }
   }
}
