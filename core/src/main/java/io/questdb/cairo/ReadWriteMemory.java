/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo;

import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.Files;
import io.questdb.std.FilesFacade;
import io.questdb.std.Unsafe;
import io.questdb.std.str.LPSZ;

public class ReadWriteMemory extends VirtualMemory {
    private static final Log LOG = LogFactory.getLog(ReadWriteMemory.class);
    private FilesFacade ff;
    private long fd = -1;

    public ReadWriteMemory(FilesFacade ff, LPSZ name, long maxPageSize) {
        of(ff, name, maxPageSize);
    }

    public ReadWriteMemory() {
    }

    @Override
    public void close() {
        long size = getAppendOffset();
        super.close();
        if (isOpen()) {
            try {
                AppendMemory.bestEffortClose(ff, LOG, fd, true, size, getMapPageSize());
            } finally {
                fd = -1;
            }
        }
    }

    @Override
    protected long allocateNextPage(int page) {
        final long offset = pageOffset(page);
        final long pageSize = getMapPageSize();

        if (ff.length(fd) < offset + pageSize) {
            ff.truncate(fd, offset + pageSize);
        }

        final long address = ff.mmap(fd, pageSize, offset, Files.MAP_RW);
        if (address != -1) {
            return address;
        }
        throw CairoException.instance(ff.errno()).put("Cannot mmap read-write fd=").put(fd).put(", offset=").put(offset).put(", size=").put(pageSize);
    }

    @Override
    public long getPageAddress(int page) {
        return mapWritePage(page);
    }

    @Override
    protected void release(int page, long address) {
        ff.munmap(address, getPageSize(page));
    }

    public long getFd() {
        return fd;
    }

    public boolean isOpen() {
        return fd != -1;
    }

    public final void of(FilesFacade ff, LPSZ name, long pageSize) {
        close();
        this.ff = ff;
        fd = TableUtils.openFileRWOrFail(ff, name);
        final long size = ff.length(fd);
        setPageSize(pageSize);
        ensurePagesListCapacity(size);
        LOG.info().$("open ").$(name).$(" [fd=").$(fd).$(']').$();
        try {
            // we may not be able to map page here
            // make sure we close file before bailing out
            jumpTo(size);
        } catch (CairoException e) {
            ff.close(fd);
            fd = -1;
            throw e;
        }
    }

    public void sync(int pageIndex, boolean async) {
        if (ff.msync(pages.getQuick(pageIndex), getMapPageSize(), async) == 0) {
            return;
        }
        LOG.error().$("could not msync [fd=").$(fd).$(']').$();
    }

    public void sync(boolean async) {
        for (int i = 0, n = pages.size(); i < n; i++) {
            sync(i, async);
        }
    }

    public void truncate() {
        // We may have many pages papped. Keep one, unmap all others and
        // truncate file to the size of first page
        final long firstPage = getPageAddress(0);
        final long pageSize = getMapPageSize();
        Unsafe.getUnsafe().setMemory(firstPage, pageSize, (byte) 0);
        for (int i = 1, n = pages.size(); i < n; i++) {
            release(i, pages.getQuick(i));
            pages.setQuick(i, 0);
        }
        jumpTo(0);
        long fileSize = ff.length(fd);
        if (fileSize > pageSize) {
            if (ff.truncate(fd, pageSize)) {
                return;
            }

            // we could not truncate the file; we have to clear it via memory mapping
            long mem = ff.mmap(fd, fileSize, 0,  Files.MAP_RW);
            Unsafe.getUnsafe().setMemory(mem + pageSize, fileSize - pageSize, (byte) 0);
            ff.munmap(mem, fileSize);
            LOG.info().$("could not truncate, zeroed [fd=").$(fd).$(']').$();
        }
    }
}
