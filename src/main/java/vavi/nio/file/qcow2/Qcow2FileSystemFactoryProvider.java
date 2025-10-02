/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.nio.file.qcow2;

import com.github.fge.filesystem.provider.FileSystemFactoryProvider;


/**
 * Qcow2FileSystemFactoryProvider.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/10/01 umjammer initial version <br>
 */
public final class Qcow2FileSystemFactoryProvider extends FileSystemFactoryProvider {

    public Qcow2FileSystemFactoryProvider() {
        setAttributesFactory(new Qcow2FileAttributesFactory());
        setOptionsFactory(new Qcow2FileSystemOptionsFactory());
    }
}
