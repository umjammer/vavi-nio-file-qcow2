/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.nio.file.qcow2;

import java.io.File;

import com.github.fge.filesystem.driver.ExtendedFileSystemDriverBase.ExtendedFileAttributesFactory;


/**
 * Qcow2FileAttributesFactory.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/10/01 umjammer initial version <br>
 */
public final class Qcow2FileAttributesFactory extends ExtendedFileAttributesFactory {

    public Qcow2FileAttributesFactory() {
        setMetadataClass(File.class);
        addImplementation("basic", Qcow2BasicFileAttributesProvider.class);
    }
}
