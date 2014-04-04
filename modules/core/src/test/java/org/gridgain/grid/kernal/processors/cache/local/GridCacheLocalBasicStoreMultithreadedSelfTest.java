/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache.local;

import org.gridgain.grid.cache.*;
import org.gridgain.grid.kernal.processors.cache.*;

/**
 * Local test.
 */
public class GridCacheLocalBasicStoreMultithreadedSelfTest extends GridCacheBasicStoreMultithreadedAbstractTest {
    @Override protected GridCacheMode cacheMode() {
        return GridCacheMode.LOCAL;
    }
}
