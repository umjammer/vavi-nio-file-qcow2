/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.nio.file.qcow2;

import com.github.fge.filesystem.provider.FileSystemProviderBase;


/**
 * Qcow2FileSystemProvider.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/10/01 umjammer initial version <br>
 */
public final class Qcow2FileSystemProvider extends FileSystemProviderBase {

    public static final String PARAM_ALIAS = "alias";

    public Qcow2FileSystemProvider() {
        super(new Qcow2FileSystemRepository());
    }
}
