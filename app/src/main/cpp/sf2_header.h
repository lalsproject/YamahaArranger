#pragma once
// Lightweight SoundFont 2 helpers that never load the whole file into RAM.
//
// Why this exists: the old loader read the *entire* SF2 into a std::vector
// several times per load (normalize, melodic cache, drum cache, preset scan).
// A 200-500 MB Yamaha SF2 on a 32-bit/low-RAM device then fails with
// bad_alloc or gets killed by the low-memory killer, which shows up in the app
// as "SF2 load failed". Everything the loader needs lives in the small "phdr"
// preset-header chunk, so we seek straight to it and read only that.
//
// Pure C++17 + POSIX, no BASS dependency, so it can be unit-tested on a host.

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include <fcntl.h>
#include <sys/statvfs.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

namespace sf2 {

struct PhdrChunk {
    uint64_t fileOffset = 0;               // absolute offset of phdr payload
    uint32_t size = 0;                     // payload size in bytes (multiple of 38)
    std::vector<unsigned char> bytes;      // payload copy (small)
    uint64_t fileSize = 0;
    size_t recordCount() const { return size / 38; }
};

namespace detail {

inline uint32_t le32(const unsigned char* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

inline bool readExact(int fd, uint64_t offset, void* dst, size_t len) {
    unsigned char* out = static_cast<unsigned char*>(dst);
    while (len > 0) {
        const ssize_t n = ::pread64(fd, out, len, static_cast<off64_t>(offset));
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (n == 0) return false;  // unexpected EOF
        out += n;
        offset += static_cast<uint64_t>(n);
        len -= static_cast<size_t>(n);
    }
    return true;
}

inline bool writeExact(int fd, const void* src, size_t len) {
    const unsigned char* in = static_cast<const unsigned char*>(src);
    while (len > 0) {
        const ssize_t n = ::write(fd, in, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        in += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

inline uint64_t fileSizeOf(int fd) {
    const off64_t end = ::lseek64(fd, 0, SEEK_END);
    return end < 0 ? 0 : static_cast<uint64_t>(end);
}

}  // namespace detail

// Walks the RIFF structure (header-by-header, seeking over sample data) and
// reads only the "phdr" chunk of the "pdta" LIST. Returns false and fills
// `err` with a human readable reason (shown in the in-app log).
inline bool readPhdr(const std::string& path, PhdrChunk& out, std::string* err) {
    auto fail = [&](const std::string& why) {
        if (err) *err = why;
        return false;
    };
    out = PhdrChunk{};

    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return fail(std::string("cannot open file (") + std::strerror(errno) + ")");
    }
    struct FdCloser { int fd; ~FdCloser() { ::close(fd); } } closer{fd};

    const uint64_t fileSize = detail::fileSizeOf(fd);
    out.fileSize = fileSize;
    if (fileSize < 12) return fail("file too small (" + std::to_string(fileSize) + " bytes)");

    unsigned char head[12];
    if (!detail::readExact(fd, 0, head, sizeof(head))) return fail("cannot read RIFF header");
    if (std::memcmp(head, "RIFF", 4) != 0 || std::memcmp(head + 8, "sfbk", 4) != 0) {
        return fail("not a SoundFont 2 file (missing RIFF/sfbk header; SF3/SFZ/zip?)");
    }

    uint64_t pos = 12;
    while (pos + 8 <= fileSize) {
        unsigned char ch[8];
        if (!detail::readExact(fd, pos, ch, sizeof(ch))) return fail("read error in chunk header");
        const uint32_t chunkSize = detail::le32(ch + 4);
        const uint64_t dataStart = pos + 8;

        const bool isList = std::memcmp(ch, "LIST", 4) == 0;
        if (isList && chunkSize >= 4) {
            unsigned char type[4];
            if (!detail::readExact(fd, dataStart, type, sizeof(type))) return fail("read error in LIST type");
            if (std::memcmp(type, "pdta", 4) == 0) {
                const uint64_t listEnd = dataStart + chunkSize;
                if (listEnd > fileSize) {
                    return fail("file is truncated inside the preset data (copy/download incomplete? need " +
                                std::to_string(listEnd) + " bytes, file has " + std::to_string(fileSize) + ")");
                }
                uint64_t sub = dataStart + 4;
                while (sub + 8 <= listEnd) {
                    unsigned char sh[8];
                    if (!detail::readExact(fd, sub, sh, sizeof(sh))) return fail("read error in pdta");
                    const uint32_t subSize = detail::le32(sh + 4);
                    const uint64_t subData = sub + 8;
                    if (subData + subSize > listEnd) return fail("corrupt pdta sub-chunk size");
                    if (std::memcmp(sh, "phdr", 4) == 0) {
                        if (subSize < 38 || subSize % 38 != 0) {
                            return fail("invalid phdr size " + std::to_string(subSize));
                        }
                        out.bytes.resize(subSize);
                        if (!detail::readExact(fd, subData, out.bytes.data(), subSize)) {
                            return fail("read error in phdr");
                        }
                        out.fileOffset = subData;
                        out.size = subSize;
                        return true;
                    }
                    sub = subData + subSize + (subSize & 1u);
                }
                return fail("pdta has no phdr chunk");
            }
        }

        // Non-pdta chunk: sample data can be hundreds of MB, just skip it.
        if (dataStart + chunkSize > fileSize) {
            return fail("file is truncated (chunk '" + std::string(reinterpret_cast<char*>(ch), 4) +
                        "' needs " + std::to_string(dataStart + chunkSize) + " bytes, file has " +
                        std::to_string(fileSize) + "). Re-copy the SF2 file.");
        }
        pos = dataStart + chunkSize + (chunkSize & 1u);
    }
    return fail("no pdta/phdr chunk found");
}

inline bool sameBytes(const PhdrChunk& a, const PhdrChunk& b) {
    return a.size == b.size && a.bytes == b.bytes;
}

// Streams `src` to `dst` in 1 MiB blocks, replacing the phdr payload with
// `patched.bytes` on the way (same size, so every other offset is unchanged).
// Writes to dst + ".tmp" and renames, so an interrupted copy never leaves a
// file that looks complete. Checks free space first so a full disk gives a
// clear message instead of a truncated SF2.
inline bool copyWithPatchedPhdr(const std::string& src, const std::string& dst,
                                const PhdrChunk& patched, std::string* err) {
    auto fail = [&](const std::string& why) {
        if (err) *err = why;
        return false;
    };
    if (patched.bytes.size() != patched.size || patched.size == 0) return fail("bad patch");

    const int in = ::open(src.c_str(), O_RDONLY | O_CLOEXEC);
    if (in < 0) return fail(std::string("cannot open source (") + std::strerror(errno) + ")");
    struct Closer { int fd; ~Closer() { if (fd >= 0) ::close(fd); } } inCloser{in};
    const uint64_t total = detail::fileSizeOf(in);
    if (patched.fileOffset + patched.size > total) return fail("patch outside file");

    const std::string slash = "/";
    const size_t cut = dst.rfind('/');
    const std::string dir = cut == std::string::npos ? "." : dst.substr(0, cut);
    struct statvfs vfs {};
    if (::statvfs(dir.c_str(), &vfs) == 0) {
        const uint64_t freeBytes = static_cast<uint64_t>(vfs.f_bavail) * static_cast<uint64_t>(vfs.f_frsize);
        if (freeBytes < total + (8ull << 20)) {
            return fail("not enough free storage for the working copy (need " +
                        std::to_string((total >> 20) + 8) + " MB, free " +
                        std::to_string(freeBytes >> 20) + " MB)");
        }
    }

    const std::string tmp = dst + ".tmp";
    const int outFd = ::open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (outFd < 0) return fail(std::string("cannot create working copy (") + std::strerror(errno) + ")");
    Closer outCloser{outFd};

    std::vector<unsigned char> buf(1u << 20);
    uint64_t off = 0;
    while (off < total) {
        const size_t want = static_cast<size_t>(std::min<uint64_t>(buf.size(), total - off));
        if (!detail::readExact(in, off, buf.data(), want)) {
            ::unlink(tmp.c_str());
            return fail("read error while copying");
        }
        // Overlay the patched phdr bytes onto the part of this block it covers.
        const uint64_t blockEnd = off + want;
        const uint64_t pStart = patched.fileOffset;
        const uint64_t pEnd = patched.fileOffset + patched.size;
        if (pStart < blockEnd && pEnd > off) {
            const uint64_t from = std::max(pStart, off);
            const uint64_t to = std::min(pEnd, blockEnd);
            std::memcpy(buf.data() + (from - off), patched.bytes.data() + (from - pStart),
                        static_cast<size_t>(to - from));
        }
        if (!detail::writeExact(outFd, buf.data(), want)) {
            const int e = errno;
            ::unlink(tmp.c_str());
            return fail(std::string("write error while copying (") + std::strerror(e) + ")");
        }
        off = blockEnd;
    }
    ::close(outCloser.fd);
    outCloser.fd = -1;
    if (::rename(tmp.c_str(), dst.c_str()) != 0) {
        const int e = errno;
        ::unlink(tmp.c_str());
        return fail(std::string("cannot finalize working copy (") + std::strerror(e) + ")");
    }
    return true;
}

}  // namespace sf2
