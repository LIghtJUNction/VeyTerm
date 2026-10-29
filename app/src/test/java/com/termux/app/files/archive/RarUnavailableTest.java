package com.termux.app.files.archive;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import static com.termux.app.files.archive.ArchiveTools.*;
import static org.junit.Assert.*;

/** Run in a fresh JVM whose library path deliberately contains no native RAR reader. */
public class RarUnavailableTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    @Test public void missingNativeReaderIsAnIOExceptionAndOtherFormatsStillWork() throws Exception {
        File rar = tmp.newFile(); Files.write(rar.toPath(), ArchiveToolsTest.rar4("file", "data"));
        assertEquals(Format.RAR, detect(rar));
        try { list(rar, null, null); fail("Expected missing-library error"); }
        catch (IOException error) { assertTrue(error.getMessage().contains("libveyterm_archive.so")); }
        File input = tmp.newFile(); Files.write(input.toPath(), new byte[]{1, 2, 3});
        File zip = new File(tmp.getRoot(), "out.zip");
        create(zip, Format.ZIP, Collections.singletonList(new Source(input, "data")), null, null);
        test(zip, null, null); assertEquals(1, list(zip, null, null).size());
    }
}
