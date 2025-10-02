/*
 * Copyright (c) 2025 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.nio.file.qcow2;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import com.github.fge.filesystem.driver.FileSystemDriver;
import com.github.fge.filesystem.provider.FileSystemRepositoryBase;

import static java.lang.System.getLogger;


/**
 * Qcow2FileSystemRepository.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2025/10/01 umjammer initial version <br>
 */
@ParametersAreNonnullByDefault
public final class Qcow2FileSystemRepository extends FileSystemRepositoryBase {

    private static final Logger logger = getLogger(Qcow2FileSystemRepository.class.getName());

    public Qcow2FileSystemRepository() {
        super("qcow2", new Qcow2FileSystemFactoryProvider());
    }

    /**
     * @param uri "vfs:protocol:///?alias=alias", sub url (after "vfs:") parts will be replaced by properties.
     *            if you don't use alias, the url must include username, password, host, port.
     */
    @Nonnull
    @Override
    public FileSystemDriver createDriver(URI uri, Map<String, ?> env) throws IOException {
        String uriString = uri.toString();
        URI subUri = URI.create(uriString.substring(uriString.indexOf(':') + 1));
        String protocol = subUri.getScheme();
logger.log(Level.DEBUG, "protocol: " + protocol);

        Map<String, String> params = getParamsMap(subUri);
        String alias = params.get(Qcow2FileSystemProvider.PARAM_ALIAS);

        Qcow2FileStore fileStore = new Qcow2FileStore(null, factoryProvider.getAttributesFactory());
        return new Qcow2FileSystemDriver(fileStore, factoryProvider, env);
    }

    /* ad-hoc hack for ignoring checking opacity */
    @Override
    protected void checkURI(@Nullable URI uri) {
        Objects.requireNonNull(uri);
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("uri is not absolute");
        }
        if (!getScheme().equals(uri.getScheme())) {
            throw new IllegalArgumentException("bad scheme");
        }
    }
}
