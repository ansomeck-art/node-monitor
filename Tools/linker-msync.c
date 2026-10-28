// Build-only compatibility shim for Apple's ExFAT mmap writeback.
// The Go linker mmaps output, then unmaps it without MS_SYNC. On this
// project's ExFAT disk that produced all-zero executable segments.
// ExFAT may discard dirty output on MS_INVALIDATE before munmap occurs.
// Interpose only the linker process, synchronize before invalidation; not shipped
// or loaded by the app or the Linux agent. No system files are changed.
#include <sys/mman.h>
#include <stddef.h>
static int nodemon_msync(void *address, size_t length, int flags) {
    if (flags & MS_INVALIDATE) {
        int result = msync(address, length, MS_SYNC);
        if (result != 0) return result;
    }
    return msync(address, length, flags);
}
__attribute__((used, section("__DATA,__interpose")))
static const struct { const void *replacement; const void *original; } nodemon_interpose = {
    (const void *)&nodemon_msync, (const void *)&msync
};
