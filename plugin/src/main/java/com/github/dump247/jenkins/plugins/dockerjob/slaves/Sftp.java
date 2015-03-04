package com.github.dump247.jenkins.plugins.dockerjob.slaves;

import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;
import com.trilead.ssh2.SFTPv3Client;
import com.trilead.ssh2.SFTPv3FileHandle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Logger;

import static java.lang.String.format;
import static java.util.logging.Level.WARNING;

public class Sftp {
    private static final Logger LOG = Logger.getLogger(Sftp.class.getName());

    public static void writeResource(SFTPv3Client ftp, Class type, String name, String path) throws IOException {
        writeFile(ftp, Resources.getResource(type, name).openStream(), path);
    }

    public static void writeFile(final SFTPv3Client ftp, InputStream content, String path) throws IOException {
        final SFTPv3FileHandle handle = ftp.createFileTruncate(path);

        try {
            ByteStreams.copy(content, new OutputStream() {
                long offset = 0;

                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    ftp.write(handle, offset, b, off, len);
                    offset += len;
                }
            });
        } finally {
            try {
                content.close();
            } catch (IOException ex) {
                LOG.log(WARNING, format("Error closing source stream for %s", path), ex);
            }

            try {
                ftp.closeFile(handle);
            } catch (IOException ex) {
                LOG.log(WARNING, format("Error closing remote file for %s", path), ex);
            }
        }
    }
}
